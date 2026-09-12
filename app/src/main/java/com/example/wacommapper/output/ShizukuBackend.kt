package com.example.wacommapper.output

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class ShizukuBackendStatus { NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, READY, FAILED }

data class ShizukuBackendState(
    val installed: Boolean = false,
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val systemPermissionGranted: Boolean = false,
    val runningUid: Int? = null,
    val status: ShizukuBackendStatus = ShizukuBackendStatus.NOT_INSTALLED,
    val detail: String = "",
    val userServiceConnected: Boolean = false,
    val userServiceUid: Int? = null,
)

data class ShizukuInjectionAttempt(
    val name: String,
    val timestampMillis: Long,
    val methodApi: String,
    val returned: Boolean?,
    val exception: String?,
    val securityException: Boolean,
    val detail: String,
) {
    val timestamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
}

data class ShizukuMotionProbe(
    val action: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val source: Int,
    val toolType: Int,
    val buttonState: Int = 0,
    val actionButton: Int = 0,
    val downTimeMillis: Long,
    val eventTimeMillis: Long,
    val displayId: Int = 0,
)

data class ShizukuTapDiagnostic(
    val testName: String,
    val displayId: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotationDegrees: Int,
    val configurationOrientation: String,
    val mappingOrientation: String,
    val x: Float,
    val y: Float,
    val downTimeMillis: Long? = null,
    val downEventTimeMillis: Long? = null,
    val upEventTimeMillis: Long? = null,
    val source: Int = InputDevice.SOURCE_TOUCHSCREEN,
    val toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    val downAttempt: ShizukuInjectionAttempt? = null,
    val upAttempt: ShizukuInjectionAttempt? = null,
)

