package com.example.wacommapper.output

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.wacommapper.MainActivity
import com.example.wacommapper.R
import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.StylusStateMapper
import com.example.wacommapper.mapping.TabletCoordinateConfig
import com.example.wacommapper.output.HoverCursorOverlay
import com.example.wacommapper.usb.Ctl472RawReportParser
import com.example.wacommapper.usb.HidConnectionState
import com.example.wacommapper.usb.HidRawReport
import com.example.wacommapper.usb.HidTransferCounters
import com.example.wacommapper.usb.WacomUsbSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ProductStylusStatus { INACTIVE, STARTING, ACTIVE, ERROR }
enum class ProductTabletStatus { DISCONNECTED, CONNECTED, PERMISSION_REQUIRED, ERROR }

data class ProductSessionState(
    val stylus: ProductStylusStatus = ProductStylusStatus.INACTIVE,
    val tablet: ProductTabletStatus = ProductTabletStatus.DISCONNECTED,
    val shizuku: ShizukuBackendStatus = ShizukuBackendStatus.NOT_INSTALLED,
    val message: String? = null,
    val reports: Long = 0,
    val pressure: Float = 0f,
    val hover: Boolean = false,
    val contact: Boolean = false,
    val x: Int? = null,
    val y: Int? = null,
    val transfers: HidTransferCounters = HidTransferCounters(),
)

