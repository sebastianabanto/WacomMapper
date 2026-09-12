package com.example.wacommapper

import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.provider.Settings
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.wacommapper.ui.theme.WacomMapperTheme
import com.example.wacommapper.ui.UsbDiagnosticsScreen
import com.example.wacommapper.ui.HidRawDiagnosticsScreen
import com.example.wacommapper.ui.HidExperimentWizardScreen
import com.example.wacommapper.ui.ParsedStylusDiagnosticsScreen
import com.example.wacommapper.ui.MappingDiagnosticsScreen
import com.example.wacommapper.ui.OutputBackendDiagnosticsScreen
import com.example.wacommapper.display.AndroidDisplayInfo
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.TabletCoordinateConfig
import com.example.wacommapper.mapping.LiveStylusEventFrame
import com.example.wacommapper.mapping.StylusEventType
import com.example.wacommapper.mapping.StylusCanvasPoint
import com.example.wacommapper.output.MappedStylusEvent
import com.example.wacommapper.output.LiveWacomMetrics
import com.example.wacommapper.output.LiveParsedTestSummary
import com.example.wacommapper.output.LiveStopReason
import com.example.wacommapper.output.UsbOpenOnlyDiagnostic
import com.example.wacommapper.output.LegacyHidDiagnostic
import com.example.wacommapper.output.LiveWacomForegroundService
import com.example.wacommapper.output.ProductSessionState
import com.example.wacommapper.output.ProductStylusStatus
import com.example.wacommapper.output.ProductTabletStatus
import com.example.wacommapper.output.HoverCursorSize
import com.example.wacommapper.output.PressureSensitivity
import com.example.wacommapper.output.WacomPreferences
import com.example.wacommapper.output.DEFAULT_HOVER_CURSOR_COLOR
import com.example.wacommapper.ui.ProductHomeScreen
import com.example.wacommapper.ui.ProductSettingsScreen
import com.example.wacommapper.output.HoverCursorOverlay
import com.example.wacommapper.output.HoverCursorStateReducer
import com.example.wacommapper.output.HoverCursorVisualState
import com.example.wacommapper.output.ShizukuBackend
import com.example.wacommapper.output.ShizukuInputManagerBackend
import com.example.wacommapper.output.InternalCanvasBackend
import com.example.wacommapper.output.LatencyWindow
import com.example.wacommapper.output.ShizukuBackendState
import com.example.wacommapper.output.ShizukuBackendStatus
import com.example.wacommapper.output.ShizukuInjectionAttempt
import com.example.wacommapper.output.ShizukuMotionProbe
import com.example.wacommapper.output.ShizukuTapDiagnostic
import com.example.wacommapper.usb.WacomCtl472ReportParser
import com.example.wacommapper.usb.CandidateByteOrder
import com.example.wacommapper.usb.HidConnectionState
import com.example.wacommapper.usb.HidExperimentAnalyzer
import com.example.wacommapper.usb.HidExperimentScenario
import com.example.wacommapper.usb.HidExperimentSession
import com.example.wacommapper.usb.ExperimentPhaseMarker
import com.example.wacommapper.usb.HidExperimentScenarios
import com.example.wacommapper.usb.RoundTwoExperimentAnalyzer
import com.example.wacommapper.usb.RoundThreeExperimentAnalyzer
import com.example.wacommapper.usb.WacomCtl472EvidenceAnalyzer
import com.example.wacommapper.usb.UsbDeviceDetector
import com.example.wacommapper.usb.UsbDeviceInfo
import com.example.wacommapper.usb.HidRawReport
import com.example.wacommapper.usb.HidTransferCounters
import com.example.wacommapper.usb.WacomUsbSessionManager
import com.example.wacommapper.usb.UsbPermissionStatus
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    private lateinit var preferences: WacomPreferences
    private var productService: LiveWacomForegroundService? = null
    private var productSessionState by mutableStateOf(ProductSessionState())
    private var showProductSettings by mutableStateOf(false)
    private var showDeveloperTools by mutableStateOf(false)
    private var cursorSize by mutableStateOf(HoverCursorSize.SMALL)
    private var cursorColor by mutableIntStateOf(DEFAULT_HOVER_CURSOR_COLOR)
    private var savedCursorColors by mutableStateOf<List<Int?>>(List(3) { null })
    private var pressureSensitivity by mutableStateOf(PressureSensitivity.NORMAL)
    private lateinit var usbAttachReceiver: android.content.BroadcastReceiver
    private var usbAttachReceiverRegistered = false
    private val productServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = (service as? LiveWacomForegroundService.LocalBinder)?.service() ?: return
            productService = local
            lifecycleScope.launch { local.state.collect { productSessionState = it } }
            local.refreshTablet()
        }

        override fun onServiceDisconnected(name: ComponentName?) { productService = null }
    }

    private lateinit var detector: UsbDeviceDetector
    private lateinit var usbSessionManager: WacomUsbSessionManager
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var shizukuBackend: ShizukuBackend
    private lateinit var shizukuInputBackend: ShizukuInputManagerBackend
    private var devices by mutableStateOf<List<UsbDeviceInfo>>(emptyList())
    private var selectedDeviceName by mutableStateOf<String?>(null)
    private var selectedInterface by mutableStateOf(0)
    private var connectionOpen by mutableStateOf(false)
    private var interfaceClaimed by mutableStateOf(false)
    private var totalReports by mutableStateOf(0L)
    private var reportsPerSecond by mutableStateOf(0)
    private var lastPacketSize by mutableStateOf(0)
    private var lastReport by mutableStateOf<HidRawReport?>(null)
    private var recentReports by mutableStateOf<List<HidRawReport>>(emptyList())
    private var captureError by mutableStateOf<String?>(null)
    private var connectionState by mutableStateOf(HidConnectionState())
    private var showExperimentWizard by mutableStateOf(true)
    private var showParsedDiagnostics by mutableStateOf(false)
    private var showMappingDiagnostics by mutableStateOf(false)
    private var showOutputDiagnostics by mutableStateOf(false)
    private var displayInfo by mutableStateOf<AndroidDisplayInfo?>(null)
    private var mappingOptions by mutableStateOf(MappingOptions())
    private var shizukuBackendState by mutableStateOf(ShizukuBackendState())
    private var overlayPermissionGranted by mutableStateOf(false)
    private var showHoverCursor by mutableStateOf(true)
    private var externalTapDiagnostic by mutableStateOf<ShizukuTapDiagnostic?>(null)
    private var externalTapCountdown by mutableIntStateOf(0)
    private var externalTapRunning by mutableStateOf(false)
    private var externalTapJob: Job? = null
    private var liveWacomSecondsRemaining by mutableIntStateOf(0)
    private var liveWacomMetrics by mutableStateOf(LiveWacomMetrics())
    private var liveStylusEventFrame by mutableStateOf(LiveStylusEventFrame())
    private var tipDownThreshold by mutableIntStateOf(5)
    private var tipUpThreshold by mutableIntStateOf(2)
    private var openUsbOnlyDiagnostic by mutableStateOf(UsbOpenOnlyDiagnostic())
    private var legacyHidDiagnostic by mutableStateOf(LegacyHidDiagnostic())
    private var liveSessionCompletion = CompletableDeferred<LiveWacomMetrics>()
    @Volatile private var liveReaderFatal: Throwable? = null
    @Volatile private var legacyHidCollecting = false
    private val legacyHidReportCount = AtomicLong(0L)
    private val livePipelineTraceCounter = AtomicLong(0L)
    private val liveMetricPipelineErrors = AtomicLong(0L)
    private val liveMetricTabletReports = AtomicLong(0L)
    private val liveMetricOutOfRangeReports = AtomicLong(0L)
    private val liveMetricGenericReports = AtomicLong(0L)
    private val liveMetricInvalidReports = AtomicLong(0L)
    private val liveMetricPressureMin = AtomicLong(Long.MAX_VALUE)
    private val liveMetricPressureMax = AtomicLong(0L)
    private val liveStylusEventCount = AtomicLong(0L)
    private val liveGlobalEventsReceived = AtomicLong(0L)
    private val usbToMapperLatency = LatencyWindow()
    private val mapperToInjectLatency = LatencyWindow()
    private val usbToInjectLatency = LatencyWindow()
    private val lastStylusMoveLogElapsed = AtomicLong(0L)
    private val lastGlobalStylusMoveLogElapsed = AtomicLong(0L)
    private var liveStylusStrokeId = 0L
    private var liveStylusStrokeActive = false
    private var lastGlobalRawX: Int? = null
    private var lastGlobalRawY: Int? = null
    private val liveStylusCanvasPoints = mutableListOf<StylusCanvasPoint>()
    private var liveCleanupInProgress = false
    private var liveCleanupCompleted = false
    private var liveHidOnlyPoint by mutableStateOf<Pair<Float, Float>?>(null)
    private var hidTransferCounters by mutableStateOf(HidTransferCounters())
    @Volatile private var liveAcquisitionActive = false
    @Volatile private var liveOutputEnabled = false
    @Volatile private var liveIsHidOnly = false
    @Volatile private var liveParsedTestActive = false
    @Volatile private var liveStylusEventTestActive = false
    @Volatile private var liveGlobalStylusActive = false
    @Volatile private var hoverCursorVisualState = HoverCursorVisualState()
    @Volatile private var fullRawStylusLogging = false
    @Volatile private var liveLastRawTimestamp = 0L
    @Volatile private var liveSessionStartedElapsed = 0L
    @Volatile private var liveSessionStartedAtWall = 0L
    @Volatile private var liveSessionStoppedAtWall = 0L
    private var nativeStylusRecentlyActive by mutableStateOf(false)
    private var injectedStylusDispatchCount by mutableStateOf(0L)
    private var lastInjectedStylusDispatch by mutableStateOf("No injected stylus event observed in WacomMapper yet.")
    private var globalInputDeviceSummary by mutableStateOf("No input devices inspected.")
    private var parsedLatestReport by mutableStateOf<com.example.wacommapper.usb.ParsedCtl472Report?>(null)
    private var parserEvidence by mutableStateOf(WacomCtl472EvidenceAnalyzer.analyze(emptyList()))
    private val reportParser = WacomCtl472ReportParser()
    private val ctl472RawParser = com.example.wacommapper.usb.Ctl472RawReportParser()
    private val stylusStateMapper = com.example.wacommapper.mapping.StylusStateMapper()
    @Volatile private var liveWacomUntilElapsed = 0L
    // The user has physically confirmed TEST EXTERNAL APP; do not repeat that stage here.
    @Volatile private var liveTapTestPassed = true
    @Volatile private var liveFollowTestPassed = false
    @Volatile private var liveSnapshot: LiveMappingSnapshot? = null
    private val liveTimerHandler = Handler(Looper.getMainLooper())
    private var liveTimerRunnable: Runnable? = null
    private var nativeStylusClearRunnable: Runnable? = null
    private var experimentSessions by mutableStateOf<List<HidExperimentSession>>(emptyList())
    private var captureReportCount by mutableStateOf(0)
    @Volatile
    private var recordingScenario: HidExperimentScenario? = null
    private val experimentLock = Any()
    private val experimentReportBuffer = mutableListOf<HidRawReport>()
    private val reportCounter = AtomicLong(0L)
    private val lastTransferUiUpdate = AtomicLong(0L)
    private val lastUiReportUpdate = AtomicLong(0L)
    private var previousUiRateCount = 0L
    private var previousUiRateTime = 0L
    private var activeExperimentInterface = 0
    private var activeExperimentEndpoint = 0
    private var activeExperimentPacketSize = 0
    private var pendingExportText: String? = null
    private val liveTraceLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = WacomPreferences(this)
        showHoverCursor = preferences.showHoverCursor
        cursorSize = preferences.cursorSize
        cursorColor = preferences.cursorColor
        savedCursorColors = preferences.savedCursorColors
        pressureSensitivity = preferences.pressureSensitivity
        tipDownThreshold = preferences.tipDownThreshold
        tipUpThreshold = preferences.tipUpThreshold
        mappingOptions = preferences.mappingOptions
        stylusStateMapper.setThresholds(tipDownThreshold, tipUpThreshold)
        HoverCursorOverlay.setCursorSize(this, cursorSize.diameterDp)
        HoverCursorOverlay.setCursorColor(this, cursorColor)
        enableEdgeToEdge()
        displayInfo = AndroidDisplayInfo.read(this)
        refreshLiveSnapshot()
        shizukuBackend = ShizukuBackend(this) { state ->
            runOnUiThread {
                shizukuBackendState = state
                if (liveGlobalStylusActive && !com.example.wacommapper.output.ShizukuConnectionPolicy.canInject(
                        state.status,
                        state.userServiceConnected,
                    )
                ) {
                    Log.e("STYLUS_GLOBAL", "Shizuku backend disconnected/failed during live session; stopping and releasing.")
                    stopLiveWacomTest(LiveStopReason.BACKEND_DISCONNECTED)
                }
            }
        }
        shizukuInputBackend = ShizukuInputManagerBackend(shizukuBackend)
        shizukuBackendState = shizukuBackend.refresh()
        if (shizukuBackendState.status == ShizukuBackendStatus.READY) shizukuBackend.connectUserService()
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeExperimentExport(uri)
        }
        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshHoverOverlayPermission()
        }
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Android still runs the FGS if denied; it surfaces it in Task Manager rather than the notification drawer.
            startProductStylusAfterNotificationPermission()
        }
        refreshHoverOverlayPermission()
        detector = UsbDeviceDetector(this) { updateDevices(detector.scan()) }
        updateDevices(detector.scan())
        bindService(Intent(this, LiveWacomForegroundService::class.java), productServiceConnection, Context.BIND_AUTO_CREATE)
        usbAttachReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateDevices(detector.scan())
                productService?.refreshTablet()
            }
        }
        val usbFilter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            usbAttachReceiver,
            usbFilter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        usbAttachReceiverRegistered = true
        usbSessionManager = WacomUsbSessionManager(
            context = this,
            onConnectionChanged = { state ->
                runOnUiThread {
                    connectionState = state
                    connectionOpen = state.isOpen
                    interfaceClaimed = state.claimedInterfaceId != null
                }
            },
            onReport = ::receiveReport,
            onError = { message ->
                runOnUiThread {
                    captureError = message
                    if (legacyHidCollecting) legacyHidDiagnostic = legacyHidDiagnostic.copy(exceptionMessage = message)
                    if (liveAcquisitionActive) {
                        val reason = if (message.contains("disconnect", ignoreCase = true)) {
                            LiveStopReason.USB_DISCONNECTED
                        } else {
                            LiveStopReason.USB_FATAL_ERROR
                        }
                        stopLiveWacomTest(reason)
                    }
                }
            },
            onFatal = { throwable ->
                liveReaderFatal = throwable
                runOnUiThread {
                    Log.e("LIVE_FATAL", "USB reader failure", throwable)
                    if (legacyHidCollecting) {
                        legacyHidDiagnostic = legacyHidDiagnostic.copy(
                            exceptionClass = throwable::class.java.name,
                            exceptionMessage = throwable.message,
                            exceptionCause = causeChain(throwable),
                            exceptionStackTrace = throwable.stackTraceToString(),
                        )
                    }
                    if (liveAcquisitionActive || liveWacomMetrics.hidOnly) {
                        storeLiveThrowable(throwable, LiveStopReason.USB_FATAL_ERROR)
                    }
                    if (liveAcquisitionActive) {
                        stopLiveWacomTest(LiveStopReason.USB_FATAL_ERROR)
                    }
                }
            },
            onConsumerError = { stage, throwable ->
                Log.e("LIVE_PIPELINE", "Report consumer callback failed at $stage; USB reader continues", throwable)
                recordPipelineFailure(stage, throwable)
            },
            onTransferCounters = { counters ->
                val elapsed = SystemClock.elapsedRealtime()
                if (elapsed - lastTransferUiUpdate.get() >= 100L || counters.attempted <= 1L || counters.usbErrors > 0L || counters.successful > 0L) {
                    lastTransferUiUpdate.set(elapsed)
                    runOnUiThread {
                        hidTransferCounters = counters
                        if (liveAcquisitionActive || liveWacomMetrics.hidOnly) {
                            liveWacomMetrics = liveWacomMetrics.copy(
                                usbTransfersAttempted = counters.attempted,
                                usbTransfersSuccessful = counters.successful,
                                usbBytesTransferred = counters.bytesTransferred,
                                usbTimeouts = counters.timeouts,
                                usbErrors = counters.usbErrors,
                                lastRawAgeMillis = liveLastRawTimestamp.takeIf { it > 0L }
                                    ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) },
                            )
                        }
                    }
                }
            },
            onLiveTrace = { message -> appendLiveTrace(message) },
        )
        setContent {
            WacomMapperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (!showDeveloperTools) {
                            if (showProductSettings) {
                                ProductSettingsScreen(
                                    showCursor = showHoverCursor,
                                    cursorSize = cursorSize,
                                    cursorColor = cursorColor,
                                    savedCursorColors = savedCursorColors,
                                    sensitivity = pressureSensitivity,
                                    tabletRotation = mappingOptions.tabletRotation,
                                    advancedDown = tipDownThreshold,
                                    advancedUp = tipUpThreshold,
                                    overlayPermissionGranted = overlayPermissionGranted,
                                    onRequestOverlayPermission = ::requestOverlayPermission,
                                    onCursor = ::updateHoverCursorPreference,
                                    onCursorSize = { size -> cursorSize = size; preferences.cursorSize = size; HoverCursorOverlay.setCursorSize(this@MainActivity, size.diameterDp) },
                                    onCursorColor = { color -> cursorColor = color; preferences.cursorColor = color; HoverCursorOverlay.setCursorColor(this@MainActivity, color) },
                                    onSaveCursorColor = { index ->
                                        preferences.saveCursorColor(index, cursorColor)
                                        savedCursorColors = preferences.savedCursorColors
                                    },
                                    onSensitivity = { sensitivity ->
                                        pressureSensitivity = sensitivity
                                        preferences.pressureSensitivity = sensitivity
                                        updateTipThresholds(sensitivity.down, sensitivity.up)
                                    },
                                    onRotation = { rotation ->
                                        mappingOptions = mappingOptions.copy(tabletRotation = rotation)
                                        preferences.tabletRotation = rotation
                                        refreshLiveSnapshot()
                                    },
                                    onAdvancedThresholds = { down, up ->
                                        updateTipThresholds(down, up)
                                        preferences.tipDownThreshold = down
                                        preferences.tipUpThreshold = up
                                    },
                                    onBack = { showProductSettings = false },
                                )
                            } else {
                                val detected = devices.firstOrNull { it.vendorId == 0x056A && it.productId == 0x037A }
                                val displayedState = productSessionState.copy(
                                    tablet = when {
                                        detected == null -> ProductTabletStatus.DISCONNECTED
                                        detected.permission != UsbPermissionStatus.GRANTED -> ProductTabletStatus.PERMISSION_REQUIRED
                                        else -> ProductTabletStatus.CONNECTED
                                    },
                                    shizuku = shizukuBackendState.status,
                                    stylus = when {
                                        liveProductStylusActive() -> ProductStylusStatus.ACTIVE
                                        productSessionState.stylus == ProductStylusStatus.ERROR -> ProductStylusStatus.ERROR
                                        else -> productSessionState.stylus
                                    },
                                )
                                ProductHomeScreen(
                                    state = displayedState,
                                    usbPermissionGranted = detected?.permission == UsbPermissionStatus.GRANTED,
                                    onGrantUsb = { detected?.let { detector.requestPermission(it.deviceName) } },
                                    onStart = ::startProductStylus,
                                    onStop = { productService?.stopGlobal() ?: LiveWacomForegroundService.stop(this@MainActivity) },
                                    onSettings = { showProductSettings = true },
                                    onDiagnostics = { showDeveloperTools = true },
                                )
                            }
                        } else {
                        androidx.compose.foundation.layout.Row(Modifier.horizontalScroll(rememberScrollState())) {
                            androidx.compose.material3.OutlinedButton(onClick = { showExperimentWizard = false; showParsedDiagnostics = false; showMappingDiagnostics = false; showOutputDiagnostics = false }) { androidx.compose.material3.Text("USB / RAW") }
                            androidx.compose.material3.OutlinedButton(onClick = { showExperimentWizard = true; showParsedDiagnostics = false; showMappingDiagnostics = false; showOutputDiagnostics = false }) { androidx.compose.material3.Text("Experiment Wizard") }
                            androidx.compose.material3.OutlinedButton(onClick = { showExperimentWizard = false; showParsedDiagnostics = true; showMappingDiagnostics = false; showOutputDiagnostics = false }) { androidx.compose.material3.Text("Parsed Diagnostics") }
                            androidx.compose.material3.OutlinedButton(onClick = { showExperimentWizard = false; showParsedDiagnostics = false; showMappingDiagnostics = true; showOutputDiagnostics = false }) { androidx.compose.material3.Text("Mapping Diagnostics") }
                            androidx.compose.material3.OutlinedButton(onClick = { showExperimentWizard = false; showParsedDiagnostics = false; showMappingDiagnostics = false; showOutputDiagnostics = true }) { androidx.compose.material3.Text("Output Backend") }
                            androidx.compose.material3.OutlinedButton(onClick = { showDeveloperTools = false }) { androidx.compose.material3.Text("Inicio") }
                        }
                        if (showExperimentWizard) {
                            HidExperimentWizardScreen(
                                device = captureDevice(),
                                selectedInterface = selectedInterface,
                                connection = connectionState,
                                sessions = experimentSessions,
                                captureReportCount = captureReportCount,
                                errorMessage = captureError,
                                onSelectInterface = { selectedInterface = it },
                                onClaimInterface = ::claimExperimentInterface,
                                onReleaseInterface = { usbSessionManager.stop() },
                                onPrepareCapture = ::prepareExperimentCapture,
                                onBeginCapture = ::beginExperimentCapture,
                                onFinishCapture = ::finishExperimentCapture,
                                onExport = ::requestExperimentExport,
                            )
                        } else if (showMappingDiagnostics) {
                            displayInfo?.let { info ->
                                MappingDiagnosticsScreen(
                                    displayInfo = info,
                                    parsed = parsedLatestReport,
                                    parserEvidence = parserEvidence,
                                    mappingOptions = mappingOptions,
                                    onMappingOptionsChanged = {
                                        mappingOptions = it
                                        preferences.mappingMode = it.mode
                                        preferences.tabletRotation = it.tabletRotation
                                        refreshLiveSnapshot()
                                    },
                                )
                            }
                        } else if (showOutputDiagnostics) {
                            displayInfo?.let { info ->
                                OutputBackendDiagnosticsScreen(
                                    displayInfo = info,
                                    parsed = parsedLatestReport,
                                    reportTimestampMillis = lastReport?.timestamp,
                                    parserEvidence = parserEvidence,
                                    mappingOptions = mappingOptions,
                                    shizukuState = shizukuBackendState,
                                    overlayPermissionGranted = overlayPermissionGranted,
                                    showHoverCursor = showHoverCursor,
                                    onShowHoverCursorChanged = ::updateHoverCursorPreference,
                                    onRequestOverlayPermission = ::requestOverlayPermission,
                                    captureInterfaceId = connectionState.claimedInterfaceId,
                                    captureEndpoint = connectionState.activeEndpointAddress,
                                    captureConnectionOpen = connectionState.isOpen,
                                    captureTotalReports = totalReports,
                                    captureReportsPerSecond = reportsPerSecond,
                                    liveWacomSecondsRemaining = liveWacomSecondsRemaining,
                                    liveWacomMetrics = liveWacomMetrics,
                                    liveHidOnlyPoint = liveHidOnlyPoint,
                                    liveStylusEventFrame = liveStylusEventFrame,
                                    tipDownThreshold = tipDownThreshold,
                                    tipUpThreshold = tipUpThreshold,
                                    onTipThresholdsChanged = ::updateTipThresholds,
                                    openUsbOnlyDiagnostic = openUsbOnlyDiagnostic,
                                    legacyHidDiagnostic = legacyHidDiagnostic,
                                    nativeStylusRecentlyActive = nativeStylusRecentlyActive,
                                    inputDeviceSummary = globalInputDeviceSummary,
                                    injectedStylusDispatchCount = injectedStylusDispatchCount,
                                    lastInjectedStylusDispatch = lastInjectedStylusDispatch,
                                    externalTapRecord = externalTapDiagnostic,
                                    externalTapCountdown = externalTapCountdown,
                                    externalTapRunning = externalTapRunning,
                                    onRefreshShizuku = { shizukuBackendState = shizukuBackend.refresh() },
                                    onRequestShizukuPermission = shizukuBackend::requestPermission,
                                    onConnectShizuku = shizukuBackend::connectUserService,
                                    onInjectProbe = ::injectShizukuProbe,
                                    onExternalTapClick = ::startExternalTapDiagnostic,
                                    onEmergencyStop = shizukuBackend::emergencyStop,
                                    onFollowTestCompleted = { passed -> liveFollowTestPassed = passed },
                                    onPrepareLiveWacom = ::prepareLiveWacomTest,
                                    onStartLiveWacom = { globalValidation ->
                                        startLiveSession(hidOnly = false, globalValidation = globalValidation)
                                    },
                                    onStartLiveHidOnly = { startLiveSession(hidOnly = true) },
                                    onLiveButtonPressed = ::beginLiveTrace,
                                    rawLoggingEnabled = fullRawStylusLogging,
                                    onRawLoggingChanged = { fullRawStylusLogging = it },
                                    onTestOpenUsbOnly = ::testOpenUsbOnly,
                                    onTestLegacyHidReader = ::testLegacyHidReader,
                                    onStopLiveWacom = ::stopLiveWacomTest,
                                    onAwaitLiveCompletion = { liveSessionCompletion.await() },
                                )
                            }
                        } else if (showParsedDiagnostics) {
                            ParsedStylusDiagnosticsScreen(
                                latestReport = lastReport,
                                selectedInterface = selectedInterface,
                                connectionOpen = connectionOpen,
                                reportsPerSecond = reportsPerSecond,
                            )
                        } else {
                            UsbDiagnosticsScreen(
                                modifier = Modifier.weight(1f),
                                devices = devices,
                                onScan = { updateDevices(detector.scan()) },
                                onRequestPermission = detector::requestPermission,
                                onUseForHid = { selectedDeviceName = it },
                                hidContent = {
                                    HidRawDiagnosticsScreen(
                                        device = captureDevice(),
                                        selectedInterface = selectedInterface,
                                        isOpen = connectionOpen,
                                        interfaceClaimed = interfaceClaimed,
                                        totalReports = totalReports,
                                        reportsPerSecond = reportsPerSecond,
                                        lastPacketSize = lastPacketSize,
                                        lastReport = lastReport,
                                        recentReports = recentReports,
                                        errorMessage = captureError,
                                        onSelectInterface = { selectedInterface = it },
                                        onStart = { startCapture() },
                                        onStop = { usbSessionManager.stop() },
                                        onClear = { clearCapture() },
                                        onExport = { exportCapture() },
                                    )
                                },
                            )
                        }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        externalTapJob?.cancel()
        if (liveGlobalStylusActive && ::shizukuInputBackend.isInitialized) {
            runCatching { shizukuInputBackend.releaseAllInput() }
            liveGlobalStylusActive = false
        }
        if (liveAcquisitionActive || liveGlobalStylusActive) {
            stopLiveWacomTest(LiveStopReason.LIFECYCLE_CANCELLED)
        }
        if (::shizukuBackend.isInitialized) shizukuBackend.close()
        usbSessionManager.close()
        detector.close()
        if (usbAttachReceiverRegistered) runCatching { unregisterReceiver(usbAttachReceiver) }
        runCatching { unbindService(productServiceConnection) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        displayInfo = AndroidDisplayInfo.read(this)
        globalInputDeviceSummary = describeRelevantInputDevices()
        refreshLiveSnapshot()
    }

    override fun onResume() {
        super.onResume()
        updateDevices(detector.scan())
        productService?.refreshTablet()
        displayInfo = AndroidDisplayInfo.read(this)
        globalInputDeviceSummary = describeRelevantInputDevices()
        refreshLiveSnapshot()
        refreshHoverOverlayPermission()
        if (::shizukuBackend.isInitialized) shizukuBackendState = shizukuBackend.refresh()
        if (shizukuBackendState.status == ShizukuBackendStatus.READY) shizukuBackend.connectUserService()
    }

    private fun liveProductStylusActive(): Boolean = productSessionState.stylus == ProductStylusStatus.ACTIVE

    private fun startProductStylus() {
        val usbManager = getSystemService(UsbManager::class.java)
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x056A && it.productId == 0x037A }
        if (device == null) {
            productSessionState = productSessionState.copy(stylus = ProductStylusStatus.ERROR, message = "Conecta la Wacom CTL-472.")
            return
        }
        if (!usbManager.hasPermission(device)) {
            detector.requestPermission(device.deviceName)
            productSessionState = productSessionState.copy(
                tablet = ProductTabletStatus.PERMISSION_REQUIRED,
                stylus = ProductStylusStatus.ERROR,
                message = "Concede permiso USB a la Wacom y vuelve a iniciar.",
            )
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startProductStylusAfterNotificationPermission()
    }

    private fun startProductStylusAfterNotificationPermission() {
        productSessionState = productSessionState.copy(stylus = ProductStylusStatus.STARTING, message = null)
        val device = getSystemService(UsbManager::class.java).deviceList.values.firstOrNull {
            it.vendorId == 0x056A && it.productId == 0x037A
        }
        if (device == null) {
            productSessionState = productSessionState.copy(
                tablet = ProductTabletStatus.DISCONNECTED,
                stylus = ProductStylusStatus.ERROR,
                message = "Conecta la Wacom CTL-472.",
            )
            return
        }
        if (!getSystemService(UsbManager::class.java).hasPermission(device)) {
            detector.requestPermission(device.deviceName)
            productSessionState = productSessionState.copy(
                tablet = ProductTabletStatus.PERMISSION_REQUIRED,
                stylus = ProductStylusStatus.ERROR,
                message = "Concede permiso USB a la Wacom y vuelve a iniciar.",
            )
            return
        }
        if (shizukuBackendState.status != ShizukuBackendStatus.READY || !shizukuBackendState.userServiceConnected) {
            productSessionState = productSessionState.copy(
                stylus = ProductStylusStatus.ERROR,
                message = when (shizukuBackendState.status) {
                    ShizukuBackendStatus.NOT_INSTALLED -> "Instala Shizuku para iniciar el stylus global."
                    ShizukuBackendStatus.NOT_RUNNING -> "Inicia Shizuku y vuelve a intentarlo."
                    ShizukuBackendStatus.PERMISSION_REQUIRED -> "Concede permiso a WacomMapper en Shizuku."
                    else -> "Conectando con Shizuku. Espera unos segundos y vuelve a iniciar."
                },
            )
            shizukuBackend.connectUserService()
            return
        }
        lifecycleScope.launch {
            try {
                usbSessionManager.stopAndWait()
                HoverCursorOverlay.setCursorSize(this@MainActivity, cursorSize.diameterDp)
                LiveWacomForegroundService.start(this@MainActivity)
            } catch (throwable: Throwable) {
                Log.e("WACOM_SERVICE", "Could not request global session", throwable)
                productSessionState = productSessionState.copy(
                    stylus = ProductStylusStatus.ERROR,
                    message = "No se pudo iniciar el servicio: ${throwable.message ?: throwable.javaClass.simpleName}",
                )
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeNativeStylus(event)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        observeNativeStylus(event)
        return super.dispatchGenericMotionEvent(event)
    }

    private fun observeNativeStylus(event: MotionEvent) {
        val stylusTool = event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val stylusSource = event.isFromSource(InputDevice.SOURCE_STYLUS)
        if (!stylusTool && !stylusSource) return
        // Injected probes use deviceId=0; track their dispatch to this app separately from native devices.
        if (event.deviceId <= 0) {
            if (liveGlobalStylusActive) {
                injectedStylusDispatchCount++
                lastInjectedStylusDispatch = "action=${event.actionMasked} source=0x${event.source.toString(16)} tool=${event.getToolType(0)} x=${event.x} y=${event.y} pressure=${event.pressure} buttons=0x${event.buttonState.toString(16)}"
            }
            return
        }
        nativeStylusRecentlyActive = true
        if (liveWacomUntilElapsed > SystemClock.elapsedRealtime()) {
            liveWacomMetrics = liveWacomMetrics.copy(possibleDoubleInput = true)
            stopLiveWacomTest()
            Thread {
                val info = displayInfo
                shizukuBackend.emergencyStop((info?.windowWidth ?: 0) / 2f, (info?.windowHeight ?: 0) / 2f)
            }.start()
            Log.e("SHIZUKU_STATUS", "Possible concurrent native stylus input observed during LIVE; injection stopped and emergency release requested")
        }
        nativeStylusClearRunnable?.let(liveTimerHandler::removeCallbacks)
        nativeStylusClearRunnable = Runnable { nativeStylusRecentlyActive = false }
        liveTimerHandler.postDelayed(nativeStylusClearRunnable!!, NATIVE_EVENT_RECENCY_MS)
    }

    private fun describeRelevantInputDevices(): String {
        val relevant: List<String> = InputDevice.getDeviceIds().asSequence().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        val stylus = device.supportsSource(InputDevice.SOURCE_STYLUS)
        val touchscreen = device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
        if (!stylus && !touchscreen && !device.name.contains("wacom", ignoreCase = true) &&
            !device.name.contains("ctl", ignoreCase = true)
        ) return@mapNotNull null
        "id=$id name=${device.name} sources=0x${device.sources.toString(16)} stylus=$stylus touchscreen=$touchscreen"
        }.toList()
        return relevant.ifEmpty { listOf("No Wacom/stylus/touchscreen device exposed in InputDevice inventory.") }
            .joinToString("\n")
    }

    private fun updateDevices(scannedDevices: List<UsbDeviceInfo>) {
        devices = scannedDevices
        val selectedStillExists = scannedDevices.any {
            it.deviceName == selectedDeviceName && it.permission == UsbPermissionStatus.GRANTED
        }
        if (!selectedStillExists) {
            selectedDeviceName = scannedDevices.firstOrNull {
                it.permission == UsbPermissionStatus.GRANTED
            }?.deviceName
        }
    }

    private fun captureDevice(): UsbDeviceInfo? = devices.firstOrNull {
        it.deviceName == selectedDeviceName && it.permission == UsbPermissionStatus.GRANTED
    }

    private fun ensureDiagnosticsUsbAvailable(): Boolean {
        val active = productSessionState.stylus == ProductStylusStatus.ACTIVE ||
            productSessionState.stylus == ProductStylusStatus.STARTING ||
            liveProductStylusActive()
        if (active) captureError = "Detén primero el stylus global para usar diagnósticos USB."
        return !active
    }

    private fun startCapture() {
        if (!ensureDiagnosticsUsbAvailable()) return
        val device = captureDevice() ?: return
        captureError = null
        usbSessionManager.start(device.deviceName, selectedInterface)
    }

    private fun receiveReport(report: HidRawReport) {
        val legacyCount = if (legacyHidCollecting) legacyHidReportCount.incrementAndGet() else 0L
        val liveCount = if (liveAcquisitionActive) liveMetricHid.incrementAndGet() else 0L
        if (legacyHidCollecting) {
            runOnUiThread {
                legacyHidDiagnostic = legacyHidDiagnostic.copy(reportsReceived = legacyCount, lastRaw = report.hex)
            }
        }
        if (liveAcquisitionActive) {
            val traceCount = livePipelineTraceCounter.incrementAndGet()
            if (traceCount == 1L || traceCount % 100L == 0L) {
                Log.i("LIVE_PIPELINE", "raw report received #$liveCount len=${report.bytes.size} raw=${report.hex}")
                Log.i("LIVE_PIPELINE", "raw counter updated #$liveCount")
            }
        }
        synchronized(experimentLock) {
            if (recordingScenario != null) experimentReportBuffer.add(report)
        }
        val activeScenario = recordingScenario
        if (activeScenario != null) {
            logControlledStylusState(report, activeScenario.id)
        }
        reportCounter.incrementAndGet()
        val elapsed = SystemClock.elapsedRealtime()
        if (liveAcquisitionActive) {
            try {
                forwardLiveWacomReport(report, SystemClock.elapsedRealtimeNanos())
            } catch (throwable: Throwable) {
                recordPipelineFailure("live consumer", throwable)
            }
        }
        val lastUpdate = lastUiReportUpdate.get()
        if (elapsed - lastUpdate < 100L || !lastUiReportUpdate.compareAndSet(lastUpdate, elapsed)) return
        runOnUiThread {
            val count = reportCounter.get()
            reportsPerSecond = if (previousUiRateTime == 0L) 0 else {
                (((count - previousUiRateCount) * 1_000L) / (elapsed - previousUiRateTime).coerceAtLeast(1L)).toInt()
            }
            previousUiRateCount = count
            previousUiRateTime = elapsed
            totalReports = count
            lastPacketSize = report.bytes.size
            lastReport = report
            try {
                parsedLatestReport = reportParser.parse(
                    report,
                    xOrder = CandidateByteOrder.LITTLE_ENDIAN,
                    yOrder = CandidateByteOrder.LITTLE_ENDIAN,
                    pressureOrder = CandidateByteOrder.LITTLE_ENDIAN,
                )
            } catch (throwable: Throwable) {
                recordPipelineFailure("parsed diagnostics", throwable)
            }
            recentReports = (recentReports + report).takeLast(MAX_RECENT_REPORTS)
            captureReportCount = synchronized(experimentLock) { experimentReportBuffer.size }
        }
    }

    private fun logControlledStylusState(report: HidRawReport, scenarioId: String) {
        if (fullRawStylusLogging) {
            Log.v(
                "STYLUS_RAW",
                "scenario=$scenarioId timestamp=${report.timestamp} interface=${report.interfaceId} " +
                    "endpoint=0x${report.endpointAddress.toString(16)} len=${report.bytes.size} raw=${report.hex}",
            )
        }
        if (report.bytes.size != com.example.wacommapper.usb.Ctl472Report.REPORT_LENGTH) return
        val now = SystemClock.elapsedRealtime()
        val previousLog = lastStylusStateLogElapsed.get()
        if (previousLog != 0L && now - previousLog < STYLUS_STATE_LOG_INTERVAL_MS) return
        if (!lastStylusStateLogElapsed.compareAndSet(previousLog, now)) return

        val parsed = ctl472RawParser.parse(report.bytes)
        Log.i(
            "STYLUS_STATE",
            "scenario=$scenarioId type=${parsed.type} x=${parsed.x} y=${parsed.y} pressure=${parsed.pressure} " +
                "nearProximity=${parsed.nearProximity} sideButton1=${parsed.sideButton1} sideButton2=${parsed.sideButton2} " +
                "eraser=${parsed.eraser} hoverDistance=${parsed.hoverDistance} status=0x${parsed.statusByte?.toString(16)?.uppercase()} " +
                "statusBit0Raw=${parsed.statusBit0} contact=UNKNOWN raw=[${report.hex}]",
        )
    }

    private fun clearCapture() {
        totalReports = 0L
        reportsPerSecond = 0
        lastPacketSize = 0
        lastReport = null
        recentReports = emptyList()
        reportCounter.set(0L)
        lastUiReportUpdate.set(0L)
        previousUiRateCount = 0L
        previousUiRateTime = 0L
        captureError = null
    }

    private fun exportCapture() {
        val device = captureDevice() ?: return
        val endpoint = recentReports.firstOrNull()?.endpointAddress ?: 0
        val text = buildString {
            appendLine("WacomMapper HID Capture")
            appendLine("Device: ${device.productName ?: device.deviceName}")
            appendLine("VID: 0x${hex(device.vendorId)}")
            appendLine("PID: 0x${hex(device.productId)}")
            appendLine("Interface: $selectedInterface")
            appendLine("Endpoint: 0x${hex(endpoint)}")
            appendLine()
            appendLine("timestamp | length | raw")
            recentReports.forEach { report ->
                appendLine("${report.timestamp} | ${report.bytes.size} | ${report.hex}")
            }
        }
        startActivity(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "WacomMapper HID capture")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }.let { android.content.Intent.createChooser(it, "Export HID capture") })
    }

    private fun claimExperimentInterface(interfaceId: Int): Boolean {
        if (!ensureDiagnosticsUsbAvailable()) return false
        val device = captureDevice() ?: run {
            captureError = "Concede permiso USB y selecciona la CTL-472 primero."
            return false
        }
        if (connectionState.isOpen) {
            return connectionState.claimedInterfaceId == interfaceId
        }
        captureError = null
        return usbSessionManager.start(device.deviceName, interfaceId)
    }

    private fun beginExperimentCapture(scenario: HidExperimentScenario, startedAt: Long) {
        synchronized(experimentLock) {
            recordingScenario = scenario
            activeExperimentInterface = connectionState.claimedInterfaceId ?: selectedInterface
            activeExperimentEndpoint = connectionState.activeEndpointAddress ?: 0
            activeExperimentPacketSize = connectionState.packetSize ?: 0
        }
        captureReportCount = 0
        captureError = null
    }

    private fun prepareExperimentCapture(scenario: HidExperimentScenario): Boolean {
        if (!ensureDiagnosticsUsbAvailable()) return false
        val validConnection = connectionState.isOpen &&
            connectionState.claimedInterfaceId == selectedInterface &&
            connectionState.activeEndpointAddress == captureDevice()?.interfaces
                ?.firstOrNull { it.id == selectedInterface }
                ?.endpoints
                ?.firstOrNull { it.directionName == "IN" && it.typeName == "INTERRUPT" }
                ?.address &&
            connectionState.packetSize == captureDevice()?.interfaces
                ?.firstOrNull { it.id == selectedInterface }
                ?.endpoints
                ?.firstOrNull { it.directionName == "IN" && it.typeName == "INTERRUPT" }
                ?.maxPacketSize
        if (!validConnection) {
            captureError = "Interfaz/endpoint/packet size no coinciden para ${scenario.id}."
            return false
        }
        synchronized(experimentLock) {
            recordingScenario = null
            experimentReportBuffer.clear()
        }
        captureReportCount = 0
        captureError = null
        return true
    }

    private fun finishExperimentCapture(
        scenario: HidExperimentScenario,
        startedAt: Long,
        endedAt: Long,
        phaseMarkers: List<ExperimentPhaseMarker>,
    ): HidExperimentSession? {
        val reportSnapshot = synchronized(experimentLock) {
            recordingScenario = null
            experimentReportBuffer.toList().also { experimentReportBuffer.clear() }
        }
        val markersWithReportIndices = phaseMarkers.map { marker ->
            marker.copy(
                nearestReportIndex = reportSnapshot.indices.minByOrNull { index ->
                    kotlin.math.abs(reportSnapshot[index].timestamp - marker.wallTimestamp)
                },
            )
        }
        val session = HidExperimentSession(
            scenario = scenario,
            interfaceId = activeExperimentInterface,
            endpointAddress = activeExperimentEndpoint,
            packetSize = activeExperimentPacketSize,
            startedAt = startedAt,
            endedAt = endedAt,
            reports = reportSnapshot,
            phaseMarkers = markersWithReportIndices,
        )
        val representative = reportSnapshot.filter {
            it.bytes.size == WacomCtl472ReportParser.REQUIRED_REPORT_LENGTH
        }
        listOf(0, representative.size / 2, representative.lastIndex)
            .filter { it in representative.indices }
            .distinct()
            .forEach { index ->
                val report = representative[index]
                val parsed = if ((report.bytes[0].toInt() and 0xFF) == 0x02) reportParser.parse(report) else null
                val position = when (index) {
                    0 -> "first"
                    representative.lastIndex -> "last"
                    else -> "middle"
                }
                Log.i(
                    "STYLUS_STATE",
                    "scenario=${scenario.id} sample=$position raw=[${report.hex}] " +
                        (parsed?.let {
                            "xCandidate=${it.candidateX} yCandidate=${it.candidateY} " +
                                "pressureCandidate=${it.candidatePressure} flags=" +
                                it.flagBytes.toSortedMap().entries.joinToString(" ") { (offset, value) ->
                                    "b$offset=0x${value.toString(16).padStart(2, '0').uppercase()}/${value.toString(2).padStart(8, '0')}"
                                }
                        } ?: "parser=not-applicable (special/unsupported report)"),
                )
            }
        experimentSessions = (experimentSessions.filterNot {
            it.interfaceId == session.interfaceId && it.scenario.id == scenario.id
        } + session)
        parserEvidence = WacomCtl472EvidenceAnalyzer.analyze(experimentSessions)
        refreshLiveSnapshot()
        parsedLatestReport = lastReport?.let { report ->
            reportParser.parse(
                report,
                parserEvidence.xByteOrder.selected,
                parserEvidence.yByteOrder.selected,
                parserEvidence.pressureByteOrder.selected,
            )
        }
        captureReportCount = reportSnapshot.size
        if (captureDevice() == null) captureError = "USB desconectado; se guardó la captura parcial."
        return session
    }

    private fun requestExperimentExport() {
        pendingExportText = buildExperimentExport()
        exportLauncher.launch("wacom_experiment.txt")
    }

    private fun buildExperimentExport(): String {
        val device = captureDevice()
        return buildString {
            appendLine("WacomMapper HID Experiment")
            appendLine("Device: ${device?.productName ?: "CTL-472"}")
            appendLine("Manufacturer: ${device?.manufacturerName ?: "Wacom Co.,Ltd."}")
            appendLine("VID: 0x${hex(device?.vendorId ?: 0x056A)}")
            appendLine("PID: 0x${hex(device?.productId ?: 0x037A)}")
            appendLine("Exported at: ${System.currentTimeMillis()}")
            appendLine()
            experimentSessions.sortedWith(compareBy<HidExperimentSession> { it.interfaceId }.thenBy { it.startedAt }).forEach { session ->
                appendLine("==================================================")
                appendLine("Experiment: ${session.scenario.id}")
                appendLine("Instructions: ${session.scenario.instructions}")
                appendLine("Duration suggested: ${session.scenario.durationSeconds}s")
                appendLine("Interface: ${session.interfaceId}")
                appendLine("Endpoint: 0x${hex(session.endpointAddress)}")
                appendLine("Packet size: ${session.packetSize}")
                appendLine("Start timestamp: ${session.startedAt}")
                appendLine("End timestamp: ${session.endedAt}")
                session.phaseMarkers.forEach {
                    appendLine("Phase marker: monotonic=${it.monotonicTimestamp}ms | wall=${it.wallTimestamp} | state=${it.phase} | nearestReportIndex=${it.nearestReportIndex}")
                }
                appendLine("Reports/sec: ${HidExperimentAnalyzer.analyze(session).reportsPerSecond}")
                appendLine("timestamp | length | raw")
                session.reports.forEach { report -> appendLine("${report.timestamp} | ${report.bytes.size} | ${report.hex}") }
                appendLine("Byte statistics: index | min | max | range | distinct | changes | changes/sec")
                HidExperimentAnalyzer.analyze(session).bytes.forEach { stat ->
                    appendLine("${stat.index} | ${stat.min} | ${stat.max} | ${stat.range} | ${stat.distinctValues} | ${stat.changes} | ${stat.changeFrequencyHz}")
                }
                appendLine("16-bit candidates: name | offsets | LE range/distinct/changes | BE range/distinct/changes")
                HidExperimentAnalyzer.analyze(session).candidates16.forEach { candidate ->
                    appendLine("${candidate.name} | ${candidate.byteOffsets} | ${candidate.littleEndianRange}/${candidate.littleEndianDistinct}/${candidate.littleEndianChanges} | ${candidate.bigEndianRange}/${candidate.bigEndianDistinct}/${candidate.bigEndianChanges}")
                }
                appendLine()
            }
            appendLine("ROUND 2 CONTROLLED BIT ANALYSIS (statistical candidates only; parser not modified)")
            appendLine("Only Interface 0 / EP 0x81 / 10-byte report ID 0x02 samples are analyzed.")
            val roundTwo = RoundTwoExperimentAnalyzer.analyze(experimentSessions)
            roundTwo.scenarios.forEach { scenario ->
                appendLine("Scenario ${scenario.scenarioId}: n=${scenario.reportCount}; byte1=${scenario.byte1ValueFrequencies}; byte8=${scenario.byte8ValueFrequencies}")
                appendLine("Pressure min/mean/max=${scenario.pressureMin}/${scenario.pressureMean}/${scenario.pressureMax}; X mean/SD=${scenario.xMean}/${scenario.xStandardDeviation}; Y mean/SD=${scenario.yMean}/${scenario.yStandardDeviation}")
                scenario.representativeRaw.forEachIndexed { index, raw -> appendLine("RAW ${listOf("first", "middle", "last").getOrElse(index) { "sample" }}: $raw") }
                scenario.markedTransitions.forEach { appendLine("Marked toggle phase: $it") }
            }
            appendLine("Per-bit P(bit=1 | scenario), percent")
            roundTwo.bits.forEach { bit ->
                appendLine("byte${bit.byteIndex}.bit${bit.bit}: ${bit.probabilitiesByScenario.entries.joinToString { (id, probability) -> "$id=${probability?.let { "%.2f".format(java.util.Locale.US, it) } ?: "n/a"}%" }} | ${bit.confidence} | ${bit.interpretation}")
            }
            roundTwo.comparisons.forEach { appendLine("Comparison: $it") }
            appendLine()
            appendLine("ROUND 3 MARKED-SEGMENT ANALYSIS (no parser semantics changed)")
            val roundThree = RoundThreeExperimentAnalyzer.analyze(experimentSessions)
            appendLine("TEST | SEGMENT | PHYSICAL_STATE | REPORTS | MEAN_X | MEAN_Y | STD_X | STD_Y | PRESSURE_MEAN | B1.B0% | B1.B1% | B8.B0..B8.B7% | REPORT_INDEX_RANGE")
            roundThree.segments.forEach { segment ->
                appendLine("${segment.test} | ${segment.segmentIndex} | ${segment.physicalState} | ${segment.reportCount} | ${segment.meanX} | ${segment.meanY} | ${segment.stdX} | ${segment.stdY} | ${segment.pressureMean} | ${segment.bitProbabilitiesPercent["b1.bit0"]} | ${segment.bitProbabilitiesPercent["b1.bit1"]} | ${(0..7).joinToString(",") { segment.bitProbabilitiesPercent["b8.bit$it"].toString() }} | ${segment.startReportIndex}-${segment.endReportIndex} | P(b1.bit0=1 | pressure)=${segment.pressureConditionalB1Bit0Percent}")
            }
            appendLine("SIDE_BUTTON: ${roundThree.sideButtonStatus}; bit=${roundThree.sideButtonBit}")
            appendLine("TIP_CONTACT: ${roundThree.tipStatus}; bit=${roundThree.tipBit}")
            appendLine("POSITION_CONTROL: ${roundThree.positionControlStatus}")
            roundThree.conclusions.forEach { appendLine("Conclusion: $it") }
            appendLine()
            appendLine("COMPARATIVE SUMMARY (all findings are possible candidates, not protocol conclusions)")
            val parserEvidence = WacomCtl472EvidenceAnalyzer.analyze(experimentSessions)
            appendLine("Parser hypothesis status: X=${parserEvidence.x.status}; Y=${parserEvidence.y.status}; pressure=${parserEvidence.pressure.status}; tip=${parserEvidence.tip.status}; side button=${parserEvidence.sideButton.status}")
            appendLine("Observed edge bounds from EDGES_X/Y: minX=${parserEvidence.edgeBounds.minX}; maxX=${parserEvidence.edgeBounds.maxX}; minY=${parserEvidence.edgeBounds.minY}; maxY=${parserEvidence.edgeBounds.maxY}")
            appendLine("Observed pressure range: min=${parserEvidence.pressureBounds.min}; max=${parserEvidence.pressureBounds.max}; hover near zero=${parserEvidence.hoverPressureNearZero}")
            appendLine("Inferred byte orders from lower total variation: X=${parserEvidence.xByteOrder.selectedLabel}; Y=${parserEvidence.yByteOrder.selectedLabel}; pressure=${parserEvidence.pressureByteOrder.selectedLabel}")
            appendLine("Flag bits with possible proximity changes: ${parserEvidence.possibleProximityBits.joinToString()}")
            experimentSessions.groupBy { it.interfaceId }.forEach { (interfaceId, sessions) ->
                appendLine("Interface $interfaceId")
                val analyses = sessions.associateBy({ it.scenario.id }, HidExperimentAnalyzer::analyze)
                appendLine("Movement-related variation vs hover baseline (not a correlation coefficient): ${HidExperimentAnalyzer.movementVariationEvidence(sessions)}")
                sessions.forEach { session ->
                    val analysis = analyses.getValue(session.scenario.id)
                    appendLine("${session.scenario.id}: ${analysis.reportsPerSecond} reports/sec; ${analysis.varyingByteCount} varying bytes; approximate entropy ${analysis.approximateEntropyBits} bits/byte")
                }
                val moveX = analyses["MOVE_X_ONLY"]
                val moveY = analyses["MOVE_Y_ONLY"]
                val pressure = analyses["PRESSURE_CENTER"]
                val pairedBytes = listOfNotNull(moveX, moveY).minOfOrNull { it.bytes.size } ?: 0
                val possibleXByte = if (moveX != null && moveY != null) (0 until pairedBytes)
                    .maxByOrNull { index -> moveX.bytes[index].range - moveY.bytes[index].range }
                    ?.takeIf { moveX.bytes[it].range > moveY.bytes[it].range } else null
                val possibleYByte = if (moveX != null && moveY != null) (0 until pairedBytes)
                    .maxByOrNull { index -> moveY.bytes[index].range - moveX.bytes[index].range }
                    ?.takeIf { moveY.bytes[it].range > moveX.bytes[it].range } else null
                val xPairMap = moveX?.candidates16?.associateBy { it.name }.orEmpty()
                val yPairMap = moveY?.candidates16?.associateBy { it.name }.orEmpty()
                val possibleXPair = if (moveX != null && moveY != null) moveX.candidates16
                    .maxByOrNull { it.littleEndianRange - (yPairMap[it.name]?.littleEndianRange ?: 0L) }
                    ?.takeIf { it.littleEndianRange > (yPairMap[it.name]?.littleEndianRange ?: 0L) } else null
                val possibleYPair = if (moveX != null && moveY != null) moveY.candidates16
                    .maxByOrNull { it.littleEndianRange - (xPairMap[it.name]?.littleEndianRange ?: 0L) }
                    ?.takeIf { it.littleEndianRange > (xPairMap[it.name]?.littleEndianRange ?: 0L) } else null
                val movementCandidates = listOfNotNull(moveX, moveY).flatMap { it.candidates16 }.groupBy { it.name }
                val possiblePressure = if (moveX != null && moveY != null && pressure != null) {
                    pressure.candidates16.filter { candidate ->
                        candidate.littleEndianRange > (movementCandidates[candidate.name]?.maxOfOrNull { it.littleEndianRange } ?: 0L)
                    }.maxByOrNull { it.littleEndianRange }
                } else null
                appendLine("Possible X candidate: ${possibleXByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
                appendLine("Possible Y candidate: ${possibleYByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
                appendLine("Possible X 16-bit candidate: ${possibleXPair?.let { "${it.name}, bytes ${it.byteOffsets.first}-${it.byteOffsets.last}" } ?: "insufficient/discriminating samples"}")
                appendLine("Possible Y 16-bit candidate: ${possibleYPair?.let { "${it.name}, bytes ${it.byteOffsets.first}-${it.byteOffsets.last}" } ?: "insufficient/discriminating samples"}")
                appendLine("Possible pressure candidate: ${possiblePressure?.name ?: "insufficient/discriminating samples"}")
                val hover = analyses["HOVER_CENTER"]
                val tip = analyses["TIP_UP_DOWN"]
                val sideButton = analyses["SIDE_BUTTON_HOVER"]
                val flagBytes = listOfNotNull(hover, tip, sideButton).minOfOrNull { it.bytes.size } ?: 0
                val possibleTouchByte = if (hover != null && tip != null) (0 until flagBytes)
                    .maxByOrNull { index -> tip.bytes[index].range - hover.bytes[index].range }
                    ?.takeIf { tip.bytes[it].range > hover.bytes[it].range } else null
                val possibleButtonByte = if (hover != null && sideButton != null) (0 until flagBytes)
                    .maxByOrNull { index -> sideButton.bytes[index].range - hover.bytes[index].range }
                    ?.takeIf { sideButton.bytes[it].range > hover.bytes[it].range } else null
                appendLine("Possible contact/hover flag: ${possibleTouchByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
                appendLine("Possible side-button flag: ${possibleButtonByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
                appendLine("These are experimental statistical candidates only; no Wacom fields have been identified definitively.")
            }
            val comparableInterfaces = experimentSessions.groupBy { it.interfaceId }.filterValues { sessions ->
                val ids = sessions.map { it.scenario.id }.toSet()
                "HOVER_CENTER" in ids && "MOVE_X_ONLY" in ids && "MOVE_Y_ONLY" in ids
            }
            val recommended = if (comparableInterfaces.size >= 2) comparableInterfaces.maxByOrNull { (_, sessions) ->
                sessions.map { HidExperimentAnalyzer.analyze(it).reportsPerSecond }.average() +
                    HidExperimentAnalyzer.movementVariationEvidence(sessions) * 2.0
            }?.key else null
            appendLine("Recommended stylus interface (experimental): ${recommended?.let { "Interface $it" } ?: "insufficient sessions"}")
        }
    }

    private fun writeExperimentExport(uri: Uri) {
        val content = pendingExportText ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Could not open export destination")
        }.onFailure { captureError = "Export failed: ${it.message}" }
    }

    private fun hex(value: Int): String = value.toString(16).padStart(4, '0').uppercase()

    private fun startExternalTapDiagnostic() {
        val info = displayInfo ?: return
        if (!::shizukuBackend.isInitialized || shizukuBackendState.status != ShizukuBackendStatus.READY ||
            !shizukuBackendState.userServiceConnected
        ) return

        externalTapJob?.cancel()
        val display = this.display ?: windowManager.defaultDisplay
        val displayId = display.displayId
        val x = info.displayWidth / 2f
        val y = info.displayHeight / 2f
        externalTapDiagnostic = ShizukuTapDiagnostic(
            testName = "TEST EXTERNAL TAP",
            displayId = displayId,
            displayWidth = info.displayWidth,
            displayHeight = info.displayHeight,
            displayRotationDegrees = display.rotation * 90,
            configurationOrientation = info.configurationOrientation,
            mappingOrientation = mappingOptions.tabletRotation.name,
            x = x,
            y = y,
        )
        externalTapRunning = true
        externalTapJob = lifecycleScope.launch {
            try {
                for (value in 5 downTo 1) {
                    externalTapCountdown = value
                    delay(1_000)
                }
                externalTapCountdown = 0
                val downTime = SystemClock.uptimeMillis()
                externalTapDiagnostic = externalTapDiagnostic?.copy(
                    downTimeMillis = downTime,
                    downEventTimeMillis = downTime,
                )
                val downProbe = ShizukuMotionProbe(
                    action = MotionEvent.ACTION_DOWN,
                    x = x,
                    y = y,
                    pressure = 1f,
                    source = InputDevice.SOURCE_TOUCHSCREEN,
                    toolType = MotionEvent.TOOL_TYPE_FINGER,
                    downTimeMillis = downTime,
                    eventTimeMillis = downTime,
                    displayId = displayId,
                )
                val downAttempt = withContext(Dispatchers.IO) {
                    shizukuBackend.injectManual("TEST EXTERNAL TAP DOWN", downProbe)
                }
                externalTapDiagnostic = externalTapDiagnostic?.copy(downAttempt = downAttempt)

                delay(80L)
                val upTime = SystemClock.uptimeMillis()
                externalTapDiagnostic = externalTapDiagnostic?.copy(upEventTimeMillis = upTime)
                val upProbe = ShizukuMotionProbe(
                    action = MotionEvent.ACTION_UP,
                    x = x,
                    y = y,
                    pressure = 0f,
                    source = InputDevice.SOURCE_TOUCHSCREEN,
                    toolType = MotionEvent.TOOL_TYPE_FINGER,
                    downTimeMillis = downTime,
                    eventTimeMillis = upTime,
                    displayId = displayId,
                )
                val upAttempt = withContext(Dispatchers.IO) {
                    shizukuBackend.injectManual("TEST EXTERNAL TAP UP", upProbe)
                }
                externalTapDiagnostic = externalTapDiagnostic?.copy(upAttempt = upAttempt)
            } catch (exception: Exception) {
                val errorAttempt = ShizukuInjectionAttempt(
                    name = "TEST EXTERNAL TAP ERROR",
                    timestampMillis = System.currentTimeMillis(),
                    methodApi = "WacomMapper lifecycleScope / Shizuku backend",
                    returned = null,
                    exception = Log.getStackTraceString(exception),
                    securityException = exception is SecurityException,
                    detail = "External tap countdown or dispatch aborted.",
                )
                externalTapDiagnostic = externalTapDiagnostic?.copy(upAttempt = errorAttempt)
                Log.e("SHIZUKU_ERROR", "External tap test failed", exception)
            } finally {
                externalTapCountdown = 0
                externalTapRunning = false
                externalTapJob = null
            }
        }
    }

    private fun injectShizukuProbe(name: String, probe: ShizukuMotionProbe): ShizukuInjectionAttempt {
        if (name == "TEST GLOBAL TAP" && probe.action == MotionEvent.ACTION_DOWN) liveTapDownAccepted = false
        val result = shizukuBackend.injectManual(name, probe)
        if (name == "TEST GLOBAL TAP" && probe.action == MotionEvent.ACTION_DOWN) {
            liveTapDownAccepted = result.returned == true && result.exception == null
        }
        if (name == "TEST GLOBAL TAP" && probe.action == MotionEvent.ACTION_UP) {
            liveTapTestPassed = liveTapDownAccepted && result.returned == true && result.exception == null
        }
        if (name == "FOLLOW_TEST_UP") liveFollowTestPassed = result.returned == true && result.exception == null
        return result
    }

    private fun refreshHoverOverlayPermission() {
        val granted = Settings.canDrawOverlays(this)
        overlayPermissionGranted = granted
        if (granted && showHoverCursor && liveGlobalStylusActive && hoverCursorVisualState.visible) {
            HoverCursorOverlay.setEnabled(this, true)
            HoverCursorOverlay.show(
                this,
                hoverCursorVisualState.screenX,
                hoverCursorVisualState.screenY,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun updateHoverCursorPreference(enabled: Boolean) {
        showHoverCursor = enabled
        if (::preferences.isInitialized) preferences.showHoverCursor = enabled
        productService?.updateCursorPreference(enabled)
        HoverCursorOverlay.setEnabled(this, enabled)
        if (enabled && overlayPermissionGranted && liveGlobalStylusActive && hoverCursorVisualState.visible) {
            HoverCursorOverlay.show(
                this,
                hoverCursorVisualState.screenX,
                hoverCursorVisualState.screenY,
                SystemClock.elapsedRealtimeNanos(),
            )
        }
    }

    private fun prepareLiveWacomTest(): Boolean {
        val ready = shizukuBackendState.status == ShizukuBackendStatus.READY && shizukuBackendState.userServiceConnected
        if (!ready || liveSnapshot == null) return false
        return try {
            HoverCursorOverlay.setEnabled(this, showHoverCursor)
            // Must happen while this Activity is visible. Android 12+ rejects FGS starts from background.
            LiveWacomForegroundService.startDiagnosticNotification(this)
            true
        } catch (throwable: Throwable) {
            Log.e("LIVE_FATAL", "Could not start bounded Live foreground service", throwable)
            liveWacomMetrics = liveWacomMetrics.copy(
                exceptionClass = throwable::class.java.name,
                exceptionMessage = throwable.message,
                exceptionStackTrace = throwable.stackTraceToString(),
                stopReason = LiveStopReason.START_EXCEPTION,
                liveError = "${throwable::class.java.name}: ${throwable.message}",
            )
            false
        }
    }

    private fun beginLiveTrace(hidOnly: Boolean, parsedStylusTest: Boolean, eventTest: Boolean, coroutineState: String) {
        synchronized(liveTraceLock) {
            liveCleanupInProgress = false
            liveCleanupCompleted = false
            liveReaderFatal = null
            liveSessionCompletion = CompletableDeferred()
            liveWacomMetrics = LiveWacomMetrics(hidOnly = hidOnly)
        }
        liveParsedTestActive = parsedStylusTest
        liveStylusEventTestActive = eventTest
        liveGlobalStylusActive = eventTest && !hidOnly
        injectedStylusDispatchCount = 0L
        lastInjectedStylusDispatch = "No injected stylus event observed in WacomMapper yet."
        liveGlobalEventsReceived.set(0L)
        lastGlobalRawX = null
        lastGlobalRawY = null
        hoverCursorVisualState = HoverCursorVisualState()
        HoverCursorOverlay.hide()
        usbToMapperLatency.clear()
        mapperToInjectLatency.clear()
        usbToInjectLatency.clear()
        if (eventTest) {
            stylusStateMapper.reset()
            stylusStateMapper.setThresholds(tipDownThreshold, tipUpThreshold)
            liveStylusStrokeId = 0L
            liveStylusStrokeActive = false
            synchronized(liveStylusCanvasPoints) { liveStylusCanvasPoints.clear() }
            liveStylusEventCount.set(0L)
            lastStylusMoveLogElapsed.set(0L)
            liveStylusEventFrame = LiveStylusEventFrame(
                tipDownThreshold = tipDownThreshold,
                tipUpThreshold = tipUpThreshold,
            )
        }
        lastStylusStateLogElapsed.set(0L)
        lastGlobalStylusMoveLogElapsed.set(0L)
        appendLiveTrace("button pressed (${if (parsedStylusTest) "LIVE PARSED STYLUS TEST" else if (eventTest && !hidOnly) "LIVE GLOBAL STYLUS TEST" else if (eventTest) "LIVE STYLUS EVENT TEST" else if (hidOnly) "LIVE HID ONLY" else "LIVE WACOM"}); $coroutineState")
    }

    private fun appendLiveTrace(message: String) {
        val step = when {
            message.contains("button pressed") -> 1
            message.contains("startLiveHidOnly entered") || message.contains("startLiveWacom entered") -> 2
            message.contains("old session cleanup started") -> 3
            message.contains("old session cleanup finished") -> 4
            message.contains("new session job active") -> 5
            message.contains("device lookup started") -> 6
            message.contains("device found") -> 7
            message.contains("USB permission checked") -> 8
            message.contains("openDevice called") -> 9
            message.contains("openDevice returned") -> 10
            message.contains("interface lookup") -> 11
            message.contains("claimInterface called") -> 12
            message.contains("claimInterface result") -> 13
            message.contains("endpoint lookup") -> 14
            message.contains("reader job started") -> 15
            message.contains("first read attempt") -> 16
            message.contains("first report") -> 17
            message.contains("time limit reached") -> 18
            message.contains("cleanup started") -> 19
            message.contains("cleanup finished") -> 20
            else -> null
        }
        val numbered = step?.let { "[%02d] %s".format(it, message) } ?: message
        val tag = when {
            message.contains("cleanup") -> "LIVE_CLEANUP"
            message.contains("scope") || message.contains("parentJob") -> "LIVE_SCOPE"
            message.contains("job") -> "LIVE_JOB"
            message.contains("first read") || message.contains("report") -> "LIVE_HID"
            message.contains("openDevice") || message.contains("interface") || message.contains("claim") || message.contains("endpoint") -> "LIVE_USB"
            message.contains("exception", ignoreCase = true) -> "LIVE_FATAL"
            else -> "LIVE_START"
        }
        Log.i(tag, numbered)
        runOnUiThread {
            val nextTrace = (liveWacomMetrics.startTrace + numbered).takeLast(80)
            liveWacomMetrics = liveWacomMetrics.copy(
                startTrace = nextTrace,
                lastReachedStage = step?.let { "[%02d]".format(it) } ?: liveWacomMetrics.lastReachedStage,
            )
        }
    }

    private suspend fun startLiveSession(hidOnly: Boolean, globalValidation: Boolean = false): Boolean {
        if (!ensureDiagnosticsUsbAvailable()) return false
        appendLiveTrace(if (hidOnly) "startLiveHidOnly entered" else "startLiveWacom entered")
        val callerJob = currentCoroutineContext()[Job]
        val scopeInfo = usbSessionManager.coroutineDiagnostics(previousJob = null, parentJob = callerJob)
        liveWacomMetrics = liveWacomMetrics.copy(coroutineDiagnostics = scopeInfo)
        appendLiveTrace("scope diagnostic: $scopeInfo")
        return try {
            runLiveSession(hidOnly, globalValidation)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            val managerActive = usbSessionManager.coroutineDiagnostics(null, callerJob).contains("liveScope active=true")
            val reason = when {
                isFinishing || isDestroyed -> LiveStopReason.LIFECYCLE_CANCELLED
                !managerActive -> LiveStopReason.PARENT_SCOPE_CANCELLED
                else -> LiveStopReason.SESSION_JOB_CANCELLED
            }
            storeLiveThrowable(cancellation, reason)
            appendLiveTrace("session cancellation: ${cancellation::class.java.name}: ${cancellation.message}")
            throw cancellation
        } catch (throwable: Throwable) {
            val reason = (throwable as? com.example.wacommapper.usb.HidReaderStartException)
                ?.failureCode
                ?.let { runCatching { LiveStopReason.valueOf(it) }.getOrNull() }
                ?: LiveStopReason.START_EXCEPTION
            storeLiveThrowable(throwable, reason)
            appendLiveTrace("start exception: ${throwable::class.java.name}: ${throwable.message}")
            Log.e("LIVE_FATAL", "Live start failed", throwable)
            false
        }
    }

    private fun storeLiveThrowable(throwable: Throwable, reason: LiveStopReason) {
        val stoppedAt = System.currentTimeMillis()
        liveSessionStoppedAtWall = stoppedAt
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = com.example.wacommapper.output.LiveWacomState.ERROR,
            liveError = "${throwable::class.java.name}: ${throwable.message ?: "<sin mensaje>"}",
            exceptionClass = throwable::class.java.name,
            exceptionMessage = throwable.message,
            exceptionCause = causeChain(throwable),
            exceptionStackTrace = throwable.stackTraceToString(),
            stoppedAtMillis = stoppedAt,
            stopReason = reason,
        )
    }

    private fun causeChain(throwable: Throwable): String? = generateSequence(throwable.cause) { it.cause }
        .take(8)
        .map { "${it::class.java.name}: ${it.message ?: "<sin mensaje>"}" }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" <- ")

    private fun recordPipelineFailure(stage: String, throwable: Throwable) {
        val errors = liveMetricPipelineErrors.incrementAndGet()
        Log.e("LIVE_PIPELINE", "$stage failed; reader remains active", throwable)
        runOnUiThread {
            liveWacomMetrics = liveWacomMetrics.copy(
                pipelineErrors = errors,
                liveError = "Pipeline $stage: ${throwable::class.java.name}: ${throwable.message}",
                exceptionClass = throwable::class.java.name,
                exceptionMessage = throwable.message,
                exceptionCause = causeChain(throwable),
                exceptionStackTrace = throwable.stackTraceToString(),
            )
        }
    }

    private suspend fun testOpenUsbOnly() {
        if (!ensureDiagnosticsUsbAvailable()) return
        var opened: UsbDeviceConnection? = null
        var openedAtElapsed = 0L
        openUsbOnlyDiagnostic = UsbOpenOnlyDiagnostic(running = true)
        Log.i("LIVE_START", "TEST OPEN USB ONLY pressed")
        try {
            usbSessionManager.stopAndWait()
            val manager = getSystemService(UsbManager::class.java)
            val device = manager.deviceList.values.firstOrNull { it.vendorId == 0x056A && it.productId == 0x037A }
            if (device == null) {
                openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(deviceFound = false)
                return
            }
            val permission = manager.hasPermission(device)
            openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(deviceFound = true, permissionGranted = permission)
            if (!permission) {
                detector.requestPermission(device.deviceName)
                return
            }
            Log.i("LIVE_USB", "TEST OPEN USB ONLY calling openDevice")
            opened = manager.openDevice(device)
            Log.i("LIVE_USB", "TEST OPEN USB ONLY openDevice null=${opened == null}")
            if (opened == null) return
            openedAtElapsed = SystemClock.elapsedRealtime()
            openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(connectionOpened = true)
            delay(3_000L)
        } catch (throwable: Throwable) {
            openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(
                exceptionClass = throwable::class.java.name,
                exceptionMessage = throwable.message,
                exceptionStackTrace = throwable.stackTraceToString(),
            )
            Log.e("LIVE_FATAL", "TEST OPEN USB ONLY failed", throwable)
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
        } finally {
            try {
                opened?.close()
            } catch (throwable: Throwable) {
                openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(
                    exceptionClass = throwable::class.java.name,
                    exceptionMessage = throwable.message,
                    exceptionStackTrace = throwable.stackTraceToString(),
                )
                Log.e("LIVE_FATAL", "TEST OPEN USB ONLY close failed", throwable)
            }
            val duration = if (openedAtElapsed > 0L) (SystemClock.elapsedRealtime() - openedAtElapsed).coerceAtLeast(0L) else 0L
            openUsbOnlyDiagnostic = openUsbOnlyDiagnostic.copy(
                connectionOpened = false,
                actualDurationMillis = duration,
                running = false,
            )
            Log.i("LIVE_CLEANUP", "TEST OPEN USB ONLY closed; actual=${duration}ms")
        }
    }

    private suspend fun testLegacyHidReader() {
        if (!ensureDiagnosticsUsbAvailable()) return
        var startedAt = 0L
        legacyHidReportCount.set(0L)
        legacyHidDiagnostic = LegacyHidDiagnostic(running = true)
        Log.i("LIVE_START", "TEST LEGACY HID READER pressed")
        try {
            // Same manager and same canonical HidRawReader used by every diagnostics/live screen.
            usbSessionManager.stopAndWait()
            val usbManager = getSystemService(UsbManager::class.java)
            val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x056A && it.productId == 0x037A }
            if (device == null) {
                legacyHidDiagnostic = legacyHidDiagnostic.copy(deviceFound = false, exceptionMessage = "CTL-472 not found")
                return
            }
            val permitted = usbManager.hasPermission(device)
            legacyHidDiagnostic = legacyHidDiagnostic.copy(deviceFound = true, permissionGranted = permitted)
            if (!permitted) {
                legacyHidDiagnostic = legacyHidDiagnostic.copy(exceptionMessage = "USB permission not granted")
                return
            }
            legacyHidCollecting = true
            Log.i("LIVE_USB", "LEGACY shared HidRawReader start interface=0 endpoint=0x81")
            usbSessionManager.startFresh(device.deviceName, 0, 0x81)
            startedAt = SystemClock.elapsedRealtime()
            legacyHidDiagnostic = legacyHidDiagnostic.copy(connectionOpened = true)
            delay(5_000L)
        } catch (throwable: Throwable) {
            Log.e("LIVE_FATAL", "TEST LEGACY HID READER startup failed", throwable)
            legacyHidDiagnostic = legacyHidDiagnostic.copy(
                exceptionClass = throwable::class.java.name,
                exceptionMessage = throwable.message,
                exceptionCause = causeChain(throwable),
                exceptionStackTrace = throwable.stackTraceToString(),
            )
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
        } finally {
            try {
                if (::usbSessionManager.isInitialized) usbSessionManager.stopAndWait()
            } catch (throwable: Throwable) {
                Log.e("LIVE_FATAL", "TEST LEGACY HID READER stop failed", throwable)
                legacyHidDiagnostic = legacyHidDiagnostic.copy(
                    exceptionClass = throwable::class.java.name,
                    exceptionMessage = throwable.message,
                    exceptionCause = causeChain(throwable),
                    exceptionStackTrace = throwable.stackTraceToString(),
                )
            }
            legacyHidCollecting = false
            legacyHidDiagnostic = legacyHidDiagnostic.copy(
                running = false,
                connectionOpened = false,
                durationMillis = if (startedAt > 0L) (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) else 0L,
                reportsReceived = legacyHidReportCount.get(),
            )
        }
    }

    private suspend fun runLiveSession(hidOnly: Boolean, globalValidation: Boolean = false): Boolean {
        if (!hidOnly && (shizukuBackendState.status != ShizukuBackendStatus.READY || !shizukuBackendState.userServiceConnected)) {
            setLiveError("Shizuku no está READY; no se inició la adquisición.")
            return false
        }
        liveIsHidOnly = hidOnly
        liveOutputEnabled = false
        liveAcquisitionActive = false
        liveWacomUntilElapsed = 0L
        val trace = liveWacomMetrics.startTrace
        val coroutineDiagnostics = liveWacomMetrics.coroutineDiagnostics
        liveWacomMetrics = LiveWacomMetrics(
            hidOnly = hidOnly,
            startTrace = trace,
            coroutineDiagnostics = coroutineDiagnostics,
            unboundedSession = globalValidation,
        )
        liveSessionStartedElapsed = 0L
        liveSessionStartedAtWall = 0L
        liveSessionStoppedAtWall = 0L
        liveHidOnlyPoint = null
        liveMetricHid.set(0L)
        liveMetricTenByte.set(0L)
        liveMetricTabletReports.set(0L)
        liveMetricOutOfRangeReports.set(0L)
        liveMetricGenericReports.set(0L)
        liveMetricInvalidReports.set(0L)
        liveMetricPressureMin.set(Long.MAX_VALUE)
        liveMetricPressureMax.set(0L)
        liveMetricParseErrors.set(0L)
        liveMetricPipelineErrors.set(0L)
        livePipelineTraceCounter.set(0L)
        liveMetricIgnored.set(0L)
        liveMetricParsed.set(0L)
        liveMetricMapped.set(0L)
        liveMetricSubmitted.set(0L)
        liveMetricInjected.set(0L)
        liveMetricRejected.set(0L)
        liveMetricHoverEnter.set(0L)
        liveMetricHoverMove.set(0L)
        liveMetricHoverExit.set(0L)
        liveMetricDown.set(0L)
        liveMetricMove.set(0L)
        liveMetricUp.set(0L)
        liveMetricLatencySum.set(0L)
        liveMetricLatencySamples.set(0L)
        liveMetricMaxLatency.set(0L)
        liveMetricLastPublish.set(0L)
        liveMetricXChanges.set(0L)
        liveMetricYChanges.set(0L)
        lastLiveParsedX = -1
        lastLiveParsedY = -1
        liveSessionStartedElapsed = SystemClock.elapsedRealtime()
        liveSessionStartedAtWall = System.currentTimeMillis()
        liveMetricStartElapsed = liveSessionStartedElapsed
        liveWacomMetrics = liveWacomMetrics.copy(
            startedAtMillis = liveSessionStartedAtWall,
            requestedDurationMillis = if (globalValidation) 0L else LIVE_WACOM_DURATION_MS,
            unboundedSession = globalValidation,
        )
        liveLastRawTimestamp = 0L
        liveLastMethod = null
        liveLastResult = null
        liveLastException = null

        Log.i("LIVE_WACOM", "Starting autonomous ${if (hidOnly) "HID-only" else "Wacom output"} session")
        liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.STOPPING)
        appendLiveTrace("old session cleanup started")
        usbSessionManager.stopAndWait()
        appendLiveTrace("old session cleanup finished")
        val liveJob = currentCoroutineContext()[Job]
        appendLiveTrace("new session job active=${liveJob?.isActive} cancelled=${liveJob?.isCancelled}")

        val usbManager = getSystemService(UsbManager::class.java)
        appendLiveTrace("device lookup started")
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x056A && it.productId == 0x037A }
        val permission = device?.let(usbManager::hasPermission) == true
        if (device != null) appendLiveTrace("device found: ${device.deviceName}, product=${device.productName}")
        appendLiveTrace("USB permission checked: $permission")
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = com.example.wacommapper.output.LiveWacomState.OPENING_USB,
            usbDeviceFound = device != null,
            usbPermissionGranted = permission,
            connectionOpened = false,
            requestedInterface = 0,
            requestedEndpoint = 0x81,
            liveError = null,
        )
        if (device == null) return setLiveError("No se encontró CTL-472 (VID 0x056A / PID 0x037A).", LiveStopReason.USB_DISCONNECTED)
        Log.i("LIVE_USB", "device CTL-472 found; permission=$permission")
        if (!permission) {
            detector.requestPermission(device.deviceName)
            return setLiveError("Permiso USB requerido: acepta el diálogo de Android y vuelve a pulsar la prueba.", LiveStopReason.START_EXCEPTION)
        }
        Log.i("LIVE_USB", "opening connection for ${device.deviceName}")
        delay(80L)
        liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.CLAIMING_INTERFACE)
        Log.i("LIVE_USB", "claiming interface 0; required endpoint 0x81")
        liveReaderFatal = null
        // Enable report fan-out before the canonical reader's coroutine can deliver its first packet.
        liveAcquisitionActive = true
        liveOutputEnabled = false
        liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.WAITING_FOR_HID_REPORTS)
        if (!usbSessionManager.startFresh(device.deviceName, 0, 0x81)) {
            return setLiveError(captureError ?: "No se pudo abrir/reclamar Interface 0 / EP 0x81.", LiveStopReason.START_EXCEPTION)
        }
        liveReaderFatal?.let { throwable ->
            storeLiveThrowable(throwable, LiveStopReason.USB_FATAL_ERROR)
            return false
        }
        val openedState = usbSessionManager.connectionState()
        Log.i("LIVE_USB", "connection opened=${openedState.isOpen}; claimedInterface=${openedState.claimedInterfaceId}; activeEndpoint=0x${openedState.activeEndpointAddress?.toString(16)}; packetSize=${openedState.packetSize}")
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = com.example.wacommapper.output.LiveWacomState.WAITING_FOR_HID_REPORTS,
            connectionOpened = openedState.isOpen,
            claimedInterface = openedState.claimedInterfaceId,
            activeEndpoint = openedState.activeEndpointAddress,
            maxPacketSize = openedState.packetSize,
        )
        Log.i("LIVE_USB", "claim interface 0 = ${openedState.claimedInterfaceId == 0}; endpoint=0x${openedState.activeEndpointAddress?.toString(16)}")
        if (!openedState.isOpen || openedState.claimedInterfaceId != 0 || openedState.activeEndpointAddress != 0x81) {
            usbSessionManager.stopAndWait()
            return setLiveError("La sesión USB no coincide con Interface 0 / EP 0x81.")
        }

        liveWacomSecondsRemaining = if (globalValidation) 0 else LIVE_WACOM_DURATION_SECONDS
        liveSessionStoppedAtWall = 0L
        liveWacomUntilElapsed = if (globalValidation) Long.MAX_VALUE else SystemClock.elapsedRealtime() + LIVE_WACOM_DURATION_MS
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = com.example.wacommapper.output.LiveWacomState.WAITING_FOR_HID_REPORTS,
            liveError = null,
            stoppedAtMillis = null,
            actualDurationMillis = 0L,
            requestedDurationMillis = if (globalValidation) 0L else LIVE_WACOM_DURATION_MS,
            unboundedSession = globalValidation,
            stopReason = null,
        )
        if (!globalValidation) scheduleLiveWacomStop()
        Log.i(
            "LIVE_WACOM",
            if (globalValidation) "Global validation started; duration is user-controlled and ends on STOP/disconnect/lifecycle"
            else "Reader is continuous; ${LIVE_WACOM_DURATION_MS} ms duration started independently of report arrival",
        )
        publishLiveMetrics()
        return true
    }

    private fun setLiveError(message: String, reason: LiveStopReason = LiveStopReason.START_EXCEPTION): Boolean {
        liveAcquisitionActive = false
        liveOutputEnabled = false
        liveSessionStoppedAtWall = System.currentTimeMillis()
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = com.example.wacommapper.output.LiveWacomState.ERROR,
            liveError = message,
            connectionOpened = usbSessionManager.connectionState().isOpen,
            stoppedAtMillis = liveSessionStoppedAtWall,
            stopReason = reason,
        )
        Log.e("LIVE_WACOM", message)
        return false
    }

    private fun scheduleLiveWacomStop() {
        liveTimerRunnable?.let(liveTimerHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - liveSessionStartedElapsed).coerceAtLeast(0L)
                val remaining = liveWacomUntilElapsed - now
                if (remaining <= 0L) {
                    appendLiveTrace("time limit reached")
                    stopLiveWacomTest(LiveStopReason.TIME_LIMIT)
                } else {
                    liveWacomSecondsRemaining = ceil(remaining / 1_000.0).toInt()
                    liveWacomMetrics = liveWacomMetrics.copy(actualDurationMillis = elapsed)
                    publishLiveMetrics()
                    liveTimerHandler.postDelayed(this, 100L)
                }
            }
        }
        liveTimerRunnable = runnable
        liveTimerHandler.postDelayed(runnable, 100L)
    }

    private fun stopLiveWacomTest(reason: LiveStopReason = LiveStopReason.USER_STOP) {
        val shouldCleanup = synchronized(liveTraceLock) {
            if (liveCleanupInProgress || liveCleanupCompleted) false
            else {
                liveCleanupInProgress = true
                true
            }
        }
        if (!shouldCleanup) return
        val wasActive = liveAcquisitionActive
        val preserveError = liveWacomMetrics.liveState == com.example.wacommapper.output.LiveWacomState.ERROR
        val recordedReason: LiveStopReason = if (wasActive) {
            reason
        } else {
            liveWacomMetrics.stopReason ?: if (preserveError) LiveStopReason.START_EXCEPTION else reason
        }
        val stoppedElapsed = SystemClock.elapsedRealtime()
        val actualDuration = if (!wasActive && liveWacomMetrics.stopReason != null) {
            liveWacomMetrics.actualDurationMillis
        } else {
            liveSessionStartedElapsed.takeIf { it > 0L }
                ?.let { (stoppedElapsed - it).coerceAtLeast(0L) } ?: 0L
        }
        val releaseGlobal = liveGlobalStylusActive
        liveGlobalStylusActive = false
        hoverCursorVisualState = hoverCursorVisualState.copy(visible = false)
        HoverCursorOverlay.remove()
        val releaseOutput = liveOutputEnabled && !liveIsHidOnly && !releaseGlobal
        if (releaseGlobal && ::shizukuInputBackend.isInitialized) {
            Thread {
                val output = shizukuInputBackend.releaseAllInput()
                Log.i("STYLUS_GLOBAL", "emergency release result=${output.lastResult} exception=${output.lastException}")
            }.start()
        } else if (releaseOutput) {
            val info = displayInfo
            Thread {
                shizukuBackend.emergencyStop((info?.windowWidth ?: 0) / 2f, (info?.windowHeight ?: 0) / 2f)
            }.start()
        }
        liveWacomUntilElapsed = 0L
        liveWacomSecondsRemaining = 0
        liveAcquisitionActive = false
        liveOutputEnabled = false
        liveTimerRunnable?.let(liveTimerHandler::removeCallbacks)
        liveTimerRunnable = null
        LiveWacomForegroundService.stop(this)
        liveSessionStoppedAtWall = if (wasActive || liveSessionStoppedAtWall == 0L) System.currentTimeMillis() else liveSessionStoppedAtWall
        liveWacomMetrics = liveWacomMetrics.copy(
            liveState = if (preserveError) com.example.wacommapper.output.LiveWacomState.ERROR else com.example.wacommapper.output.LiveWacomState.STOPPING,
            actualDurationMillis = actualDuration,
            stoppedAtMillis = liveSessionStoppedAtWall.takeIf { it > 0L },
            stopReason = recordedReason,
        )
        publishLiveMetrics(force = true)
        if (::usbSessionManager.isInitialized) {
            appendLiveTrace("cleanup started; reason=$recordedReason")
            lifecycleScope.launch {
                var cleanupFailure: Throwable? = null
                try {
                    usbSessionManager.stopAndWait()
                } catch (throwable: Throwable) {
                    cleanupFailure = throwable
                    Log.e("LIVE_FATAL", "USB session cleanup failed", throwable)
                    liveWacomMetrics = liveWacomMetrics.copy(
                        exceptionClass = throwable::class.java.name,
                        exceptionMessage = throwable.message,
                        exceptionStackTrace = throwable.stackTraceToString(),
                        liveError = "Cleanup: ${throwable::class.java.name}: ${throwable.message}",
                        stopReason = LiveStopReason.CLEANUP_CANCELLED_SESSION,
                        liveState = com.example.wacommapper.output.LiveWacomState.ERROR,
                    )
                } finally {
                    liveParsedTestActive = false
                    liveStylusEventTestActive = false
                    appendLiveTrace("cleanup finished")
                    synchronized(liveTraceLock) {
                        liveCleanupInProgress = false
                        liveCleanupCompleted = true
                    }
                }
                if (!preserveError) {
                    liveWacomMetrics = liveWacomMetrics.copy(
                        liveState = if (cleanupFailure != null) com.example.wacommapper.output.LiveWacomState.ERROR else if (recordedReason == LiveStopReason.TIME_LIMIT) {
                            com.example.wacommapper.output.LiveWacomState.FINISHED
                        } else {
                            com.example.wacommapper.output.LiveWacomState.IDLE
                        },
                        connectionOpened = usbSessionManager.connectionState().isOpen,
                    )
                } else {
                    liveWacomMetrics = liveWacomMetrics.copy(connectionOpened = usbSessionManager.connectionState().isOpen)
                }
                // Complete only after the reader is joined and the exact final panel snapshot is published.
                val finalSnapshot = buildLiveMetricsSnapshot()
                liveWacomMetrics = finalSnapshot
                liveSessionCompletion.complete(finalSnapshot)
            }
        } else {
            val finalSnapshot = buildLiveMetricsSnapshot()
            liveWacomMetrics = finalSnapshot
            liveSessionCompletion.complete(finalSnapshot)
        }
        Log.i("LIVE_WACOM", "Stop reason=$recordedReason actualDuration=${actualDuration}ms; USB close requested")
    }

    private fun forwardLiveWacomReport(report: HidRawReport, usbElapsedNanos: Long) {
        liveLastRawTimestamp = report.timestamp
        if (liveParsedTestActive && fullRawStylusLogging) {
            Log.v("STYLUS_RAW", "timestamp=${report.timestamp} interface=${report.interfaceId} endpoint=0x${report.endpointAddress.toString(16)} len=${report.bytes.size} raw=${report.hex}")
        }
        runOnUiThread {
            liveWacomMetrics = liveWacomMetrics.copy(lastRawReport = report.hex, lastRawAgeMillis = 0)
        }
        if (liveParsedTestActive || liveStylusEventTestActive) {
            forwardLiveParsedDiagnostic(report, usbElapsedNanos)
            return
        }
        val validHidReport = report.bytes.size == 10 && (report.bytes[0].toInt() and 0xFF) == 0x02
        if (report.bytes.size == 10) {
            val validCount = liveMetricTenByte.incrementAndGet()
            if (validCount == 1L || validCount % 100L == 0L) Log.i("LIVE_PIPELINE", "valid10BytePackets updated #$validCount")
        }
        if (!validHidReport) liveMetricIgnored.incrementAndGet()
        if (validHidReport) {
            if (liveMetricTenByte.get() == 1L) {
                Log.i("LIVE_HID", "first report received: ${report.hex}")
                runOnUiThread {
                    if (liveWacomMetrics.liveState != com.example.wacommapper.output.LiveWacomState.OUTPUT_ACTIVE) {
                        liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.HID_ACTIVE)
                    }
                }
            }
        } else {
            if (liveMetricHid.get() == 1L) Log.w("LIVE_HID", "Non-standard report observed (still retained RAW): len=${report.bytes.size} raw=${report.hex}")
            publishLiveMetrics()
            return
        }
        val snapshot = liveSnapshot
        if (snapshot == null) {
            liveMetricRejected.incrementAndGet()
            publishLiveMetrics()
            return
        }
        Log.d("LIVE_PIPELINE", "parser called for raw=${report.hex}")
        val parsed = try {
            reportParser.parse(
                report,
                snapshot.evidence.xByteOrder.selected,
                snapshot.evidence.yByteOrder.selected,
                snapshot.evidence.pressureByteOrder.selected,
            )
        } catch (throwable: Throwable) {
            liveMetricParseErrors.incrementAndGet()
            recordPipelineFailure("parser", throwable)
            publishLiveMetrics()
            return
        }
        Log.d("LIVE_PIPELINE", "parser result=${parsed?.let { "parsed" } ?: "null"}")
        if (parsed == null) {
            liveMetricParseErrors.incrementAndGet()
            if (validHidReport) liveMetricIgnored.incrementAndGet()
            liveMetricRejected.incrementAndGet()
            publishLiveMetrics()
            return
        }
        liveMetricParsed.incrementAndGet()
        val previousX = lastLiveParsedX
        val previousY = lastLiveParsedY
        if (previousX >= 0 && previousX != parsed.candidateX) liveMetricXChanges.incrementAndGet()
        if (previousY >= 0 && previousY != parsed.candidateY) liveMetricYChanges.incrementAndGet()
        lastLiveParsedX = parsed.candidateX
        lastLiveParsedY = parsed.candidateY
        runOnUiThread {
            if (liveWacomMetrics.liveState != com.example.wacommapper.output.LiveWacomState.OUTPUT_ACTIVE) {
                liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.PARSER_ACTIVE)
            }
        }
        Log.d("LIVE_PARSER", "x=${parsed.candidateX} y=${parsed.candidateY} pressure=${parsed.candidatePressure}")
        Log.d("LIVE_PIPELINE", "mapper called xRaw=${parsed.candidateX} yRaw=${parsed.candidateY}")
        val mapped = try {
            CoordinateMapper.map(
                xRaw = parsed.candidateX.toDouble(),
                yRaw = parsed.candidateY.toDouble(),
                usableWidth = snapshot.displayInfo.usableWidth,
                usableHeight = snapshot.displayInfo.usableHeight,
                config = TabletCoordinateConfig(),
                options = snapshot.mappingOptions,
            )
        } catch (throwable: Throwable) {
            recordPipelineFailure("mapper", throwable)
            publishLiveMetrics()
            return
        }
        liveMetricMapped.incrementAndGet()
        if (!liveIsHidOnly && liveAcquisitionActive && !liveOutputEnabled) {
            liveOutputEnabled = true
            runOnUiThread {
                liveWacomMetrics = liveWacomMetrics.copy(liveState = com.example.wacommapper.output.LiveWacomState.OUTPUT_ACTIVE)
            }
            Log.i("LIVE_OUTPUT", "Output enabled only after at least one report was received, parsed, and mapped")
        }
        val pressureNorm = CoordinateMapper.normalizePressure(parsed.candidatePressure).toFloat()
        val tip = confirmedFlag(parsed, snapshot.evidence.tip.status, snapshot.evidence.tip.candidate)
        val sideButton = confirmedFlag(parsed, snapshot.evidence.sideButton.status, snapshot.evidence.sideButton.candidate)
        val proximityCandidate = snapshot.evidence.possibleProximityBits.firstOrNull()?.let { (byte, bit) ->
            ((parsed.flagBytes[byte] ?: 0) shr bit and 1) == 1
        }
        val hoverCandidate = when {
            proximityCandidate == true && tip == false -> true
            proximityCandidate == false -> false
            else -> null
        }
        val flagSummary = parsed.flagBytes.toSortedMap().entries.joinToString(" ") { (offset, value) ->
            "b$offset=0x${value.toString(16).padStart(2, '0').uppercase()}/${value.toString(2).padStart(8, '0')}"
        }
        if (liveParsedTestActive) {
            val now = SystemClock.elapsedRealtime()
            val previousLog = lastStylusStateLogElapsed.get()
            if ((previousLog == 0L || now - previousLog >= STYLUS_STATE_LOG_INTERVAL_MS) &&
                lastStylusStateLogElapsed.compareAndSet(previousLog, now)
            ) {
                val proximityBit = snapshot.evidence.possibleProximityBits.firstOrNull()
                    ?.let { (byte, bit) -> "byte$byte.bit$bit" } ?: "UNCERTAIN"
                Log.i(
                    "STYLUS_STATE",
                    "x=${parsed.candidateX}[${snapshot.evidence.x.status}] y=${parsed.candidateY}[${snapshot.evidence.y.status}] " +
                        "pressureCandidate=${parsed.candidatePressure}[${snapshot.evidence.pressure.status}] pressureNormCandidate=${"%.3f".format(java.util.Locale.US, pressureNorm)} " +
                        "proximityCandidate=${proximityCandidate ?: "UNKNOWN"}[$proximityBit] hoverCandidate=${hoverCandidate ?: "UNKNOWN"} " +
                        "contact=${tip ?: "UNKNOWN"}[${snapshot.evidence.tip.status}] sideButton=${sideButton ?: "UNKNOWN"}[${snapshot.evidence.sideButton.status}] " +
                        "flags={$flagSummary} raw=[${report.hex}]",
                )
            }
        }
        val normalizedPoint = Pair(
            if (snapshot.displayInfo.usableWidth > 0) mapped.screenX.toFloat() / snapshot.displayInfo.usableWidth else 0f,
            if (snapshot.displayInfo.usableHeight > 0) mapped.screenY.toFloat() / snapshot.displayInfo.usableHeight else 0f,
        )
        runOnUiThread {
            liveHidOnlyPoint = normalizedPoint
            liveWacomMetrics = liveWacomMetrics.copy(
                liveState = if (liveOutputEnabled && !liveIsHidOnly) {
                    com.example.wacommapper.output.LiveWacomState.OUTPUT_ACTIVE
                } else {
                    com.example.wacommapper.output.LiveWacomState.MAPPING_ACTIVE
                },
                lastRawReport = report.hex,
                lastRawAgeMillis = 0,
                lastXRaw = parsed.candidateX,
                lastYRaw = parsed.candidateY,
                lastPressureRaw = parsed.candidatePressure,
                lastTip = tip,
                lastSideButton = sideButton,
                lastProximityCandidate = proximityCandidate,
                lastHoverCandidate = hoverCandidate,
                lastFlagBytes = flagSummary,
                xValueChanges = liveMetricXChanges.get(),
                yValueChanges = liveMetricYChanges.get(),
                mappedX = mapped.screenX.toFloat(),
                mappedY = mapped.screenY.toFloat(),
                mappedPressure = pressureNorm,
            )
        }
        Log.d("LIVE_MAPPING", "screenX=${mapped.screenX} screenY=${mapped.screenY}")
        if (!liveOutputEnabled || liveIsHidOnly) {
            publishLiveMetrics()
            return
        }
        if (tip == null) {
            val count = liveUnknownFlagLogCounter.incrementAndGet()
            if (count == 1L || count % 50L == 0L) {
                Log.w("SHIZUKU_STATUS", "LIVE WACOM paused for this report: tip bit is not confirmed by current wizard sessions")
            }
            liveMetricRejected.incrementAndGet()
            publishLiveMetrics()
            return
        }
        val proximityBit = snapshot.evidence.possibleProximityBits.firstOrNull()
        val inRange = proximityBit?.let { (byte, bit) -> ((parsed.flagBytes[byte] ?: 0) shr bit and 1) == 1 }
        val event = MappedStylusEvent(
            timestampMillis = report.timestamp,
            x = mapped.screenX.toFloat(),
            y = mapped.screenY.toFloat(),
            pressure = pressureNorm,
            tip = tip,
            sideButton = sideButton,
            inRange = inRange,
        )
        liveMetricSubmitted.incrementAndGet()
        val output = shizukuBackend.send(event)
        liveMetricInjected.addAndGet(output.injectedEvents.toLong())
        liveMetricRejected.addAndGet((output.rejectedEvents + if (output.attemptedEvents == 0) 1 else 0).toLong())
        val latency = (System.currentTimeMillis() - report.timestamp).coerceAtLeast(0L)
        liveMetricLatencySum.addAndGet(latency)
        liveMetricLatencySamples.incrementAndGet()
        liveMetricMaxLatency.updateAndGet { previous -> maxOf(previous, latency) }
        liveLastMethod = output.lastMethod
        liveLastResult = output.lastResult
        liveLastException = output.lastException
        Log.d("LIVE_OUTPUT", "inject result=${output.lastResult} method=${output.lastMethod} exception=${output.lastException}")
        publishLiveMetrics()
        if (output.support != com.example.wacommapper.output.OutputSupport.SUPPORTED) {
            val count = liveOutputWarningCounter.incrementAndGet()
            if (count == 1L || count % 50L == 0L) Log.w("SHIZUKU_INJECTION", "LIVE output=${output.support}: ${output.message}")
        }
    }

    private fun forwardLiveParsedDiagnostic(report: HidRawReport, usbElapsedNanos: Long) {
        if (report.bytes.size == com.example.wacommapper.usb.Ctl472Report.REPORT_LENGTH) {
            liveMetricTenByte.incrementAndGet()
        }
        val parsed = try {
            ctl472RawParser.parse(report.bytes)
        } catch (throwable: Throwable) {
            liveMetricParseErrors.incrementAndGet()
            recordPipelineFailure("CTL-472 raw parser", throwable)
            publishLiveMetrics()
            return
        }
        when (parsed.type) {
            com.example.wacommapper.usb.Ctl472ReportType.TABLET_REPORT -> {
                liveMetricTabletReports.incrementAndGet()
                val pressure = (parsed.pressure ?: 0).toLong()
                liveMetricPressureMin.updateAndGet { minOf(it, pressure) }
                liveMetricPressureMax.updateAndGet { maxOf(it, pressure) }
            }
            com.example.wacommapper.usb.Ctl472ReportType.OUT_OF_RANGE -> liveMetricOutOfRangeReports.incrementAndGet()
            com.example.wacommapper.usb.Ctl472ReportType.GENERIC_REPORT -> liveMetricGenericReports.incrementAndGet()
            com.example.wacommapper.usb.Ctl472ReportType.INVALID -> liveMetricInvalidReports.incrementAndGet()
        }
        if (parsed.type == com.example.wacommapper.usb.Ctl472ReportType.INVALID) {
            liveMetricIgnored.incrementAndGet()
            liveMetricParseErrors.incrementAndGet()
            publishLiveMetrics()
            return
        }
        liveMetricParsed.incrementAndGet()
        if (parsed.type == com.example.wacommapper.usb.Ctl472ReportType.GENERIC_REPORT) {
            liveMetricIgnored.incrementAndGet()
            Log.d("LIVE_PARSER", "generic report retained; no tablet fields decoded: ${report.hex}")
            publishLiveMetrics()
            return
        }
        if (liveStylusEventTestActive) {
            forwardLiveStylusEventDiagnostic(parsed, report.timestamp, usbElapsedNanos)
            publishLiveMetrics()
            return
        }
        Log.d("LIVE_PARSER", "type=${parsed.type} x=${parsed.x} y=${parsed.y} pressure=${parsed.pressure}")
        val snapshot = liveSnapshot
        if (snapshot == null) {
            liveMetricRejected.incrementAndGet()
            publishLiveMetrics()
            return
        }
        val stylusState = stylusStateMapper.map(
            report = parsed,
            usableWidth = snapshot.displayInfo.usableWidth,
            usableHeight = snapshot.displayInfo.usableHeight,
            options = snapshot.mappingOptions,
        )
        if (stylusState.mappedX != null && stylusState.mappedY != null) {
            liveMetricMapped.incrementAndGet()
            val x = stylusState.xRaw ?: -1
            val y = stylusState.yRaw ?: -1
            if (lastLiveParsedX >= 0 && lastLiveParsedX != x) liveMetricXChanges.incrementAndGet()
            if (lastLiveParsedY >= 0 && lastLiveParsedY != y) liveMetricYChanges.incrementAndGet()
            lastLiveParsedX = x
            lastLiveParsedY = y
            Log.d("LIVE_MAPPING", "screenX=${stylusState.mappedX} screenY=${stylusState.mappedY}")
        }
        val now = SystemClock.elapsedRealtime()
        val previousLog = lastStylusStateLogElapsed.get()
        if ((previousLog == 0L || now - previousLog >= STYLUS_STATE_LOG_INTERVAL_MS) &&
            lastStylusStateLogElapsed.compareAndSet(previousLog, now)
        ) {
            Log.i(
                "STYLUS_STATE",
                "type=${parsed.type} x=${parsed.x} y=${parsed.y} pressure=${parsed.pressure} " +
                    "nearProximity=${parsed.nearProximity} hoverDistance=${parsed.hoverDistance} " +
                    "contact=UNKNOWN sideButton1=${parsed.sideButton1} sideButton2=${parsed.sideButton2} " +
                    "eraser=${parsed.eraser} status=0x${parsed.statusByte?.toString(16)?.uppercase()} " +
                    "statusBit0Raw=${parsed.statusBit0} raw=[${report.hex}]",
            )
        }
        runOnUiThread {
            liveHidOnlyPoint = if (parsed.type == com.example.wacommapper.usb.Ctl472ReportType.OUT_OF_RANGE) null else {
                if (stylusState.mappedX != null && stylusState.mappedY != null && snapshot.displayInfo.usableWidth > 0 && snapshot.displayInfo.usableHeight > 0) {
                    stylusState.mappedX / snapshot.displayInfo.usableWidth to stylusState.mappedY / snapshot.displayInfo.usableHeight
                } else null
            }
            liveWacomMetrics = liveWacomMetrics.copy(
                lastRawReport = report.hex,
                lastXRaw = stylusState.xRaw,
                lastYRaw = stylusState.yRaw,
                lastPressureRaw = stylusState.pressureRaw,
                lastTip = null,
                lastSideButton = parsed.sideButton1 || parsed.sideButton2,
                lastProximityCandidate = parsed.nearProximity,
                lastHoverCandidate = null,
                lastFlagBytes = parsed.statusByte?.let { "status=0x%02X".format(it) },
                mappedX = stylusState.mappedX,
                mappedY = stylusState.mappedY,
                mappedPressure = stylusState.pressureNormalized,
                liveState = if (parsed.type == com.example.wacommapper.usb.Ctl472ReportType.OUT_OF_RANGE) {
                    com.example.wacommapper.output.LiveWacomState.HID_ACTIVE
                } else com.example.wacommapper.output.LiveWacomState.MAPPING_ACTIVE,
            )
        }
        publishLiveMetrics()
    }

    private fun forwardLiveStylusEventDiagnostic(
        report: com.example.wacommapper.usb.Ctl472Report,
        timestampMillis: Long,
        usbElapsedNanos: Long,
    ) {
        stylusStateMapper.setThresholds(tipDownThreshold, tipUpThreshold)
        val parsedAtNanos = SystemClock.elapsedRealtimeNanos()
        val update = stylusStateMapper.process(report, timestampMillis)
        liveGlobalEventsReceived.addAndGet(update.events.size.toLong())
        val points = synchronized(liveStylusCanvasPoints) { liveStylusCanvasPoints.toMutableList() }
        for (event in update.events) {
            liveStylusEventCount.incrementAndGet()
            when (event.type) {
                StylusEventType.HOVER_ENTER -> liveMetricHoverEnter.incrementAndGet()
                StylusEventType.HOVER_MOVE -> liveMetricHoverMove.incrementAndGet()
                StylusEventType.HOVER_EXIT -> liveMetricHoverExit.incrementAndGet()
                StylusEventType.DOWN -> {
                    liveMetricDown.incrementAndGet()
                    liveStylusStrokeId++
                    liveStylusStrokeActive = true
                    appendCanvasPoint(points, update.state, liveStylusStrokeId)
                }
                StylusEventType.MOVE -> {
                    liveMetricMove.incrementAndGet()
                    if (liveStylusStrokeActive) appendCanvasPoint(points, update.state, liveStylusStrokeId)
                }
                StylusEventType.UP -> {
                    liveMetricUp.incrementAndGet()
                    liveStylusStrokeActive = false
                }
                StylusEventType.OUT_OF_RANGE -> liveStylusStrokeActive = false
                else -> Unit
            }
            logStylusEvent(event.type, event, report)
            if (liveGlobalStylusActive) {
                dispatchGlobalStylusEvent(event, update.state, usbElapsedNanos, parsedAtNanos)
            } else {
                val localX = event.normalizedX ?: 0f
                val localY = event.normalizedY ?: 0f
                InternalCanvasBackend.send(
                    MappedStylusEvent(
                        timestampMillis = timestampMillis,
                        x = localX,
                        y = localY,
                        pressure = event.pressureNormalized,
                        tip = update.state.contact,
                        sideButton = update.state.sideButton1,
                        inRange = update.state.present,
                        eventType = event.type,
                        sideButton2 = update.state.sideButton2,
                    ),
                )
            }
        }
        val x = update.state.xRaw
        val y = update.state.yRaw
        if (update.state.present && x != null && y != null) {
            liveMetricMapped.incrementAndGet()
            if (lastLiveParsedX >= 0 && lastLiveParsedX != x) liveMetricXChanges.incrementAndGet()
            if (lastLiveParsedY >= 0 && lastLiveParsedY != y) liveMetricYChanges.incrementAndGet()
            lastLiveParsedX = x
            lastLiveParsedY = y
        }
        synchronized(liveStylusCanvasPoints) {
            liveStylusCanvasPoints.clear()
            liveStylusCanvasPoints.addAll(points)
        }
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - liveSessionStartedElapsed).coerceAtLeast(1L)) / 1_000f
        val lastEvent = update.events.lastOrNull() ?: liveStylusEventFrame.lastEvent
        val nextFrame = LiveStylusEventFrame(
            rawReport = report,
            update = update,
            lastEvent = lastEvent,
            eventCount = liveStylusEventCount.get(),
            eventsPerSecond = liveStylusEventCount.get() / elapsedSeconds,
            canvasPoints = points,
            tipDownThreshold = tipDownThreshold,
            tipUpThreshold = tipUpThreshold,
        )
        runOnUiThread {
            liveStylusEventFrame = nextFrame
            liveWacomMetrics = liveWacomMetrics.copy(
                lastRawReport = report.rawHex,
                lastRawAgeMillis = 0,
                lastXRaw = update.state.xRaw,
                lastYRaw = update.state.yRaw,
                lastPressureRaw = update.state.pressureRaw,
                lastSideButton = update.state.sideButton1,
                lastSideButton2 = update.state.sideButton2,
                lastProximityCandidate = update.state.nearProximity,
                lastHoverCandidate = update.state.hover,
                lastFlagBytes = report.statusByte?.let { "status=0x%02X bit0=%s".format(it, report.statusBit0) },
                xValueChanges = liveMetricXChanges.get(),
                yValueChanges = liveMetricYChanges.get(),
            )
        }
    }

    private fun dispatchGlobalStylusEvent(
        event: com.example.wacommapper.mapping.StylusInternalEvent,
        state: com.example.wacommapper.mapping.StylusState,
        usbElapsedNanos: Long,
        parsedAtNanos: Long,
    ) {
        val rawX = event.xRaw ?: lastGlobalRawX
        val rawY = event.yRaw ?: lastGlobalRawY
        if (event.xRaw != null) lastGlobalRawX = event.xRaw
        if (event.yRaw != null) lastGlobalRawY = event.yRaw
        val info = displayInfo ?: return
        val mapped = if (rawX != null && rawY != null) {
            runCatching {
                CoordinateMapper.map(
                    xRaw = rawX.toDouble(),
                    yRaw = rawY.toDouble(),
                    usableWidth = info.logicalWidth.coerceAtLeast(1),
                    usableHeight = info.logicalHeight.coerceAtLeast(1),
                    config = TabletCoordinateConfig(),
                    options = mappingOptions.copy(mode = com.example.wacommapper.mapping.MappingMode.FULL_TABLET),
                )
            }.getOrNull()
        } else null
        if (mapped == null) {
            liveMetricRejected.incrementAndGet()
            return
        }
        val mappedAtNanos = SystemClock.elapsedRealtimeNanos()
        val outputEvent = MappedStylusEvent(
            timestampMillis = event.timestampMillis,
            x = mapped.screenX.toFloat().coerceIn(0f, (info.logicalWidth - 1).coerceAtLeast(0).toFloat()),
            y = mapped.screenY.toFloat().coerceIn(0f, (info.logicalHeight - 1).coerceAtLeast(0).toFloat()),
            pressure = event.pressureNormalized.coerceIn(0f, 1f),
            tip = state.contact,
            sideButton = event.sideButton1,
            inRange = state.present,
            eventType = event.type,
            sideButton2 = event.sideButton2,
            displayId = info.displayId,
            inputElapsedNanos = usbElapsedNanos,
        )
        val cursorState = HoverCursorStateReducer.onEvent(
            current = hoverCursorVisualState,
            event = outputEvent,
            sessionActive = liveGlobalStylusActive,
        )
        hoverCursorVisualState = cursorState
        if (showHoverCursor && overlayPermissionGranted && cursorState.visible && liveGlobalStylusActive) {
            HoverCursorOverlay.show(this, outputEvent.x, outputEvent.y, mappedAtNanos)
        } else {
            HoverCursorOverlay.hide()
        }
        InternalCanvasBackend.send(outputEvent)
        usbToMapperLatency.add(mappedAtNanos - usbElapsedNanos)
        liveMetricMapped.incrementAndGet()
        liveMetricSubmitted.incrementAndGet()
        val output = try {
            shizukuInputBackend.send(outputEvent)
        } catch (throwable: Throwable) {
            Log.e("STYLUS_GLOBAL", "Output backend threw; request emergency release", throwable)
            shizukuInputBackend.releaseAllInput()
        }
        val injectedAtNanos = SystemClock.elapsedRealtimeNanos()
        val mapperToInject = mapperToInjectLatency.add(injectedAtNanos - mappedAtNanos)
        val fullLatency = usbToInjectLatency.add(injectedAtNanos - usbElapsedNanos)
        liveMetricInjected.addAndGet(output.injectedEvents.toLong())
        liveMetricRejected.addAndGet(output.rejectedEvents.toLong())
        liveMetricLatencySum.addAndGet(((injectedAtNanos - usbElapsedNanos).coerceAtLeast(0L) / 1_000_000L))
        liveMetricLatencySamples.incrementAndGet()
        liveMetricMaxLatency.updateAndGet { maxOf(it, ((injectedAtNanos - usbElapsedNanos).coerceAtLeast(0L) / 1_000_000L)) }
        liveLastMethod = output.lastMethod
        liveLastResult = output.lastResult
        liveLastException = output.lastException
        val isContinuousMove = event.type == StylusEventType.MOVE ||
            event.type == StylusEventType.HOVER_MOVE
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousLogElapsed = lastGlobalStylusMoveLogElapsed.get()
        val shouldLogEvent = !isContinuousMove ||
            (nowElapsed - previousLogElapsed >= STYLUS_STATE_LOG_INTERVAL_MS &&
                lastGlobalStylusMoveLogElapsed.compareAndSet(previousLogElapsed, nowElapsed))
        if (shouldLogEvent || output.lastResult != true || output.lastException != null) {
            Log.i(
                "STYLUS_EVENT",
                "t_usb=$usbElapsedNanos t_parsed=$parsedAtNanos t_mapped=$mappedAtNanos t_injected=$injectedAtNanos " +
                    "usbToMapper=${usbToMapperLatency.summary().averageMillis}ms mapperToInject=${mapperToInject.averageMillis}ms " +
                    "usbToInject=${fullLatency.averageMillis}ms event=${event.type} rawX=$rawX rawY=$rawY " +
                    "normalizedX=${mapped.screenNormX} normalizedY=${mapped.screenNormY} screenX=${outputEvent.x} screenY=${outputEvent.y} " +
                    "pressure=${outputEvent.pressure} button1=${outputEvent.sideButton} button2=${outputEvent.sideButton2} " +
                    "tabletRotation=${mappingOptions.tabletRotation} logical=${info.logicalWidth}x${info.logicalHeight} " +
                    "accepted=${output.lastResult} exception=${output.lastException ?: "none"}",
            )
        }
        val usbMapStats = usbToMapperLatency.summary()
        val outputStats = mapperToInjectLatency.summary()
        val totalStats = usbToInjectLatency.summary()
        runOnUiThread {
            liveWacomMetrics = liveWacomMetrics.copy(
                eventsReceived = liveGlobalEventsReceived.get(),
                eventsSubmitted = liveMetricSubmitted.get(),
                injectedEvents = liveMetricInjected.get(),
                rejectedEvents = liveMetricRejected.get(),
                usbToMapperAverageMillis = usbMapStats.averageMillis,
                usbToMapperP50Millis = usbMapStats.p50Millis,
                usbToMapperP95Millis = usbMapStats.p95Millis,
                usbToMapperMaxMillis = usbMapStats.maxMillis,
                mapperToInjectAverageMillis = outputStats.averageMillis,
                mapperToInjectP50Millis = outputStats.p50Millis,
                mapperToInjectP95Millis = outputStats.p95Millis,
                mapperToInjectMaxMillis = outputStats.maxMillis,
                usbToInjectAverageMillis = totalStats.averageMillis,
                usbToInjectP50Millis = totalStats.p50Millis,
                usbToInjectP95Millis = totalStats.p95Millis,
                usbToInjectMaxMillis = totalStats.maxMillis,
                lastMethod = output.lastMethod,
                lastResult = output.lastResult,
                lastException = output.lastException,
            )
        }
    }

    private fun appendCanvasPoint(
        points: MutableList<StylusCanvasPoint>,
        state: com.example.wacommapper.mapping.StylusState,
        strokeId: Long,
    ) {
        val x = state.xRaw ?: return
        val y = state.yRaw ?: return
        val normalized = CoordinateMapper.mapToCanvas(x, y, 1f, 1f)
        points += StylusCanvasPoint(normalized.first, normalized.second, state.pressureNormalized, strokeId)
        if (points.size > MAX_STYLUS_CANVAS_POINTS) points.removeAt(0)
    }

    private fun logStylusEvent(
        type: StylusEventType,
        event: com.example.wacommapper.mapping.StylusInternalEvent,
        report: com.example.wacommapper.usb.Ctl472Report,
    ) {
        if (type == StylusEventType.MOVE || type == StylusEventType.HOVER_MOVE) {
            val now = SystemClock.elapsedRealtime()
            val previous = lastStylusMoveLogElapsed.get()
            if (now - previous < STYLUS_STATE_LOG_INTERVAL_MS || !lastStylusMoveLogElapsed.compareAndSet(previous, now)) return
        }
        Log.i(
            "STYLUS_EVENT",
            "timestamp=${event.timestampMillis} reportType=${report.type} state=${event.fromState}->${event.toState} event=$type " +
                "rawX=${event.xRaw} rawY=${event.yRaw} xNorm=${event.normalizedX} yNorm=${event.normalizedY} " +
                "pressureRaw=${event.pressureRaw} pressureNorm=${event.pressureNormalized} " +
                "button1=${report.sideButton1} button2=${report.sideButton2} hoverDistance=${report.hoverDistance} statusBit0=${report.statusBit0}",
        )
    }

    private fun updateTipThresholds(down: Int, up: Int) {
        if (down !in 1..com.example.wacommapper.usb.Ctl472Report.MAX_PRESSURE || up !in 0 until down) return
        tipDownThreshold = down
        tipUpThreshold = up
        if (::preferences.isInitialized) {
            preferences.tipDownThreshold = down
            preferences.tipUpThreshold = up
        }
        productService?.updatePressureThresholds(down, up)
        stylusStateMapper.setThresholds(down, up)
        liveStylusEventFrame = liveStylusEventFrame.copy(tipDownThreshold = down, tipUpThreshold = up)
    }

    private fun publishLiveMetrics(force: Boolean = false) {
        val elapsedNow = SystemClock.elapsedRealtime()
        val previousPublish = liveMetricLastPublish.get()
        if (force) {
            liveMetricLastPublish.set(elapsedNow)
        } else {
            if (elapsedNow - previousPublish < 100L) return
            if (!liveMetricLastPublish.compareAndSet(previousPublish, elapsedNow)) return
        }
        val snapshot = buildLiveMetricsSnapshot()
        runOnUiThread { liveWacomMetrics = snapshot }
    }

    private fun buildLiveMetricsSnapshot(): LiveWacomMetrics {
        val elapsedNow = SystemClock.elapsedRealtime()
        val elapsedSeconds = ((elapsedNow - liveMetricStartElapsed).coerceAtLeast(1L)) / 1_000f
        val latencySamples = liveMetricLatencySamples.get()
        return liveWacomMetrics.copy(
            hidReports = liveMetricHid.get(),
            tabletReports = liveMetricTabletReports.get(),
            outOfRangeReports = liveMetricOutOfRangeReports.get(),
            genericReports = liveMetricGenericReports.get(),
            invalidReports = liveMetricInvalidReports.get(),
            pressureMinRaw = liveMetricPressureMin.get().takeIf { it != Long.MAX_VALUE }?.toInt(),
            pressureMaxRaw = liveMetricPressureMax.get().takeIf { liveMetricTabletReports.get() > 0L }?.toInt(),
            parsedEvents = liveMetricParsed.get(),
            mappedEvents = liveMetricMapped.get(),
            eventsSubmitted = liveMetricSubmitted.get(),
            injectedEvents = liveMetricInjected.get(),
            rejectedEvents = liveMetricRejected.get(),
            hoverEnterEvents = liveMetricHoverEnter.get(),
            hoverMoveEvents = liveMetricHoverMove.get(),
            hoverExitEvents = liveMetricHoverExit.get(),
            downEvents = liveMetricDown.get(),
            moveEvents = liveMetricMove.get(),
            upEvents = liveMetricUp.get(),
            xValueChanges = liveMetricXChanges.get(),
            yValueChanges = liveMetricYChanges.get(),
            inputReportsPerSecond = liveMetricHid.get() / elapsedSeconds,
            outputEventsPerSecond = liveMetricInjected.get() / elapsedSeconds,
            averageLatencyMillis = if (latencySamples == 0L) 0f else liveMetricLatencySum.get() / latencySamples.toFloat(),
            maxLatencyMillis = liveMetricMaxLatency.get(),
            lastMethod = liveLastMethod,
            lastResult = liveLastResult,
            lastException = liveLastException,
            usbTransfersAttempted = usbSessionManager.counters().attempted,
            usbTransfersSuccessful = usbSessionManager.counters().successful,
            usbBytesTransferred = usbSessionManager.counters().bytesTransferred,
            usbTimeouts = usbSessionManager.counters().timeouts,
            usbErrors = usbSessionManager.counters().usbErrors,
            ignoredPackets = liveMetricIgnored.get(),
            tenByteReports = liveMetricTenByte.get(),
            parseErrors = liveMetricParseErrors.get(),
            pipelineErrors = liveMetricPipelineErrors.get(),
            actualDurationMillis = if (liveAcquisitionActive && liveSessionStartedElapsed > 0L) {
                (SystemClock.elapsedRealtime() - liveSessionStartedElapsed).coerceAtLeast(0L)
            } else liveWacomMetrics.actualDurationMillis,
            lastRawAgeMillis = liveLastRawTimestamp.takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) },
        )
    }

    private fun refreshLiveSnapshot() {
        val display = displayInfo ?: return
        liveSnapshot = LiveMappingSnapshot(display, mappingOptions, parserEvidence)
    }

    private fun confirmedFlag(
        report: com.example.wacommapper.usb.ParsedCtl472Report,
        status: com.example.wacommapper.usb.HypothesisStatus,
        candidate: String?,
    ): Boolean? {
        if (status != com.example.wacommapper.usb.HypothesisStatus.CONFIRMED) return null
        val match = Regex("byte (\\d+), bit (\\d+)").find(candidate.orEmpty()) ?: return null
        val byte = match.groupValues[1].toIntOrNull() ?: return null
        val bit = match.groupValues[2].toIntOrNull() ?: return null
        return report.flagBytes[byte]?.let { ((it shr bit) and 1) == 1 }
    }

    private data class LiveMappingSnapshot(
        val displayInfo: AndroidDisplayInfo,
        val mappingOptions: MappingOptions,
        val evidence: com.example.wacommapper.usb.Ctl472ParserEvidence,
    )

    companion object {
        private const val MAX_RECENT_REPORTS = 50
        private const val LIVE_WACOM_DURATION_SECONDS = 10
        private const val LIVE_WACOM_DURATION_MS = LIVE_WACOM_DURATION_SECONDS * 1_000L
        private const val STYLUS_STATE_LOG_INTERVAL_MS = 100L
        private const val MAX_STYLUS_CANVAS_POINTS = 8_000
        private const val NATIVE_EVENT_RECENCY_MS = 1_200L
    }

    private val liveUnknownFlagLogCounter = AtomicLong(0L)
    private val liveOutputWarningCounter = AtomicLong(0L)
    private val liveMetricHid = AtomicLong(0L)
    private val liveMetricTenByte = AtomicLong(0L)
    private val liveMetricParseErrors = AtomicLong(0L)
    private val liveMetricIgnored = AtomicLong(0L)
    private val liveMetricParsed = AtomicLong(0L)
    private val liveMetricMapped = AtomicLong(0L)
    private val liveMetricSubmitted = AtomicLong(0L)
    private val liveMetricInjected = AtomicLong(0L)
    private val liveMetricRejected = AtomicLong(0L)
    private val liveMetricHoverEnter = AtomicLong(0L)
    private val liveMetricHoverMove = AtomicLong(0L)
    private val liveMetricHoverExit = AtomicLong(0L)
    private val liveMetricDown = AtomicLong(0L)
    private val liveMetricMove = AtomicLong(0L)
    private val liveMetricUp = AtomicLong(0L)
    private val liveMetricLatencySum = AtomicLong(0L)
    private val liveMetricLatencySamples = AtomicLong(0L)
    private val liveMetricMaxLatency = AtomicLong(0L)
    private val liveMetricLastPublish = AtomicLong(0L)
    private val liveMetricXChanges = AtomicLong(0L)
    private val liveMetricYChanges = AtomicLong(0L)
    private val lastStylusStateLogElapsed = AtomicLong(0L)
    @Volatile private var liveMetricStartElapsed = 0L
    @Volatile private var lastLiveParsedX = -1
    @Volatile private var lastLiveParsedY = -1
    @Volatile private var liveLastMethod: String? = null
    @Volatile private var liveLastResult: Boolean? = null
    @Volatile private var liveLastException: String? = null
    @Volatile private var liveTapDownAccepted = false
}