/** Shizuku shell-UID backend used only for explicit, bounded Phase 5 tests. */
class ShizukuBackend(
    context: Context,
    private val onStateChanged: (ShizukuBackendState) -> Unit,
) : StylusOutputBackend {
    private val appContext = context.applicationContext
    @Volatile private var backendState = ShizukuBackendState()
    @Volatile private var remote: IShizukuInjectionService? = null
    @Volatile private var closed = false
    @Volatile private var contactActive = false
    @Volatile private var hoverActive = false
    @Volatile private var stylusButtonState = 0
    private var contactDownTime = 0L
    private var hoverDownTime = 0L
    private val liveLogCounter = AtomicLong(0L)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        val oldRemote = remote
        if (oldRemote != null) {
            Thread {
                runCatching { oldRemote.emergencyStop(0f, 0f, SystemClock.uptimeMillis()) }
                    .onFailure { Log.w(TAG_INJECTION, "Best-effort release after Shizuku binder death failed", it) }
            }.start()
        }
        remote = null
        update(backendState.copy(binderAvailable = false, permissionGranted = false, userServiceConnected = false, userServiceUid = null, status = ShizukuBackendStatus.NOT_RUNNING, detail = "Shizuku binder died."))
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_PERMISSION_CODE) {
            Log.i(TAG_PERMISSION, "request result=${grantResult == PackageManager.PERMISSION_GRANTED}")
            refresh()
        }
    }

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, ShizukuInjectionUserService::class.java),
    ).processNameSuffix("wacommapper_injector")
        .tag("wacommapper-shizuku-injector-v2")
        .version(2)
        .daemon(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IShizukuInjectionService.Stub.asInterface(binder)
            val uid = runCatching { remote?.runningUid }.getOrNull()
            Log.i(TAG_STATUS, "UserService connected uid=$uid")
            if (uid != SHIZUKU_SHELL_UID) {
                update(backendState.copy(
                    userServiceConnected = true,
                    userServiceUid = uid,
                    status = ShizukuBackendStatus.FAILED,
                    detail = "Expected non-root Shizuku shell UID $SHIZUKU_SHELL_UID; got ${uid ?: "unknown"}.",
                ))
            } else {
                update(backendState.copy(userServiceConnected = true, userServiceUid = uid, detail = "Shizuku UserService connected as shell (UID $uid)."))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            update(backendState.copy(userServiceConnected = false, userServiceUid = null, detail = "Shizuku UserService disconnected."))
        }
    }

    init {
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
            .onFailure { Log.w(TAG_STATUS, "Could not register binder listener", it) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            .onFailure { Log.w(TAG_STATUS, "Could not register binder-dead listener", it) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
            .onFailure { Log.w(TAG_PERMISSION, "Could not register permission listener", it) }
        refresh()
    }

    @Synchronized
    fun refresh(): ShizukuBackendState {
        val installed = isShizukuInstalled()
        if (!installed) return publish(ShizukuBackendState(
            installed = false,
            status = ShizukuBackendStatus.NOT_INSTALLED,
            detail = "Install the official Shizuku app, then start its service.",
        ))

        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) return publish(ShizukuBackendState(
            installed = true,
            binderAvailable = false,
            status = ShizukuBackendStatus.NOT_RUNNING,
            detail = "Shizuku is installed, but its ADB service/binder is not running.",
        ))

        val uid = runCatching { Shizuku.getUid() }.getOrNull()
        val appPermission = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val systemPermission = runCatching {
            Shizuku.checkRemotePermission(INJECT_EVENTS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val granted = appPermission && systemPermission
        val status = when {
            !appPermission -> ShizukuBackendStatus.PERMISSION_REQUIRED
            uid != SHIZUKU_SHELL_UID -> ShizukuBackendStatus.FAILED
            !systemPermission -> ShizukuBackendStatus.FAILED
            else -> ShizukuBackendStatus.READY
        }
        val detail = when {
            !appPermission -> "Grant WacomMapper permission in Shizuku."
            uid != SHIZUKU_SHELL_UID -> "This test requires Shizuku's non-root ADB shell UID ($SHIZUKU_SHELL_UID); current UID is $uid."
            !systemPermission -> "Shizuku permission is granted, but server UID $uid lacks android.permission.INJECT_EVENTS on this build."
            else -> "Shizuku permission and INJECT_EVENTS are available; tap Connect backend to bind the isolated service."
        }
        Log.i(TAG_STATUS, "installed=$installed binder=$binderAvailable appPermission=$appPermission systemPermission=$systemPermission uid=$uid status=$status")
        Log.i(TAG_PERMISSION, "WacomMapper=$appPermission ${INJECT_EVENTS_PERMISSION}=$systemPermission")
        return publish(ShizukuBackendState(
            installed = true,
            binderAvailable = true,
            permissionGranted = appPermission,
            systemPermissionGranted = systemPermission,
            runningUid = uid,
            status = status,
            detail = detail,
            userServiceConnected = remote != null,
            userServiceUid = backendState.userServiceUid,
        ))
    }

    fun requestPermission() {
        if (!backendState.binderAvailable) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(REQUEST_PERMISSION_CODE) }
            .onFailure { failStatus("Permission request failed", it) }
    }

    fun connectUserService() {
        val current = refresh()
        if (current.status != ShizukuBackendStatus.READY) return
        if (remote != null) return
        runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }
            .onFailure { failStatus("Could not bind Shizuku UserService", it) }
    }

    fun injectManual(
        name: String,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        source: Int,
        toolType: Int,
        buttonState: Int = 0,
        actionButton: Int = 0,
        downTimeMillis: Long = SystemClock.uptimeMillis(),
        eventTimeMillis: Long = SystemClock.uptimeMillis(),
        displayId: Int = 0,
        injectionMode: Int = ShizukuInputManagerBackend.INJECT_ASYNC,
    ): ShizukuInjectionAttempt {
        if (name.startsWith("LIVE_GLOBAL")) liveLogCounter.incrementAndGet()
        val target = remote
        if (backendState.status != ShizukuBackendStatus.READY || target == null) {
            return attempt(name, "InputManager reflection (Shizuku UserService)", null, "Shizuku backend is not READY/connected.", false)
        }
        return try {
            val response = target.injectMotion(
                action, x, y, pressure, source, toolType, buttonState, actionButton, downTimeMillis, eventTimeMillis, displayId, injectionMode,
            )
            val returned = Regex("returned=(true|false)").find(response)?.groupValues?.get(1)?.toBooleanStrictOrNull()
            val api = Regex("api=([^;]+)").find(response)?.groupValues?.get(1) ?: "InputManager reflection"
            val priorException = Regex("priorException=(.*?);uid=").find(response)?.groupValues?.get(1)
                ?.takeUnless { it == "none" }
                ?.replace("\\n", "\n")
            val details = "action=$action xy=($x,$y) displayId=$displayId pressure=$pressure source=0x${source.toString(16)} toolType=$toolType buttonState=0x${buttonState.toString(16)} actionButton=0x${actionButton.toString(16)} downTime=$downTimeMillis eventTime=$eventTimeMillis mode=${if (injectionMode == 0) "ASYNC" else if (injectionMode == 1) "WAIT_FOR_RESULT" else "WAIT_FOR_FINISH"}; $response"
            val record = attempt(name, api, returned, priorException, priorException?.contains("SecurityException") == true, details)
            logAttempt(record)
            record
        } catch (throwable: Throwable) {
            val cause = unwrap(throwable)
            val security = cause is SecurityException || cause.cause is SecurityException
            val record = attempt(
                name,
                "InputManager/InputManagerGlobal.injectInputEvent via Shizuku UserService",
                null,
                Log.getStackTraceString(throwable).ifBlank { "${cause.javaClass.name}: ${cause.message ?: "no message"}" },
                security,
            )
            logAttempt(record)
            if (security || record.exception != null) {
                update(backendState.copy(status = ShizukuBackendStatus.FAILED, detail = "Injection backend failed: ${record.exception}"))
            }
            record
        }
    }

    fun injectManual(name: String, probe: ShizukuMotionProbe): ShizukuInjectionAttempt = injectManual(
        name = name,
        action = probe.action,
        x = probe.x,
        y = probe.y,
        pressure = probe.pressure,
        source = probe.source,
        toolType = probe.toolType,
        buttonState = probe.buttonState,
        actionButton = probe.actionButton,
        downTimeMillis = probe.downTimeMillis,
        eventTimeMillis = probe.eventTimeMillis,
        displayId = probe.displayId,
        injectionMode = ShizukuInputManagerBackend.INJECT_ASYNC,
    )

    fun emergencyStop(x: Float, y: Float): ShizukuInjectionAttempt {
        contactActive = false
        hoverActive = false
        stylusButtonState = 0
        val target = remote ?: return attempt("STOP", "Shizuku UserService emergencyStop", null, "No connected UserService.", false)
        return try {
            val now = SystemClock.uptimeMillis()
            val response = target.emergencyStop(x, y, now)
            val failed = response.startsWith("Stylus release failed")
            val record = attempt("STOP", "Shizuku UserService emergency release", !failed, null, false, response)
            logAttempt(record)
            record
        } catch (throwable: Throwable) {
            val cause = unwrap(throwable)
            val record = attempt("STOP", "Shizuku UserService emergencyStop", null, "${cause.javaClass.name}: ${cause.message}", cause is SecurityException)
            logAttempt(record)
            record
        }
    }

    override fun send(event: MappedStylusEvent): OutputResult {
        if (backendState.status != ShizukuBackendStatus.READY || remote == null) {
            return OutputResult(OutputSupport.UNSUPPORTED, "Shizuku backend is not READY/connected.")
        }
        val x = event.x
        val y = event.y
        val now = SystemClock.uptimeMillis()
        val sideState = if (event.sideButton == true) MotionEvent.BUTTON_STYLUS_PRIMARY else 0
        val inRange = event.inRange
        val tip = event.tip
        var latest: ShizukuInjectionAttempt? = null
        var attemptedEvents = 0
        var injectedEvents = 0
        var rejectedEvents = 0

        fun inject(name: String, action: Int, pressure: Float, buttons: Int, actionButton: Int = 0, downTime: Long): ShizukuInjectionAttempt {
            val result = injectManual(name, action, x, y, pressure, InputDevice.SOURCE_STYLUS,
                MotionEvent.TOOL_TYPE_STYLUS, buttons, actionButton, downTime, now)
            attemptedEvents++
            if (result.returned == true && result.exception == null) injectedEvents++ else rejectedEvents++
            latest = result
            return result
        }

        if (tip == true) {
            if (!contactActive) contactDownTime = now
            val action = if (contactActive) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_DOWN
            latest = inject("LIVE_WACOM_${actionName(action)}", action, event.pressure, sideState, downTime = contactDownTime)
            contactActive = latest.returned == true
            if (sideState != stylusButtonState) {
                val buttonAction = if (sideState == 0) MotionEvent.ACTION_BUTTON_RELEASE else MotionEvent.ACTION_BUTTON_PRESS
                latest = inject("LIVE_WACOM_SIDE_BUTTON", buttonAction, 0f, sideState, MotionEvent.BUTTON_STYLUS_PRIMARY, contactDownTime)
                stylusButtonState = sideState
            }
        } else {
            if (contactActive) {
                latest = inject("LIVE_WACOM_UP", MotionEvent.ACTION_UP, 0f, 0, downTime = contactDownTime)
                contactActive = false
            }
            if (inRange == true) {
                if (!hoverActive) hoverDownTime = now
                val action = if (hoverActive) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_HOVER_ENTER
                latest = inject("LIVE_WACOM_${actionName(action)}", action, 0f, sideState, downTime = hoverDownTime)
                hoverActive = latest.returned == true
                if (sideState != stylusButtonState) {
                    val buttonAction = if (sideState == 0) MotionEvent.ACTION_BUTTON_RELEASE else MotionEvent.ACTION_BUTTON_PRESS
                    latest = inject("LIVE_WACOM_SIDE_BUTTON", buttonAction, 0f, sideState, MotionEvent.BUTTON_STYLUS_PRIMARY, hoverDownTime)
                    stylusButtonState = sideState
                }
            } else if (inRange == false && hoverActive) {
                latest = inject("LIVE_WACOM_HOVER_EXIT", MotionEvent.ACTION_HOVER_EXIT, 0f, 0, downTime = hoverDownTime)
                hoverActive = false
                stylusButtonState = 0
            }
        }
        val sentCount = liveLogCounter.incrementAndGet()
        if (sentCount == 1L || sentCount % 25L == 0L) {
            Log.i(TAG_INJECTION, "LIVE_WACOM reportsSent=$sentCount result=${latest?.returned} ${latest?.methodApi}")
        }
        return if (latest?.returned == true) OutputResult(
            OutputSupport.SUPPORTED,
            "InputManager accepted the latest live stylus event.",
            attemptedEvents,
            injectedEvents,
            rejectedEvents,
            latest?.methodApi,
            latest?.returned,
            latest?.exception,
        ) else OutputResult(
            OutputSupport.PARTIAL,
            "Live event not confirmed; see SHIZUKU_INJECTION / SHIZUKU_ERROR.",
            attemptedEvents,
            injectedEvents,
            rejectedEvents,
            latest?.methodApi,
            latest?.returned,
            latest?.exception,
        )
    }

    override fun releaseAllInput(): OutputResult {
        val attempt = emergencyStop(0f, 0f)
        val accepted = attempt.returned == true && attempt.exception == null
        return OutputResult(
            support = if (accepted) OutputSupport.SUPPORTED else OutputSupport.PARTIAL,
            message = attempt.detail,
            attemptedEvents = 1,
            injectedEvents = if (accepted) 1 else 0,
            rejectedEvents = if (accepted) 0 else 1,
            lastMethod = attempt.methodApi,
            lastResult = attempt.returned,
            lastException = attempt.exception,
        )
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        emergencyStop(0f, 0f)
        runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
            .onFailure { Log.w(TAG_STATUS, "Unbind failed", it) }
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
        remote = null
    }

    private fun isShizukuInstalled(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            appContext.packageManager.getPackageInfo(
                SHIZUKU_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Synchronized
    private fun publish(state: ShizukuBackendState): ShizukuBackendState {
        update(state)
        return state
    }

    private fun update(state: ShizukuBackendState) {
        backendState = state
        Log.i(TAG_STATUS, "state=${state.status} binder=${state.binderAvailable} permission=${state.permissionGranted} uid=${state.runningUid} service=${state.userServiceConnected}")
        onStateChanged(state)
    }

    private fun failStatus(message: String, throwable: Throwable) {
        val cause = unwrap(throwable)
        Log.e(TAG_ERROR, "$message: ${cause.javaClass.name}: ${cause.message}", cause)
        update(backendState.copy(status = ShizukuBackendStatus.FAILED, detail = "$message: ${cause.javaClass.simpleName}: ${cause.message}"))
    }

    private fun attempt(
        name: String,
        api: String,
        returned: Boolean?,
        exception: String?,
        security: Boolean,
        detail: String = "",
    ) = ShizukuInjectionAttempt(name, System.currentTimeMillis(), api, returned, exception, security, detail)

    private fun logAttempt(attempt: ShizukuInjectionAttempt) {
        val line = "${attempt.name} timestamp=${attempt.timestamp} api=${attempt.methodApi} returned=${attempt.returned} securityException=${attempt.securityException} exception=${attempt.exception} detail=${attempt.detail}"
        val liveCount = liveLogCounter.get()
        if (attempt.exception != null) Log.e(TAG_ERROR, line)
        else if (!attempt.name.startsWith("LIVE_") || liveCount <= 1L || liveCount % 25L == 0L) Log.i(TAG_INJECTION, line)
        if (attempt.securityException) Log.e(TAG_PERMISSION, "SecurityException during ${attempt.name}: ${attempt.exception}")
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var cause = throwable
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        else -> action.toString()
    }

    companion object {
        const val REQUEST_PERMISSION_CODE = 5821
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_SHELL_UID = 2_000
        const val INJECT_EVENTS_PERMISSION = "android.permission.INJECT_EVENTS"
        private const val TAG_STATUS = "SHIZUKU_STATUS"
        private const val TAG_PERMISSION = "SHIZUKU_PERMISSION"
        private const val TAG_INJECTION = "SHIZUKU_INJECTION"
        private const val TAG_ERROR = "SHIZUKU_ERROR"
    }
}