/** Owns the daily-use global session independently from any Activity/Compose lifecycle. */
class LiveWacomForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): LiveWacomForegroundService = this@LiveWacomForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ProductSessionState())
    val state: StateFlow<ProductSessionState> = mutableState.asStateFlow()
    private lateinit var preferences: WacomPreferences
    private lateinit var usbSessions: WacomUsbSessionManager
    private lateinit var shizuku: ShizukuBackend
    private lateinit var output: ShizukuInputManagerBackend
    private val parser = Ctl472RawReportParser()
    private var stateMapper = StylusStateMapper()
    private var started = false
    private var stopping = false
    private var usbStarting = false
    private var reportCount = 0L
    private var latestConnection = HidConnectionState()
    private var latestDisplaySize = 1 to 1

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra()
                    if (device?.vendorId == CTL472_VID && device.productId == CTL472_PID) stopSession("Wacom desconectada.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshTablet()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = WacomPreferences(this)
        HoverCursorOverlay.setCursorSize(this, preferences.cursorSize.diameterDp)
        HoverCursorOverlay.setCursorColor(this, preferences.cursorColor)
        HoverCursorOverlay.setEnabled(this, preferences.showHoverCursor)
        usbSessions = WacomUsbSessionManager(
            context = this,
            onConnectionChanged = { latestConnection = it },
            onReport = ::processReport,
            onError = { message ->
                Log.e(TAG, "USB session error: $message")
                stopSession(if (message.contains("disconnect", true)) "Wacom desconectada." else message)
            },
            onTransferCounters = { counters -> mutableState.value = mutableState.value.copy(transfers = counters) },
            onFatal = { throwable ->
                Log.e(TAG, "Fatal USB reader error", throwable)
                stopSession("Error al leer la Wacom: ${throwable.message ?: throwable.javaClass.simpleName}")
            },
        )
        shizuku = ShizukuBackend(this) { backendState ->
            mutableState.value = mutableState.value.copy(shizuku = backendState.status)
            if (started && backendState.status == ShizukuBackendStatus.READY && backendState.userServiceConnected) {
                startUsbIfReady()
            } else if (started && backendState.status != ShizukuBackendStatus.READY) {
                stopSession("Shizuku se desconectó.")
            }
        }
        output = ShizukuInputManagerBackend(shizuku)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshTablet()
        latestDisplaySize = readLogicalDisplaySize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession("Detenido desde la notificación.")
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Preparando stylus…"))
        if (intent?.action == ACTION_START) beginSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startGlobal() {
        if (started) return
        started = true
        stopping = false
        reportCount = 0
        stateMapper = StylusStateMapper(
            tipDownThreshold = preferences.tipDownThreshold,
            tipUpThreshold = preferences.tipUpThreshold,
        )
        HoverCursorOverlay.setCursorSize(this, preferences.cursorSize.diameterDp)
        HoverCursorOverlay.setCursorColor(this, preferences.cursorColor)
        HoverCursorOverlay.setEnabled(this, preferences.showHoverCursor)
        mutableState.value = mutableState.value.copy(stylus = ProductStylusStatus.STARTING, message = null, reports = 0)
        updateNotification("Iniciando Wacom CTL-472…")
        val current = shizuku.refresh()
        mutableState.value = mutableState.value.copy(shizuku = current.status)
        if (current.status != ShizukuBackendStatus.READY) {
            fail("${shizukuMessage(current.status)}"); return
        }
        shizuku.connectUserService()
        if (current.userServiceConnected) startUsbIfReady()
    }

    fun stopGlobal() = stopSession(null)

    fun updateCursorPreference(enabled: Boolean) {
        preferences.showHoverCursor = enabled
        HoverCursorOverlay.setEnabled(this, enabled)
        if (!enabled) HoverCursorOverlay.hide()
    }

    fun updatePressureThresholds(down: Int, up: Int) {
        if (down !in 1..2_047 || up !in 0 until down) return
        preferences.tipDownThreshold = down
        preferences.tipUpThreshold = up
        stateMapper.setThresholds(down, up)
    }

    fun refreshTablet() {
        val found = usbManager().deviceList.values.any { it.vendorId == CTL472_VID && it.productId == CTL472_PID }
        if (!found && !started) mutableState.value = mutableState.value.copy(tablet = ProductTabletStatus.DISCONNECTED)
        else if (found && !started) mutableState.value = mutableState.value.copy(tablet = ProductTabletStatus.CONNECTED)
    }

    private fun beginSession() {
        val device = usbManager().deviceList.values.firstOrNull { it.vendorId == CTL472_VID && it.productId == CTL472_PID }
        if (device == null) { fail("Conecta la Wacom CTL-472.", ProductTabletStatus.DISCONNECTED); return }
        if (!usbManager().hasPermission(device)) { fail("Concede permiso USB a la Wacom.", ProductTabletStatus.PERMISSION_REQUIRED); return }
        startGlobal()
    }

    private fun startUsbIfReady() {
        if (!started || stopping || usbStarting || latestConnection.isOpen) return
        usbStarting = true
        scope.launch {
            try {
                val device = usbManager().deviceList.values.firstOrNull { it.vendorId == CTL472_VID && it.productId == CTL472_PID }
                    ?: throw IllegalStateException("Conecta la Wacom CTL-472.")
                if (!usbManager().hasPermission(device)) throw SecurityException("Concede permiso USB a la Wacom.")
                usbSessions.stopAndWait()
                val ok = usbSessions.startFresh(device.deviceName, 0, 0x81)
                if (!ok) throw IllegalStateException("No se pudo abrir Interface 0 / endpoint 0x81.")
                val connection = usbSessions.connectionState()
                if (!connection.isOpen || connection.claimedInterfaceId != 0 || connection.activeEndpointAddress != 0x81) {
                    throw IllegalStateException("No se pudo reclamar la interfaz de lápiz de la Wacom.")
                }
                latestConnection = connection
                mutableState.value = mutableState.value.copy(
                    tablet = ProductTabletStatus.CONNECTED,
                    stylus = ProductStylusStatus.ACTIVE,
                    message = null,
                )
                updateNotification("Stylus activo")
            } catch (throwable: Throwable) {
                Log.e(TAG, "Could not start USB session", throwable)
                fail(throwable.message ?: "No se pudo iniciar la sesión USB.", if (throwable is SecurityException) ProductTabletStatus.PERMISSION_REQUIRED else ProductTabletStatus.ERROR)
            } finally {
                usbStarting = false
            }
        }
    }

    private fun processReport(raw: HidRawReport) {
        if (!started || stopping) return
        val parsed = parser.parse(raw.bytes)
        val update = stateMapper.process(parsed, raw.timestamp)
        val state = update.state
        val display = latestDisplaySize
        for (event in update.events) {
            val rawX = event.xRaw ?: state.xRaw ?: continue
            val rawY = event.yRaw ?: state.yRaw ?: continue
            val coords = CoordinateMapper.map(
                rawX.toDouble(), rawY.toDouble(), display.first, display.second,
                TabletCoordinateConfig(), preferences.mappingOptions,
            )
            val mapped = MappedStylusEvent(
                timestampMillis = event.timestampMillis,
                x = coords.screenX.toFloat().coerceIn(0f, (display.first - 1).coerceAtLeast(0).toFloat()),
                y = coords.screenY.toFloat().coerceIn(0f, (display.second - 1).coerceAtLeast(0).toFloat()),
                pressure = if (state.contact) state.pressureNormalized else 0f,
                tip = state.contact,
                sideButton = state.sideButton1,
                inRange = state.present,
                eventType = event.type,
                sideButton2 = state.sideButton2,
                inputElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos(),
            )
            val cursor = HoverCursorStateReducer.onEvent(
                HoverCursorVisualState(visible = false), mapped, sessionActive = true,
            )
            if (preferences.showHoverCursor && cursor.visible && android.provider.Settings.canDrawOverlays(this)) {
                HoverCursorOverlay.setEnabled(this, true)
                HoverCursorOverlay.show(this, mapped.x, mapped.y, android.os.SystemClock.elapsedRealtimeNanos())
            } else HoverCursorOverlay.hide()
            val result = output.send(mapped)
            if (result.lastResult != true || result.lastException != null) {
                Log.e(TAG, "Input injection failed: ${result.lastException ?: result.message}")
                stopSession("Falló la inyección del stylus.")
                return
            }
        }
        reportCount++
        mutableState.value = mutableState.value.copy(
            stylus = ProductStylusStatus.ACTIVE,
            tablet = ProductTabletStatus.CONNECTED,
            reports = reportCount,
            pressure = state.pressureNormalized,
            hover = state.hover,
            contact = state.contact,
            x = state.xRaw,
            y = state.yRaw,
        )
    }

    private fun stopSession(message: String?) {
        if (stopping) return
        stopping = true
        val wasRunning = started
        started = false
        usbStarting = false
        if (::output.isInitialized && wasRunning) runCatching { output.releaseAllInput() }
        HoverCursorOverlay.hide()
        HoverCursorOverlay.remove()
        scope.launch {
            if (::usbSessions.isInitialized) runCatching { usbSessions.stopAndWait() }
            mutableState.value = mutableState.value.copy(
                stylus = if (message != null && wasRunning) ProductStylusStatus.ERROR else ProductStylusStatus.INACTIVE,
                message = message,
                hover = false,
                contact = false,
                pressure = 0f,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopping = false
        }
    }

    private fun fail(message: String, tablet: ProductTabletStatus = mutableState.value.tablet) {
        started = false
        runCatching { if (::output.isInitialized) output.releaseAllInput() }
        HoverCursorOverlay.remove()
        mutableState.value = mutableState.value.copy(stylus = ProductStylusStatus.ERROR, tablet = tablet, message = message)
        scope.launch {
            if (::usbSessions.isInitialized) runCatching { usbSessions.stopAndWait() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shizukuMessage(status: ShizukuBackendStatus): String = when (status) {
        ShizukuBackendStatus.NOT_INSTALLED -> "Instala Shizuku para iniciar el stylus global."
        ShizukuBackendStatus.NOT_RUNNING -> "Inicia Shizuku y vuelve a intentarlo."
        ShizukuBackendStatus.PERMISSION_REQUIRED -> "Concede permiso a WacomMapper en Shizuku."
        ShizukuBackendStatus.FAILED -> "Shizuku no está listo para inyectar eventos."
        ShizukuBackendStatus.READY -> ""
    }

    private fun readLogicalDisplaySize(): Pair<Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display?.getRealSize(size)
        if (size.x > 0 && size.y > 0) return size.x to size.y
        val metrics = getSystemService(WindowManager::class.java).defaultDisplay
        @Suppress("DEPRECATION")
        metrics.getRealSize(size)
        return size.x.coerceAtLeast(1) to size.y.coerceAtLeast(1)
    }

    private fun usbManager() = getSystemService(UsbManager::class.java)

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): android.hardware.usb.UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stylus global activo", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 4726, Intent(this, LiveWacomForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 4725, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("WacomMapper")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(started)
            .addAction(0, "DETENER", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        if (started) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        started = false
        runCatching { output.releaseAllInput() }
        HoverCursorOverlay.remove()
        runCatching { usbSessions.close() }
        runCatching { shizuku.close() }
        runCatching { unregisterReceiver(usbReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.wacommapper.action.START_GLOBAL"
        const val ACTION_STOP = "com.example.wacommapper.action.STOP_GLOBAL"
        private const val CHANNEL_ID = "wacommapper_stylus_active"
        private const val NOTIFICATION_ID = 4725
        private const val TAG = "WACOM_SERVICE"
        private const val CTL472_VID = 0x056A
        private const val CTL472_PID = 0x037A

        fun start(context: Context) {
            val intent = Intent(context, LiveWacomForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun startDiagnosticNotification(context: Context) {
            val intent = Intent(context, LiveWacomForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveWacomForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
