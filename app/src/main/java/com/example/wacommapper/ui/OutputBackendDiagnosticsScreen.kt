package com.example.wacommapper.ui

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wacommapper.display.AndroidDisplayInfo
import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.GeometryPattern
import com.example.wacommapper.mapping.GlobalGeometryDiagnostic
import com.example.wacommapper.mapping.MappingMode
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.TabletCoordinateConfig
import com.example.wacommapper.output.MappedStylusEvent
import com.example.wacommapper.output.HoverCursorOverlay
import com.example.wacommapper.output.LiveWacomMetrics
import com.example.wacommapper.output.LiveParsedTestSummary
import com.example.wacommapper.output.LiveStopReason
import com.example.wacommapper.output.UsbOpenOnlyDiagnostic
import com.example.wacommapper.output.LegacyHidDiagnostic
import com.example.wacommapper.output.OutputBackendKind
import com.example.wacommapper.output.OutputSupport
import com.example.wacommapper.output.ShizukuBackendState
import com.example.wacommapper.output.ShizukuBackendStatus
import com.example.wacommapper.output.ShizukuInjectionAttempt
import com.example.wacommapper.output.ShizukuMotionProbe
import com.example.wacommapper.output.ShizukuTapDiagnostic
import com.example.wacommapper.usb.Ctl472ParserEvidence
import com.example.wacommapper.usb.HypothesisStatus
import com.example.wacommapper.mapping.LiveStylusEventFrame
import com.example.wacommapper.mapping.StylusEventType
import com.example.wacommapper.mapping.StylusPresenceState
import com.example.wacommapper.usb.ParsedCtl472Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.text.DateFormat
import java.util.Date

@Composable
fun OutputBackendDiagnosticsScreen(
    displayInfo: AndroidDisplayInfo,
    parsed: ParsedCtl472Report?,
    reportTimestampMillis: Long?,
    parserEvidence: Ctl472ParserEvidence,
    mappingOptions: MappingOptions,
    shizukuState: ShizukuBackendState,
    overlayPermissionGranted: Boolean,
    showHoverCursor: Boolean,
    onShowHoverCursorChanged: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    captureInterfaceId: Int?,
    captureEndpoint: Int?,
    captureConnectionOpen: Boolean,
    captureTotalReports: Long,
    captureReportsPerSecond: Int,
    liveWacomSecondsRemaining: Int,
    liveWacomMetrics: LiveWacomMetrics,
    liveHidOnlyPoint: Pair<Float, Float>?,
    liveStylusEventFrame: LiveStylusEventFrame,
    tipDownThreshold: Int,
    tipUpThreshold: Int,
    onTipThresholdsChanged: (Int, Int) -> Unit,
    openUsbOnlyDiagnostic: UsbOpenOnlyDiagnostic,
    legacyHidDiagnostic: LegacyHidDiagnostic,
    nativeStylusRecentlyActive: Boolean,
    inputDeviceSummary: String,
    injectedStylusDispatchCount: Long,
    lastInjectedStylusDispatch: String,
    externalTapRecord: ShizukuTapDiagnostic?,
    externalTapCountdown: Int,
    externalTapRunning: Boolean,
    onRefreshShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onConnectShizuku: () -> Unit,
    onInjectProbe: (String, ShizukuMotionProbe) -> ShizukuInjectionAttempt,
    onExternalTapClick: () -> Unit,
    onEmergencyStop: (Float, Float) -> ShizukuInjectionAttempt,
    onFollowTestCompleted: (Boolean) -> Unit,
    onPrepareLiveWacom: () -> Boolean,
    onStartLiveWacom: suspend (globalValidation: Boolean) -> Boolean,
    onStartLiveHidOnly: suspend () -> Boolean,
    onLiveButtonPressed: (Boolean, Boolean, Boolean, String) -> Unit,
    rawLoggingEnabled: Boolean,
    onRawLoggingChanged: (Boolean) -> Unit,
    onTestOpenUsbOnly: suspend () -> Unit,
    onTestLegacyHidReader: suspend () -> Unit,
    onStopLiveWacom: (LiveStopReason) -> Unit,
    onAwaitLiveCompletion: suspend () -> LiveWacomMetrics,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val attempts = remember { mutableStateListOf<ShizukuInjectionAttempt>() }
    val capabilities = remember { mutableStateMapOf<String, OutputSupport>() }
    val stageConfirmed = remember { mutableStateMapOf("TEST EXTERNAL APP" to true) }
    val stageCompleted = remember { mutableStateMapOf<String, Boolean>() }
    var selectedBackend by remember { mutableStateOf(OutputBackendKind.SHIZUKU) }
    var tapsReceived by remember { mutableIntStateOf(0) }
    var tapZoneCenterOnScreen by remember { mutableStateOf<Offset?>(null) }
    var tapTestRunning by remember { mutableStateOf(false) }
    var tapTestCountdown by remember { mutableIntStateOf(0) }
    var tapDiagnosticRecord by remember { mutableStateOf<ShizukuTapDiagnostic?>(null) }
    var followConfirmed by remember { mutableStateOf(false) }
    var followObserved by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var followRunning by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var followPoint by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var lastStatusMessage by remember { mutableStateOf("No test executed.") }
    var liveTestMessage by remember { mutableStateOf("Sin ejecutar. Inicia GLOBAL VALIDATION, cambia a la app de destino y pulsa STOP al terminar.") }
    var liveGlobalRunning by remember { mutableStateOf(false) }
    var doubleInputObserved by remember { mutableStateOf(false) }
    var parsedTestResult by remember { mutableStateOf<String?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var openOnlyRunning by remember { mutableStateOf(false) }
    var legacyReaderJob by remember { mutableStateOf<Job?>(null) }
    var openOnlyJob by remember { mutableStateOf<Job?>(null) }

    val mapped = parsed?.let {
        CoordinateMapper.map(
            xRaw = it.candidateX.toDouble(),
            yRaw = it.candidateY.toDouble(),
            usableWidth = displayInfo.usableWidth,
            usableHeight = displayInfo.usableHeight,
            config = TabletCoordinateConfig(),
            options = mappingOptions,
        )
    }
    val physicalReport = remember(parsed?.raw) {
        parsed?.raw?.let { com.example.wacommapper.usb.Ctl472RawReportParser().parse(it) }
    }
    val event = parsed?.let { report ->
        MappedStylusEvent(
            timestampMillis = reportTimestampMillis ?: 0L,
            x = mapped?.screenX?.toFloat() ?: 0f,
            y = mapped?.screenY?.toFloat() ?: 0f,
            pressure = CoordinateMapper.normalizePressure(report.candidatePressure).toFloat(),
            tip = confirmedBit(report, parserEvidence.tip.status, parserEvidence.tip.candidate),
            sideButton = confirmedBit(report, parserEvidence.sideButton.status, parserEvidence.sideButton.candidate),
            inRange = parserEvidence.possibleProximityBits.firstOrNull()?.let { (byte, bit) ->
                ((report.flagBytes[byte] ?: 0) shr bit and 1) == 1
            },
        )
    }
    val canTest = selectedBackend == OutputBackendKind.SHIZUKU && stageConfirmed["TEST EXTERNAL APP"] == true &&
        shizukuState.status == ShizukuBackendStatus.READY && shizukuState.userServiceConnected && !running
    val outputStatus = when (selectedBackend) {
        OutputBackendKind.NONE, OutputBackendKind.UINPUT -> OutputSupport.UNSUPPORTED
        OutputBackendKind.ACCESSIBILITY -> OutputSupport.PARTIAL
        OutputBackendKind.SHIZUKU -> if (shizukuState.status == ShizukuBackendStatus.READY) OutputSupport.PARTIAL else OutputSupport.UNSUPPORTED
    }

    fun addAttempt(attempt: ShizukuInjectionAttempt) {
        attempts.add(attempt)
        while (attempts.size > MAX_VISIBLE_ATTEMPTS) attempts.removeAt(0)
    }

    fun runTapDiagnostic(testName: String, pointOnScreen: Offset, secondsBeforeTap: Int) {
        if (tapTestRunning || shizukuState.status != ShizukuBackendStatus.READY || !shizukuState.userServiceConnected) return
        scope.launch {
            tapTestRunning = true
            try {
                for (value in secondsBeforeTap downTo 1) {
                    tapTestCountdown = value
                    delay(1_000)
                }
                tapTestCountdown = 0
                val display = activity?.display ?: activity?.windowManager?.defaultDisplay
                val displayId = displayInfo.displayId
                val displayWidth = displayInfo.displayWidth
                val displayHeight = displayInfo.displayHeight
                val downTime = SystemClock.uptimeMillis()
                val eventTimeDown = downTime
                val source = InputDevice.SOURCE_TOUCHSCREEN
                val toolType = MotionEvent.TOOL_TYPE_FINGER
                tapDiagnosticRecord = ShizukuTapDiagnostic(
                    testName = testName,
                    displayId = displayId,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    displayRotationDegrees = (display?.rotation ?: 0) * 90,
                    configurationOrientation = displayInfo.configurationOrientation,
                    mappingOrientation = mappingOptions.tabletRotation.name,
                    x = pointOnScreen.x,
                    y = pointOnScreen.y,
                    downTimeMillis = downTime,
                    downEventTimeMillis = eventTimeDown,
                    upEventTimeMillis = null,
                    source = source,
                    toolType = toolType,
                )
                val down = probe(
                    MotionEvent.ACTION_DOWN, pointOnScreen.x, pointOnScreen.y, 1f,
                    source, toolType, 0, 0, downTime, eventTimeDown, displayId,
                )
                val downAttempt = withContext(Dispatchers.IO) { onInjectProbe("$testName DOWN", down) }
                addAttempt(downAttempt)
                tapDiagnosticRecord = tapDiagnosticRecord?.copy(downAttempt = downAttempt)

                delay(TAP_HOLD_MS)
                val eventTimeUp = SystemClock.uptimeMillis()
                tapDiagnosticRecord = tapDiagnosticRecord?.copy(upEventTimeMillis = eventTimeUp)
                val up = probe(
                    MotionEvent.ACTION_UP, pointOnScreen.x, pointOnScreen.y, 0f,
                    source, toolType, 0, 0, downTime, eventTimeUp, displayId,
                )
                val upAttempt = withContext(Dispatchers.IO) { onInjectProbe("$testName UP", up) }
                addAttempt(upAttempt)
                tapDiagnosticRecord = tapDiagnosticRecord?.copy(upAttempt = upAttempt)
            } catch (exception: Exception) {
                lastStatusMessage = "$testName failed: ${exception.javaClass.name}: ${exception.message}"
            } finally {
                tapTestCountdown = 0
                tapTestRunning = false
            }
        }
    }

    fun setCapability(name: String, results: List<ShizukuInjectionAttempt>) {
        val accepted = results.count { it.returned == true }
        capabilities[name] = when {
            results.isEmpty() -> OutputSupport.UNSUPPORTED
            accepted == results.size -> OutputSupport.SUPPORTED
            accepted > 0 -> OutputSupport.PARTIAL
            else -> OutputSupport.UNSUPPORTED
        }
    }

    fun runManualTest(
        name: String,
        capability: String,
        previousStage: String,
        buildProbes: (Long, Float, Float) -> List<ShizukuMotionProbe>,
    ) {
        if (!canTest || stageConfirmed[previousStage] != true || running) return
        testJob?.cancel()
        testJob = scope.launch {
            running = true
            try {
                for (value in 3 downTo 1) {
                    countdown = value
                    delay(1_000)
                }
                countdown = null
                val centerX = displayInfo.displayWidth / 2f
                val centerY = displayInfo.displayHeight / 2f
                val start = SystemClock.uptimeMillis()
                val probes = buildProbes(start, centerX, centerY).map { it.copy(displayId = displayInfo.displayId) }
                val results = mutableListOf<ShizukuInjectionAttempt>()
                probes.forEachIndexed { index, probe ->
                    if (!running) return@forEachIndexed
                    val attempt = withContext(Dispatchers.IO) { onInjectProbe(name, probe) }
                    results += attempt
                    addAttempt(attempt)
                    if (index != probes.lastIndex) delay(EVENT_GAP_MS)
                }
                setCapability(capability, results)
                stageCompleted[name] = results.isNotEmpty()
                lastStatusMessage = "$name: ${results.count { it.returned == true && it.exception == null }}/${results.size} injectInputEvent calls returned true. Visual validation is separate; inspect failed calls and do not confirm unless the target app showed the requested behavior."
            } catch (exception: Exception) {
                lastStatusMessage = "$name interrupted: ${exception.javaClass.simpleName}: ${exception.message}"
            } finally {
                countdown = null
                running = false
                testJob = null
            }
        }
    }

    fun stopAllTests() {
        testJob?.cancel()
        testJob = null
        running = false
        followRunning = false
        countdown = null
        onStopLiveWacom(LiveStopReason.USER_STOP)
        val releaseAlreadyHandled = liveGlobalRunning
        liveGlobalRunning = false
        val x = displayInfo.windowWidth / 2f
        val y = displayInfo.windowHeight / 2f
        if (!releaseAlreadyHandled) {
            scope.launch {
                val attempt = withContext(Dispatchers.IO) { onEmergencyStop(x, y) }
                addAttempt(attempt)
                lastStatusMessage = "Emergency STOP sent release/cancel attempts."
            }
        }
    }

    fun launchLiveTest(
        hidOnly: Boolean,
        parsedStylusTest: Boolean = false,
        stylusEventTest: Boolean = false,
        globalStylusTest: Boolean = false,
    ) {
        val parentJob = scope.coroutineContext[Job]
        val previousJob = testJob
        onLiveButtonPressed(
            hidOnly,
            parsedStylusTest,
            stylusEventTest,
            "parentJob active=${parentJob?.isActive} cancelled=${parentJob?.isCancelled}; " +
                "previousLiveJob active=${previousJob?.isActive} cancelled=${previousJob?.isCancelled}",
        )
        if (parsedStylusTest) parsedTestResult = "RUNNING — mueve el stylus en diagonal durante los 10 segundos."
        if (!hidOnly && !onPrepareLiveWacom()) {
            liveTestMessage = "No iniciado: Shizuku debe estar READY y el servicio temporal debe poder arrancar."
            liveGlobalRunning = false
            onStopLiveWacom(LiveStopReason.USER_STOP)
            return
        }
        testJob?.cancel()
        testJob = scope.launch {
            running = true
            liveGlobalRunning = globalStylusTest
            var liveStarted = false
            try {
                if (!hidOnly) {
                    liveTestMessage = if (globalStylusTest) {
                        "GLOBAL VALIDATION: cambia a la app destino al iniciar; la sesión permanece activa hasta STOP."
                    } else {
                        "Cambia a la app destino; después se abrirá una sesión USB limpia y se comprobarán los reportes antes de inyectar."
                    }
                    for (value in 3 downTo 1) { countdown = value; delay(1_000) }
                    countdown = null
                }
                val started = if (hidOnly) onStartLiveHidOnly() else onStartLiveWacom(globalStylusTest)
                if (!started) {
                    liveTestMessage = liveWacomMetrics.liveError ?: "Falló la adquisición USB. Revisa estado y contadores."
                    return@launch
                }
                liveTestMessage = when {
                    parsedStylusTest -> "LIVE PARSED activo: análisis local; no usa Shizuku ni envía eventos."
                    globalStylusTest -> "GLOBAL VALIDATION activa: USB → parser → mapper → Shizuku; vuelve aquí y pulsa STOP para cerrar."
                    stylusEventTest -> "LIVE STYLUS EVENT TEST activo: eventos internos y canvas local; no inyecta Android."
                    hidOnly -> "HID ONLY activo: mueve el stylus; el punto debe seguirlo. Sin Shizuku."
                    else -> "Salida activa: mueve el stylus en la app destino durante 10 segundos."
                }
                liveStarted = true
                val finalMetrics = onAwaitLiveCompletion()
                liveTestMessage = when {
                    parsedStylusTest -> {
                        val metrics = finalMetrics
                        val summary = LiveParsedTestSummary.from(metrics)
                        parsedTestResult = if (summary.passed) {
                            "PASS — TABLET_REPORT=${summary.tabletReports}; X changes=${summary.xChanges}; Y changes=${summary.yChanges}; USB errors=${summary.usbErrors}. " +
                                "OUT_OF_RANGE=${metrics.outOfRangeReports}; GENERIC=${metrics.genericReports}; INVALID=${metrics.invalidReports}; pressure=${metrics.pressureMinRaw ?: "—"}..${metrics.pressureMaxRaw ?: "—"}."
                        } else {
                            "INCOMPLETE — TABLET_REPORT=${summary.tabletReports}, X=${summary.xChanges}, Y=${summary.yChanges}, USB errors=${summary.usbErrors}. " +
                                "OUT_OF_RANGE=${metrics.outOfRangeReports}; GENERIC=${metrics.genericReports}; INVALID=${metrics.invalidReports}. Repite moviendo en diagonal."
                        }
                        "LIVE PARSED STYLUS TEST finalizado: $parsedTestResult"
                    }
                    globalStylusTest -> "GLOBAL VALIDATION finalizada; revisa aceptaciones, latencia y posible doble entrada."
                    stylusEventTest -> "LIVE STYLUS EVENT TEST finalizado; revisa estado, eventos y canvas."
                    hidOnly -> "LIVE HID ONLY terminó; revisa el punto y los contadores."
                    else -> "LIVE WACOM terminó; revisa los contadores de cada etapa."
                }
            } finally {
                val wasCancelled = !currentCoroutineContext().isActive
                if (!liveStarted || wasCancelled) {
                    onStopLiveWacom(if (wasCancelled) LiveStopReason.COROUTINE_CANCELLED else LiveStopReason.START_EXCEPTION)
                }
                running = false
                liveGlobalRunning = false
                countdown = null
                testJob = null
            }
        }
    }

    if (followRunning) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF101820)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("FOLLOW TEST — 5 seconds", color = Color.White)
            Text("Synthetic stylus trajectory is being injected; press STOP to abort.", color = Color.White)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                drawRect(Color(0xFF263238))
                drawCircle(Color(0xFF80CBC4), radius = 12.dp.toPx(), center = Offset(followPoint.x * size.width, followPoint.y * size.height))
            }
            Button(onClick = ::stopAllTests) { Text("STOP") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Shizuku Backend — experimental global input")
        Text("La inyección global está desactivada hasta START. GLOBAL VALIDATION continúa al cambiar a otra app y termina con STOP; Android puede finalizarla si destruye el proceso.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Shizuku installed: ${shizukuState.installed}")
                Text("Binder available: ${shizukuState.binderAvailable}")
                Text("Permission granted (WacomMapper): ${shizukuState.permissionGranted}")
                Text("INJECT_EVENTS granted: ${shizukuState.systemPermissionGranted}")
                Text("Running UID: ${shizukuState.runningUid ?: "—"} (ADB shell expected: 2000; root is rejected)")
                Text("UserService connected: ${shizukuState.userServiceConnected}; service UID: ${shizukuState.userServiceUid ?: "—"}")
                Text("Backend status: ${shizukuState.status}")
                Text(shizukuState.detail)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefreshShizuku) { Text("Refresh") }
                    if (shizukuState.status == ShizukuBackendStatus.PERMISSION_REQUIRED) {
                        Button(onClick = onRequestShizukuPermission) { Text("Grant permission") }
                    }
                    if (shizukuState.status == ShizukuBackendStatus.READY && !shizukuState.userServiceConnected) {
                        Button(onClick = onConnectShizuku) { Text("Connect backend") }
                    }
                }
            }
        }
        Text("Select backend")
        OutputBackendKind.entries.forEach { backend ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedBackend == backend,
                    onClick = {
                        if (liveGlobalRunning && selectedBackend != backend) stopAllTests()
                        selectedBackend = backend
                    },
                )
                Text(backend.name)
            }
        }
        Text("Backend status: $outputStatus")
        if (selectedBackend != OutputBackendKind.SHIZUKU) {
            Text("Only Shizuku is active in this experimental screen. Other choices are capability labels, not implementations.")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("GLOBAL VALIDATION — CTL-472 → Shizuku → InputManager")
                Text("Estado: ${if (liveGlobalRunning) "RUNNING" else liveWacomMetrics.liveState}; fuente=SOURCE_STYLUS; tool=TOOL_TYPE_STYLUS")
                Text("GLOBAL VALIDATION: la sesión permanece activa hasta STOP y está pensada para cambiar a una app externa.")
                Text("Prueba en orden: 1) Samsung Notes  2) ibisPaint/Krita si está disponible  3) navegador/Android. Revisa posición, hover, contacto, presión, side button, estabilidad (30–60 s) y doble trazo.")
                Text("Duración: ${liveWacomMetrics.actualDurationMillis / 1_000f}s; estado=${if (liveGlobalRunning) "ACTIVA HASTA STOP" else "DETENIDA"}; stop=${liveWacomMetrics.stopReason ?: "—"}")
                Text("Mapping configurado: ${mappingOptions.mode}; tablet rotation=${mappingOptions.tabletRotation}. Objetivo: LANDSCAPE / FULL_TABLET / ROTATION_0.")
                Text("OVERLAY PERMISSION: ${if (overlayPermissionGranted) "GRANTED" else "REQUIRED"}; el overlay solo dibuja y no recibe toques.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SHOW HOVER CURSOR", modifier = Modifier.weight(1f))
                    Switch(checked = showHoverCursor, onCheckedChange = onShowHoverCursorChanged)
                }
                if (!overlayPermissionGranted) {
                    OutlinedButton(onClick = onRequestOverlayPermission) {
                        Text("Permitir mostrar sobre otras apps")
                    }
                }
                Text("Display ${displayInfo.displayId}: ${displayInfo.displayWidth}×${displayInfo.displayHeight}px; rotaciÃ³n=${displayInfo.rotationDegrees}°; insets=${displayInfo.systemBarsInsets}")
                Text("Coordenadas de inyecciÃ³n usan el display completo. Insets se reportan, no se restan del espacio global.")
                Text("Reader reclamado: interface=${captureInterfaceId ?: "ninguna"}; endpoint=${captureEndpoint?.let { "0x%02X".format(it) } ?: "ninguno"}; abierto=$captureConnectionOpen")
                Button(
                    onClick = { launchLiveTest(hidOnly = false, stylusEventTest = true, globalStylusTest = true) },
                    enabled = shizukuState.status == ShizukuBackendStatus.READY && shizukuState.userServiceConnected &&
                        !running && !openOnlyRunning && !legacyHidDiagnostic.running,
                ) { Text("START GLOBAL INJECTION") }
                Button(
                    onClick = ::stopAllTests,
                    enabled = liveGlobalRunning || running || captureConnectionOpen,
                ) { Text("STOP GLOBAL INJECTION — EMERGENCY RELEASE") }
                Text(liveTestMessage)
                Text("Internal events received=${liveWacomMetrics.eventsReceived}; submitted=${liveWacomMetrics.eventsSubmitted}; accepted=${liveWacomMetrics.injectedEvents}; rejected=${liveWacomMetrics.rejectedEvents}")
                Text("USB reports=${liveWacomMetrics.hidReports} (${liveWacomMetrics.inputReportsPerSecond.format1()}/s); mapped=${liveWacomMetrics.mappedEvents}; HOVER_ENTER=${liveWacomMetrics.hoverEnterEvents}, HOVER_MOVE=${liveWacomMetrics.hoverMoveEvents}, HOVER_EXIT=${liveWacomMetrics.hoverExitEvents}")
                Text("DOWN=${liveWacomMetrics.downEvents}, MOVE=${liveWacomMetrics.moveEvents}, UP=${liveWacomMetrics.upEvents}; OUT_OF_RANGE=${liveWacomMetrics.outOfRangeReports}; injected=${liveWacomMetrics.injectedEvents}; rejected=${liveWacomMetrics.rejectedEvents}")
                Text("Pressure raw=${liveWacomMetrics.lastPressureRaw ?: "—"}/2047; normalized=${liveWacomMetrics.mappedPressure?.format3() ?: "—"}; observed min..max=${liveWacomMetrics.pressureMinRaw ?: "—"}..${liveWacomMetrics.pressureMaxRaw ?: "—"}")
                Text("Raw buttons: sideButton1=${liveWacomMetrics.lastSideButton ?: "—"}; sideButton2=${liveWacomMetrics.lastSideButton2 ?: "—"}")
                Text("Hover cursor map→frame latency: ${HoverCursorOverlay.lastUpdateLatencyMillis()?.format1() ?: "—"} ms")
                Text("Last MotionEvent: ${liveStylusEventFrame.lastEvent?.type ?: "—"}; state=${liveStylusEventFrame.update?.state?.state ?: "OUT_OF_RANGE"}; x=${liveWacomMetrics.mappedX?.format1() ?: "—"}; y=${liveWacomMetrics.mappedY?.format1() ?: "—"}; pressure=${liveWacomMetrics.mappedPressure?.format3() ?: "—"}")
                Text("Button1=${liveStylusEventFrame.update?.state?.sideButton1 ?: false}; Button2=${liveStylusEventFrame.update?.state?.sideButton2 ?: false}; source=SOURCE_STYLUS; tool=TOOL_TYPE_STYLUS; mode=ASYNC")
                Text("Last API: ${liveWacomMetrics.lastMethod ?: "—"}; inject result=${liveWacomMetrics.lastResult ?: "—"}; exception=${liveWacomMetrics.lastException ?: "none"}")
                Text("WacomMapper received injected stylus events: $injectedStylusDispatchCount; last: $lastInjectedStylusDispatch")
                Text("Input→mapper avg/p50/p95/max=${liveWacomMetrics.usbToMapperAverageMillis.format1()}/${liveWacomMetrics.usbToMapperP50Millis.format1()}/${liveWacomMetrics.usbToMapperP95Millis.format1()}/${liveWacomMetrics.usbToMapperMaxMillis.format1()} ms")
                Text("Mapper→inject avg/p50/p95/max=${liveWacomMetrics.mapperToInjectAverageMillis.format1()}/${liveWacomMetrics.mapperToInjectP50Millis.format1()}/${liveWacomMetrics.mapperToInjectP95Millis.format1()}/${liveWacomMetrics.mapperToInjectMaxMillis.format1()} ms")
                Text("Input→inject avg/p50/p95/max=${liveWacomMetrics.usbToInjectAverageMillis.format1()}/${liveWacomMetrics.usbToInjectP50Millis.format1()}/${liveWacomMetrics.usbToInjectP95Millis.format1()}/${liveWacomMetrics.usbToInjectMaxMillis.format1()} ms")
                Text("ASYNC means InputManager accepted/enqueued the event; it is not proof that the foreground app visibly handled it.")
                Text("Relevant InputDevice inventory:\n$inputDeviceSummary")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = doubleInputObserved, onCheckedChange = { doubleInputObserved = it })
                    Text("Vi cursor/evento nativo duplicado además del inyectado")
                }
                Text(
                    if (doubleInputObserved || liveWacomMetrics.possibleDoubleInput)
                        "ADVERTENCIA: posible doble entrada. No avanzar a producto hasta resolver el input nativo."
                    else "Doble entrada requiere observaciÃ³n visual: una app normal no puede monitorizar los eventos globales de otras apps.",
                )
            }
        }

        GlobalGeometryDiagnosticPanel(
            displayInfo = displayInfo,
            mappingOptions = mappingOptions,
            shizukuReady = shizukuState.status == ShizukuBackendStatus.READY && shizukuState.userServiceConnected,
            onInjectProbe = onInjectProbe,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Input — most recent CTL-472 report")
                Text("Report type=${physicalReport?.type ?: "WAITING"}; xRaw=${physicalReport?.x ?: "—"}; yRaw=${physicalReport?.y ?: "—"}")
                Text("pressure=${physicalReport?.pressure ?: "—"} / 2047; normalized=${event?.pressure?.format3() ?: "—"}")
                Text("CONTACT INTERPRETATION: UNKNOWN")
                Text("nearProximity=${physicalReport?.nearProximity ?: "—"}; hoverDistance=${physicalReport?.hoverDistance ?: "—"}; hover=UNKNOWN")
                Text("sideButton1=${physicalReport?.sideButton1 ?: "—"}; sideButton2=${physicalReport?.sideButton2 ?: "—"}; eraser=${physicalReport?.eraser ?: "—"}")
                Text("status=${physicalReport?.statusByte?.let { "0x%02X/%8s".format(it, it.toString(2).padStart(8, '0')) } ?: "—"}; bit0 raw=${physicalReport?.statusBit0 ?: "—"}")
                Text("Mapped screenX=${mapped?.screenX?.format1() ?: "—"}; screenY=${mapped?.screenY?.format1() ?: "—"}")
                Text("Mapping=${mappingOptions.mode}, manual tablet rotation=${mappingOptions.tabletRotation}; usable ${displayInfo.usableWidth}×${displayInfo.usableHeight}px")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Global touchscreen tap diagnostics")
                Text("Target display ${displayInfo.displayId}: Display.getRealSize=${displayInfo.displayWidth} × ${displayInfo.displayHeight}px")
                Text("Display rotation=${displayInfo.rotationDegrees}° from natural orientation; current app configuration=${displayInfo.configurationOrientation}; manual tablet rotation=${mappingOptions.tabletRotation}")
                Text("Android display rotation updates logical display dimensions only; tablet rotation is an independent explicit setting.")
                Text("Taps received: $tapsReceived")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Color(0xFFB2DFDB))
                        .onGloballyPositioned { coordinates ->
                            val localCenter = coordinates.localToWindow(
                                Offset(coordinates.size.width / 2f, coordinates.size.height / 2f),
                            )
                            val screenOrigin = IntArray(2)
                            activity?.window?.decorView?.getLocationOnScreen(screenOrigin)
                            tapZoneCenterOnScreen = activity?.let {
                                Offset(screenOrigin[0] + localCenter.x, screenOrigin[1] + localCenter.y)
                            }
                        }
                        .clickable { tapsReceived++ },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("TAP TARGET — injected finger tap should increment the counter", color = Color(0xFF102A27))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            tapZoneCenterOnScreen?.let { runTapDiagnostic("TEST TAP INSIDE APP", it, 0) }
                        },
                        enabled = !tapTestRunning && !externalTapRunning && tapZoneCenterOnScreen != null && selectedBackend == OutputBackendKind.SHIZUKU &&
                            shizukuState.status == ShizukuBackendStatus.READY && shizukuState.userServiceConnected,
                    ) { Text("TEST TAP INSIDE APP") }
                    Button(
                        onClick = onExternalTapClick,
                        enabled = !tapTestRunning && !externalTapRunning && selectedBackend == OutputBackendKind.SHIZUKU &&
                            shizukuState.status == ShizukuBackendStatus.READY && shizukuState.userServiceConnected,
                    ) { Text("TEST EXTERNAL TAP — 5 SECOND DELAY") }
                }
                if (externalTapCountdown > 0) Text("External tap in $externalTapCountdown")
                if (tapTestCountdown > 0) Text("Tap injection in progress")
                if (tapTestRunning || externalTapRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                (externalTapRecord ?: tapDiagnosticRecord)?.let { record ->
                    Text("Last ${record.testName}")
                    Text("Display ID=${record.displayId}; display=${record.displayWidth}×${record.displayHeight}px")
                    Text("Orientation: rotation=${record.displayRotationDegrees}° from natural orientation; Configuration=${record.configurationOrientation}; mapping label=${record.mappingOrientation}")
                    Text("x=${record.x}; y=${record.y}; source=SOURCE_TOUCHSCREEN (0x${record.source.toString(16)}); toolType=FINGER (${record.toolType})")
                    Text("downTime=${record.downTimeMillis?.let { "$it ms (uptime)" } ?: "pending"}; eventTime DOWN=${record.downEventTimeMillis?.let { "$it ms" } ?: "pending"}; eventTime UP=${record.upEventTimeMillis?.let { "$it ms" } ?: "pending"}")
                    Text("DOWN result=${record.downAttempt?.returned ?: "pending"}; method=${record.downAttempt?.methodApi ?: "pending"}")
                    Text("UP result=${record.upAttempt?.returned ?: "pending"}; method=${record.upAttempt?.methodApi ?: "pending"}")
                    val exceptions = listOfNotNull(
                        record.downAttempt?.exception?.let { "DOWN exception:\n$it" },
                        record.upAttempt?.exception?.let { "UP exception:\n$it" },
                    )
                    Text(if (exceptions.isEmpty()) "Exception: none" else exceptions.joinToString("\n\n"))
                }
            }
        }

        ExperimentStageCard(
            title = "1. TEST STYLUS",
            description = "SOURCE_STYLUS + TOOL_TYPE_STYLUS; ACTION_DOWN / ACTION_UP en el centro del display. Cambia a una app de dibujo durante la cuenta regresiva.",
            enabled = canTest && !stageConfirmed.containsKey("TEST STYLUS"),
            completed = stageCompleted["TEST STYLUS"] == true,
            confirmed = stageConfirmed["TEST STYLUS"] == true,
            attempts = attempts.filter { it.name.startsWith("TEST STYLUS") },
            countdown = countdown,
            onStart = {
                runManualTest("TEST STYLUS", "Stylus tool type", "TEST EXTERNAL APP") { start, x, y ->
                    listOf(
                        probe(MotionEvent.ACTION_DOWN, x, y, 1f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start),
                        probe(MotionEvent.ACTION_UP, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 80),
                    )
                }
            },
            onConfirm = { checked -> stageConfirmed["TEST STYLUS"] = checked },
        )
        ExperimentStageCard(
            title = "2. TEST PRESSURE",
            description = "Secuencia de contacto continua con pressure 0.1, 0.5 y 1.0. Verifica visualmente diferencias en la app destino.",
            enabled = canTest && stageConfirmed["TEST STYLUS"] == true && !stageConfirmed.containsKey("TEST PRESSURE"),
            completed = stageCompleted["TEST PRESSURE"] == true,
            confirmed = stageConfirmed["TEST PRESSURE"] == true,
            attempts = attempts.filter { it.name.startsWith("TEST PRESSURE") },
            countdown = countdown,
            onStart = {
                runManualTest("TEST PRESSURE", "Pressure", "TEST STYLUS") { start, x, y ->
                    listOf(
                        probe(MotionEvent.ACTION_DOWN, x, y, 0.1f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start),
                        probe(MotionEvent.ACTION_MOVE, x, y, 0.5f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 120),
                        probe(MotionEvent.ACTION_MOVE, x, y, 1.0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 240),
                        probe(MotionEvent.ACTION_UP, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 360),
                    )
                }
            },
            onConfirm = { checked -> stageConfirmed["TEST PRESSURE"] = checked },
        )
        ExperimentStageCard(
            title = "3. TEST HOVER",
            description = "ACTION_HOVER_ENTER / MOVE / EXIT con SOURCE_STYLUS y TOOL_TYPE_STYLUS.",
            enabled = canTest && stageConfirmed["TEST PRESSURE"] == true && !stageConfirmed.containsKey("TEST HOVER"),
            completed = stageCompleted["TEST HOVER"] == true,
            confirmed = stageConfirmed["TEST HOVER"] == true,
            attempts = attempts.filter { it.name.startsWith("TEST HOVER") },
            countdown = countdown,
            onStart = {
                runManualTest("TEST HOVER", "Hover", "TEST PRESSURE") { start, x, y ->
                    listOf(
                        probe(MotionEvent.ACTION_HOVER_ENTER, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start),
                        probe(MotionEvent.ACTION_HOVER_MOVE, x + 30f, y + 20f, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 100),
                        probe(MotionEvent.ACTION_HOVER_EXIT, x + 30f, y + 20f, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 200),
                    )
                }
            },
            onConfirm = { checked -> stageConfirmed["TEST HOVER"] = checked },
        )
        ExperimentStageCard(
            title = "4. TEST SIDE BUTTON",
            description = "Prueba el button state de stylus BUTTON_STYLUS_PRIMARY (presionar y soltar) durante hover.",
            enabled = canTest && stageConfirmed["TEST HOVER"] == true && !stageConfirmed.containsKey("TEST SIDE BUTTON"),
            completed = stageCompleted["TEST SIDE BUTTON"] == true,
            confirmed = stageConfirmed["TEST SIDE BUTTON"] == true,
            attempts = attempts.filter { it.name.startsWith("TEST SIDE BUTTON") },
            countdown = countdown,
            onStart = {
                runManualTest("TEST SIDE BUTTON", "Side button", "TEST HOVER") { start, x, y ->
                    val button = MotionEvent.BUTTON_STYLUS_PRIMARY
                    listOf(
                        probe(MotionEvent.ACTION_HOVER_ENTER, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start),
                        probe(MotionEvent.ACTION_BUTTON_PRESS, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, button, button, start, start + 100),
                        probe(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, button, start, start + 200),
                        probe(MotionEvent.ACTION_HOVER_EXIT, x, y, 0f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, start, start + 300),
                    )
                }
            },
            onConfirm = { checked -> stageConfirmed["TEST SIDE BUTTON"] = checked },
        )
        if (countdown != null) Text("Switch to the target app now — test starts in $countdown…")
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(lastStatusMessage)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Capability results")
                listOf("Global touch", "Stylus tool type", "Pressure", "Hover", "Side button").forEach { capability ->
                    val resultKey = when (capability) {
                        "Global touch" -> "Global touch"
                        "Stylus tool type" -> "Stylus tool type"
                        else -> capability
                    }
                    Text("$capability: ${capabilities[resultKey] ?: OutputSupport.UNSUPPORTED}")
                }
                Text("SUPPORT means InputManager accepted the event; verify visually that the target app received the claimed behavior.")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("5. FOLLOW TEST — trayectoria sintética de stylus, 5 segundos")
                Text("Sin datos CTL-472. La trayectoria se envía a otra app; STOP aborta inmediatamente.")
                Button(
                    onClick = {
                        if (!canTest || stageConfirmed["TEST SIDE BUTTON"] != true || stageConfirmed["FOLLOW TEST"] == true) return@Button
                        testJob?.cancel()
                        testJob = scope.launch {
                            running = true
                            try {
                                for (value in 3 downTo 1) { countdown = value; delay(1_000) }
                                countdown = null
                                followRunning = true
                                val success = executeFollowTest(
                                    width = displayInfo.displayWidth.toFloat(),
                                    height = displayInfo.displayHeight.toFloat(),
                                    displayId = displayInfo.displayId,
                                    onPoint = { point -> followPoint = point },
                                    onInject = onInjectProbe,
                                    addAttempt = ::addAttempt,
                                )
                                followConfirmed = success
                                followObserved = false
                                stageCompleted["FOLLOW TEST"] = success
                                lastStatusMessage = if (success) "FOLLOW TEST completed; InputManager accepted the trajectory." else "FOLLOW TEST failed/partial; inspect attempt log."
                            } catch (exception: Exception) {
                                lastStatusMessage = "FOLLOW TEST interrupted: ${exception.javaClass.simpleName}: ${exception.message}"
                            } finally {
                                followRunning = false
                                if (!followConfirmed) onFollowTestCompleted(false)
                                running = false
                                countdown = null
                                testJob = null
                            }
                        }
                    },
                    enabled = canTest && stageConfirmed["TEST SIDE BUTTON"] == true && !stageConfirmed.containsKey("FOLLOW TEST") && !running,
                ) { Text("FOLLOW TEST") }
                if (countdown != null) Text("Switch to target app now — follow starts in $countdown…")
                if (followConfirmed) {
                    Text("InputManager accepted the follow trajectory. Verify that the target app visibly received it.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = followObserved,
                            onCheckedChange = { checked ->
                                followObserved = checked
                                if (checked) stageConfirmed["FOLLOW TEST"] = true else stageConfirmed.remove("FOLLOW TEST")
                                onFollowTestCompleted(checked)
                            },
                            enabled = !followObserved,
                        )
                        Text("Confirmo visualmente la trayectoria en la app destino")
                    }
                    if (followObserved) Text("LIVE WACOM can be tested when Interface 0 / EP 0x81 is actively captured.")
                }
                attempts.filter { it.name.startsWith("FOLLOW_TEST") }.takeLast(20).forEach { attempt ->
                    Text("${attempt.timestamp} | ${attempt.name} | API=${attempt.methodApi} | inject result=${attempt.returned}")
                    Text("${formatProbeDetail(attempt.detail)} | exception=${attempt.exception ?: "none"}")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("LIVE WACOM — adquisición autónoma (máximo 10 segundos)")
                Text("Cierra la captura anterior y abre una sesión exclusiva CTL-472 → Interface 0 → EP 0x81. No depende de USB/RAW ni de otra pantalla.")
                Text("Estado: ${liveWacomMetrics.liveState}")
                val liveLabel = when {
                    running && liveWacomMetrics.unboundedSession -> "GLOBAL VALIDATION — RUNNING (hasta STOP)"
                    running && liveWacomMetrics.hidOnly -> "LIVE HID ONLY — RUNNING"
                    running -> "LIVE WACOM — RUNNING"
                    liveWacomMetrics.stopReason != null -> if (liveWacomMetrics.hidOnly) "LIVE HID ONLY — FINISHED" else "LIVE WACOM — FINISHED"
                    else -> "LIVE — IDLE"
                }
                Text(liveLabel)
                Text(if (liveWacomMetrics.unboundedSession) {
                    "Elapsed: ${liveWacomMetrics.actualDurationMillis / 1_000f} s / sin límite — STOP manual"
                } else {
                    "Elapsed: ${liveWacomMetrics.actualDurationMillis / 1_000f} / ${liveWacomMetrics.requestedDurationMillis / 1_000f} s"
                })
                Text(if (liveWacomMetrics.unboundedSession) {
                    "Requested duration: until STOP | actual: ${liveWacomMetrics.actualDurationMillis} ms"
                } else {
                    "Requested duration: ${liveWacomMetrics.requestedDurationMillis} ms | actual: ${liveWacomMetrics.actualDurationMillis} ms"
                })
                Text("Started at: ${liveWacomMetrics.startedAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"} | stopped at: ${liveWacomMetrics.stoppedAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"}")
                Text("Stop reason: ${liveWacomMetrics.stopReason ?: "—"}")
                liveWacomMetrics.lastReachedStage?.let { Text("Last START TRACE stage: $it") }
                liveWacomMetrics.coroutineDiagnostics?.let { Text("Coroutines: $it") }
                if (liveWacomMetrics.startTrace.isNotEmpty()) {
                    Text("START TRACE")
                    liveWacomMetrics.startTrace.forEach { Text(it) }
                }
                liveWacomMetrics.exceptionClass?.let { Text("Exception: $it: ${liveWacomMetrics.exceptionMessage}") }
                liveWacomMetrics.exceptionCause?.let { Text("Cause: $it") }
                liveWacomMetrics.exceptionStackTrace?.let { trace ->
                    Text("Top stack:\n${trace.lineSequence().take(6).joinToString("\n")}")
                    Text("Full stack trace:\n$trace")
                }
                Text("Device found: ${liveWacomMetrics.usbDeviceFound} | USB permission: ${liveWacomMetrics.usbPermissionGranted}")
                Text("Connection opened: ${liveWacomMetrics.connectionOpened} | requested interface: ${liveWacomMetrics.requestedInterface} | claimed: ${liveWacomMetrics.claimedInterface ?: "failure/—"}")
                Text("Requested endpoint: 0x%02X | active: %s | type: INTERRUPT IN | max packet: %s".format(liveWacomMetrics.requestedEndpoint, liveWacomMetrics.activeEndpoint?.let { "0x%02X".format(it) } ?: "failure/—", liveWacomMetrics.maxPacketSize?.toString() ?: "—"))
                Button(
                    onClick = {
                        openOnlyJob?.cancel()
                        openOnlyJob = scope.launch {
                            openOnlyRunning = true
                            try { onTestOpenUsbOnly() } finally { openOnlyRunning = false; openOnlyJob = null }
                        }
                    },
                    enabled = !running && !openOnlyRunning,
                ) { Text("TEST OPEN USB ONLY (3 s)") }
                Text("OPEN ONLY: running=$openOnlyRunning | deviceFound=${openUsbOnlyDiagnostic.deviceFound} | permission=${openUsbOnlyDiagnostic.permissionGranted} | connectionOpened=${openUsbOnlyDiagnostic.connectionOpened} | actual=${openUsbOnlyDiagnostic.actualDurationMillis} ms")
                openUsbOnlyDiagnostic.exceptionClass?.let { Text("OPEN ONLY exception: $it: ${openUsbOnlyDiagnostic.exceptionMessage}") }
                openUsbOnlyDiagnostic.exceptionStackTrace?.let { Text(it) }
                Button(
                    onClick = {
                        legacyReaderJob?.cancel()
                        legacyReaderJob = scope.launch {
                            try { onTestLegacyHidReader() } finally { legacyReaderJob = null }
                        }
                    },
                    enabled = !running && !openOnlyRunning && !legacyHidDiagnostic.running,
                ) { Text("TEST LEGACY HID READER (5 s)") }
                Text("LEGACY: running=${legacyHidDiagnostic.running} | device=${legacyHidDiagnostic.deviceFound} | permission=${legacyHidDiagnostic.permissionGranted} | connection=${legacyHidDiagnostic.connectionOpened} | duration=${legacyHidDiagnostic.durationMillis} ms | reports=${legacyHidDiagnostic.reportsReceived} | last RAW=${legacyHidDiagnostic.lastRaw ?: "—"}")
                legacyHidDiagnostic.exceptionClass?.let { Text("LEGACY exception: $it: ${legacyHidDiagnostic.exceptionMessage}") }
                legacyHidDiagnostic.exceptionCause?.let { Text("LEGACY cause: $it") }
                legacyHidDiagnostic.exceptionStackTrace?.let { Text("LEGACY stack:\n$it") }
                Button(onClick = { launchLiveTest(hidOnly = true) }, enabled = !running && !openOnlyRunning && !legacyHidDiagnostic.running) { Text("LIVE HID ONLY (10 s)") }
                Button(
                    onClick = { launchLiveTest(hidOnly = true, parsedStylusTest = true) },
                    enabled = !running && !openOnlyRunning && !legacyHidDiagnostic.running,
                ) { Text("LIVE PARSED STYLUS TEST (10 s)") }
                Button(
                    onClick = { launchLiveTest(hidOnly = true, stylusEventTest = true) },
                    enabled = !running && !openOnlyRunning && !legacyHidDiagnostic.running,
                ) { Text("LIVE STYLUS EVENT TEST (10 s)") }
                Text("Mueve el stylus en diagonal durante la prueba. PASS requiere reportes, cambios en X e Y y cero errores USB fatales; pressure/hover/tip/botón no condicionan PASS.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = rawLoggingEnabled, onCheckedChange = onRawLoggingChanged)
                    Text("RAW completo en Logcat (STYLUS_RAW; puede generar mucho log)")
                }
                parsedTestResult?.let { Text("Parsed test: $it") }
                Button(
                    onClick = { launchLiveTest(hidOnly = false) },
                    enabled = selectedBackend == OutputBackendKind.SHIZUKU && shizukuState.status == ShizukuBackendStatus.READY &&
                        shizukuState.userServiceConnected && !running && !openOnlyRunning && !legacyHidDiagnostic.running && !liveWacomMetrics.possibleDoubleInput,
                ) { Text("LIVE WACOM TEST (10 s)") }
                Text(liveTestMessage)
                if (countdown != null && running) Text("Cambia a la app destino: $countdown…")
                if (liveWacomSecondsRemaining > 0) Text("Prueba activa: quedan $liveWacomSecondsRemaining s")
                liveWacomMetrics.liveError?.let { Text("ERROR: $it") }
                Text("Read attempts: ${liveWacomMetrics.usbTransfersAttempted} | bytes transferred: ${liveWacomMetrics.usbBytesTransferred} | timeouts: ${liveWacomMetrics.usbTimeouts} | USB errors: ${liveWacomMetrics.usbErrors}")
                Text("Raw packets: ${liveWacomMetrics.hidReports} | valid 10-byte: ${liveWacomMetrics.tenByteReports} | ignored: ${liveWacomMetrics.ignoredPackets} | reports/sec: ${liveWacomMetrics.inputReportsPerSecond.format1()}")
                Text("Last RAW: ${liveWacomMetrics.lastRawReport ?: "—"} | age: ${liveWacomMetrics.lastRawAgeMillis?.let { "$it ms" } ?: "—"}")
                Text("Parsed: ${liveWacomMetrics.parsedEvents} | TABLET=${liveWacomMetrics.tabletReports} OUT=${liveWacomMetrics.outOfRangeReports} GENERIC=${liveWacomMetrics.genericReports} INVALID=${liveWacomMetrics.invalidReports}")
                Text("parse errors: ${liveWacomMetrics.parseErrors} | mapped: ${liveWacomMetrics.mappedEvents} | pipeline errors: ${liveWacomMetrics.pipelineErrors}")
                Text("Report type: ${physicalReport?.type ?: "WAITING"}")
                Text("X=${physicalReport?.x ?: "—"}; Y=${physicalReport?.y ?: "—"}; pressure=${physicalReport?.pressure ?: "—"}")
                Text("Side button 1=${physicalReport?.sideButton1 ?: "—"}; side button 2=${physicalReport?.sideButton2 ?: "—"}; eraser=${physicalReport?.eraser ?: "—"}")
                Text("Near proximity=${physicalReport?.nearProximity ?: "—"}; hover distance=${physicalReport?.hoverDistance ?: "—"}")
                Text("Status byte=${physicalReport?.statusByte?.let { "0x%02X".format(it) } ?: "—"} / ${physicalReport?.statusByte?.toString(2)?.padStart(8, '0') ?: "--------"}; status bit 0 raw=${physicalReport?.statusBit0 ?: "—"}")
                Text("CONTACT INTERPRETATION: UNKNOWN — statistical tip/hover hypotheses are not parser fields.")
                Text("Position changes this session: X=${liveWacomMetrics.xValueChanges}; Y=${liveWacomMetrics.yValueChanges}")
                Text("Mapped: x=${liveWacomMetrics.mappedX?.format1() ?: "—"} y=${liveWacomMetrics.mappedY?.format1() ?: "—"} pressure=${liveWacomMetrics.mappedPressure?.format1() ?: "—"}")
                Text("Events submitted: ${liveWacomMetrics.eventsSubmitted} | accepted: ${liveWacomMetrics.injectedEvents} | rejected: ${liveWacomMetrics.rejectedEvents}")
                Text("Output rate: ${liveWacomMetrics.outputEventsPerSecond.format1()} events/s | latency avg ${liveWacomMetrics.averageLatencyMillis.format1()} ms / max ${liveWacomMetrics.maxLatencyMillis} ms")
                Text("Last output: SOURCE_STYLUS / TOOL_TYPE_STYLUS | API=${liveWacomMetrics.lastMethod ?: "—"} | result=${liveWacomMetrics.lastResult ?: "pending"} | exception=${liveWacomMetrics.lastException ?: "none"}")
                liveHidOnlyPoint?.let { point ->
                    Text("HID ONLY: posición mapeada local, sin inyección")
                    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                        drawRect(Color(0xFF263238))
                        drawCircle(Color(0xFF80CBC4), 9.dp.toPx(), Offset(point.first.coerceIn(0f, 1f) * size.width, point.second.coerceIn(0f, 1f) * size.height))
                    }
                }
                if (liveWacomMetrics.possibleDoubleInput || (liveWacomSecondsRemaining > 0 && nativeStylusRecentlyActive)) {
                    Text("POSIBLE DOBLE INPUT: vigila acciones duplicadas; la prueba se detiene al detectar entrada nativa.")
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("LIVE STYLUS EVENT TEST — local only, no Android injection")
                        Text("Thresholds: DOWN=$tipDownThreshold | UP=$tipUpThreshold | pressureRaw 0..2047")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { onTipThresholdsChanged((tipDownThreshold - 1).coerceAtLeast(tipUpThreshold + 1), tipUpThreshold) },
                                enabled = !running && tipDownThreshold > tipUpThreshold + 1,
                            ) { Text("DOWN −") }
                            OutlinedButton(
                                onClick = { onTipThresholdsChanged((tipDownThreshold + 1).coerceAtMost(2047), tipUpThreshold) },
                                enabled = !running && tipDownThreshold < 2047,
                            ) { Text("DOWN +") }
                            OutlinedButton(
                                onClick = { onTipThresholdsChanged(tipDownThreshold, (tipUpThreshold - 1).coerceAtLeast(0)) },
                                enabled = !running && tipUpThreshold > 0,
                            ) { Text("UP −") }
                            OutlinedButton(
                                onClick = { onTipThresholdsChanged(tipDownThreshold, (tipUpThreshold + 1).coerceAtMost(tipDownThreshold - 1)) },
                                enabled = !running && tipUpThreshold < tipDownThreshold - 1,
                            ) { Text("UP +") }
                        }
                        val eventState = liveStylusEventFrame.update?.state
                        val rawReport = liveStylusEventFrame.rawReport
                        Text("Report type: ${rawReport?.type ?: "WAITING"} | raw X=${rawReport?.x ?: "—"} Y=${rawReport?.y ?: "—"} | pressure raw=${rawReport?.pressure ?: "—"}")
                        Text("Status byte: ${rawReport?.statusByte?.let { "0x%02X".format(it) } ?: "—"} | statusBit0 RAW=${rawReport?.statusBit0 ?: "—"} | BTN1 raw=${rawReport?.sideButton1 ?: "—"} | BTN2 raw=${rawReport?.sideButton2 ?: "—"} | proximity=${rawReport?.nearProximity ?: "—"} | hoverDistance=${rawReport?.hoverDistance ?: "—"}")
                        Text("State: ${eventState?.state ?: StylusPresenceState.OUT_OF_RANGE} | inRange=${eventState?.present ?: false} | contact=${eventState?.contact ?: false} | hover=${eventState?.hover ?: false}")
                        Text("Normalized X=${eventState?.normalizedX?.format1() ?: "—"} Y=${eventState?.normalizedY?.format1() ?: "—"} pressure=${eventState?.pressureNormalized?.format1() ?: "—"} (${eventState?.pressureRaw ?: 0}/2047)")
                        Text("Transition: ${eventState?.transition ?: "—"} | last event: ${liveStylusEventFrame.lastEvent?.type ?: "—"} | events/s=${liveStylusEventFrame.eventsPerSecond.format1()} | total=${liveStylusEventFrame.eventCount}")
                        Text("BTN1=${eventState?.sideButton1 ?: false}  BTN2=${eventState?.sideButton2 ?: false}  PRESSURE=${eventState?.pressureNormalized?.format1() ?: "0.0"}  STATE=${eventState?.state ?: StylusPresenceState.OUT_OF_RANGE}")
                        Canvas(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF101820))) {
                            val points = liveStylusEventFrame.canvasPoints
                            for (index in 1 until points.size) {
                                val before = points[index - 1]
                                val after = points[index]
                                if (before.strokeId == after.strokeId) {
                                    drawLine(
                                        color = Color(0xFF80CBC4),
                                        start = Offset(before.x * size.width, before.y * size.height),
                                        end = Offset(after.x * size.width, after.y * size.height),
                                        strokeWidth = 2f + ((before.pressure + after.pressure) / 2f) * 18f,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                            if (eventState?.hover == true && eventState.normalizedX != null && eventState.normalizedY != null) {
                                drawCircle(
                                    color = Color(0xFFFFC857),
                                    radius = 7.dp.toPx(),
                                    center = Offset(eventState.normalizedX * size.width, eventState.normalizedY * size.height),
                                )
                            }
                        }
                        Text("HOVER mueve cursor sin dibujar; DOWN inicia trazo; MOVE dibuja con ancho 2..20 px; UP termina; OUT_OF_RANGE oculta cursor.")
                    }
                }
            }
        }

        Button(onClick = ::stopAllTests, enabled = shizukuState.userServiceConnected || running || liveWacomSecondsRemaining > 0) {
            Text("STOP — emergency release")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Injection log — ${attempts.size} latest attempts")
                attempts.asReversed().forEach { attempt ->
                    Text("${attempt.timestamp} | ${attempt.name} | API: ${attempt.methodApi} | returned=${attempt.returned}")
                    Text("exception=${attempt.exception ?: "none"} | SecurityException=${attempt.securityException} | ${attempt.detail}")
                }
            }
        }
    }
}

@Composable
private fun ExperimentStageCard(
    title: String,
    description: String,
    enabled: Boolean,
    completed: Boolean,
    confirmed: Boolean,
    attempts: List<ShizukuInjectionAttempt>,
    countdown: Int?,
    onStart: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title)
            Text(description)
            Button(onClick = onStart, enabled = enabled) { Text("START ${title.substringAfter('.').trim()}") }
            if (countdown != null && !completed) Text("Switch to target app — starts in $countdown…")
            if (completed) {
                val accepted = attempts.count { it.returned == true && it.exception == null }
                Text("Test executed: $accepted/${attempts.size} calls accepted. Inspect each result; false/exception is not hidden. Verify the requested behavior in the destination app before confirming.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = onConfirm, enabled = !confirmed)
                    Text("Confirmo visualmente esta prueba")
                }
            }
            attempts.forEach { attempt ->
                Text("${attempt.timestamp} | ${attempt.name} | API=${attempt.methodApi} | inject result=${attempt.returned}")
                Text("${formatProbeDetail(attempt.detail)} | exception=${attempt.exception ?: "none"}")
            }
        }
    }
}

@Composable
private fun GlobalGeometryDiagnosticPanel(
    displayInfo: AndroidDisplayInfo,
    mappingOptions: MappingOptions,
    shizukuReady: Boolean,
    onInjectProbe: (String, ShizukuMotionProbe) -> ShizukuInjectionAttempt,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sin ejecutar") }
    var countdown by remember { mutableIntStateOf(0) }
    val logicalWidth = displayInfo.displayWidth
    val logicalHeight = displayInfo.displayHeight
    val globalMappingOptions = mappingOptions.copy(mode = MappingMode.FULL_TABLET)

    fun startPattern(pattern: GeometryPattern) {
        if (running || !shizukuReady) return
        scope.launch {
            running = true
            status = "$pattern: preparando; cambia a la app destino"
            try {
                for (seconds in 5 downTo 1) {
                    countdown = seconds
                    delay(1_000)
                }
                countdown = 0
                val points = GlobalGeometryDiagnostic.normalizedPath(pattern)
                val startedAt = SystemClock.uptimeMillis()
                var accepted = 0
                points.forEachIndexed { index, (nx, ny) ->
                    val sample = GlobalGeometryDiagnostic.sample(
                        pattern = pattern,
                        index = index,
                        normalizedX = nx,
                        normalizedY = ny,
                        displayWidth = displayInfo.logicalWidth,
                        displayHeight = displayInfo.logicalHeight,
                        displayId = displayInfo.displayId,
                        options = globalMappingOptions,
                    )
                    val action = when (index) {
                        0 -> MotionEvent.ACTION_DOWN
                        points.lastIndex -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val now = SystemClock.uptimeMillis()
                    val probe = ShizukuMotionProbe(
                        action = action,
                        x = sample.motionEventX,
                        y = sample.motionEventY,
                        pressure = if (action == MotionEvent.ACTION_UP) 0f else 0.5f,
                        source = InputDevice.SOURCE_STYLUS,
                        toolType = MotionEvent.TOOL_TYPE_STYLUS,
                        downTimeMillis = startedAt,
                        eventTimeMillis = now,
                        displayId = sample.displayId,
                    )
                    Log.i(
                        "GLOBAL_GEOMETRY",
                        "pattern=$pattern mapping=GLOBAL_DEFAULT index=$index raw=(${sample.rawX},${sample.rawY}) " +
                            "normalized=(${sample.normalizedX},${sample.normalizedY}) " +
                            "mappedBeforeRotation=(${sample.mappedXBeforeRotation},${sample.mappedYBeforeRotation}) " +
                        "tabletRotation=${sample.rotationDegrees} matrix=${sample.matrixDescription} " +
                            "afterRotation=(${sample.xAfterRotation},${sample.yAfterRotation}) " +
                            "MotionEvent.getX/getY=(${probe.x},${probe.y}) display=${sample.displayWidth}x${sample.displayHeight} " +
                            "displayId=${sample.displayId} action=$action",
                    )
                    val attempt = withContext(Dispatchers.IO) {
                        onInjectProbe("GEOMETRY_${pattern.name}_$index", probe)
                    }
                    if (attempt.returned == true && attempt.exception == null) accepted++
                    if (index != points.lastIndex) delay(45)
                }
                status = "$pattern / mapping global estándar: aceptados $accepted/${points.size}; $logicalWidth×$logicalHeight px; rotación manual=${mappingOptions.tabletRotation.degrees}°. Revisa GLOBAL_GEOMETRY y SHIZUKU_INJECTION."
            } catch (exception: Exception) {
                status = "Error $pattern: ${exception.javaClass.name}: ${exception.message}"
                Log.e("GLOBAL_GEOMETRY", status, exception)
            } finally {
                countdown = 0
                running = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GLOBAL GEOMETRY DIAGNOSTIC")
            Text("No usa datos HID; genera patrones controlados de prueba. Solo inyecta cuando se pulsa un patrón.")
            Text("Display lógico objetivo: ${displayInfo.displayWidth}×${displayInfo.displayHeight}; displayId=${displayInfo.displayId}; rotación Android=${displayInfo.rotationDegrees}°; rotación manual de tableta=${mappingOptions.tabletRotation}")
            Text("Resolución natural informada por el panel: 1600×2560; ventana/insets no se usan para inyectar.")
            Text("Escalas globales: ${GlobalGeometryDiagnostic.scaleSummary(logicalWidth, logicalHeight)} (se esperan ≈0.168421 en X e Y)")
            Text("Mapping global estándar: FULL_TABLET + rotación manual ${mappingOptions.tabletRotation}; independiente de la rotación Android.")
            Text("Patrones inyectados como un trazo SOURCE_STYLUS: sweep horizontal, vertical, diagonal, rectángulo y círculo físico con radios ajustados a 1.6:1.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { startPattern(GeometryPattern.HORIZONTAL_SWEEP) }, enabled = shizukuReady && !running) { Text("HORIZONTAL_SWEEP") }
                Button(onClick = { startPattern(GeometryPattern.VERTICAL_SWEEP) }, enabled = shizukuReady && !running) { Text("VERTICAL_SWEEP") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { startPattern(GeometryPattern.DIAGONAL) }, enabled = shizukuReady && !running) { Text("DIAGONAL") }
                Button(onClick = { startPattern(GeometryPattern.RECTANGLE) }, enabled = shizukuReady && !running) { Text("RECTANGLE") }
                Button(onClick = { startPattern(GeometryPattern.CIRCLE_ELLIPSE) }, enabled = shizukuReady && !running) { Text("CIRCLE / ELLIPSE") }
            }
            if (countdown > 0) Text("Cambia a la app destino; inicio en $countdown…")
            Text(if (running) "RUNNING — $status" else "Resultado: $status")
            Text("Cada muestra registra RAW sintético, normalized, antes/después de rotación manual, matriz, x/y que recibe MotionEvent y displayId. El retorno true confirma aceptación, no la geometría final tras InputDispatcher.")
        }
    }
}

private fun formatProbeDetail(detail: String): String {
    val actionNumber = Regex("action=(-?\\d+)").find(detail)?.groupValues?.get(1)?.toIntOrNull()
    val action = when (actionNumber) {
        MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
        MotionEvent.ACTION_UP -> "ACTION_UP"
        MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
        MotionEvent.ACTION_HOVER_ENTER -> "ACTION_HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "ACTION_HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "ACTION_HOVER_EXIT"
        MotionEvent.ACTION_BUTTON_PRESS -> "ACTION_BUTTON_PRESS"
        MotionEvent.ACTION_BUTTON_RELEASE -> "ACTION_BUTTON_RELEASE"
        else -> "action=$actionNumber"
    }
    val source = Regex("source=0x([0-9a-fA-F]+)").find(detail)?.groupValues?.get(1)
    val tool = Regex("toolType=(\\d+)").find(detail)?.groupValues?.get(1)
    val x = Regex("xy=\\(([^,]+),([^\\)]+)\\)").find(detail)
    val fields = listOf(
        "source=${if (source == "4002") "SOURCE_STYLUS" else "0x${source ?: "?"}"}",
        "toolType=${if (tool == MotionEvent.TOOL_TYPE_STYLUS.toString()) "TOOL_TYPE_STYLUS" else tool ?: "?"}",
        "action=$action",
        "pressure=${Regex("pressure=([^ ]+)").find(detail)?.groupValues?.get(1) ?: "?"}",
        "buttonState=${Regex("buttonState=([^ ]+)").find(detail)?.groupValues?.get(1) ?: "?"}",
        "x=${x?.groupValues?.get(1) ?: "?"}; y=${x?.groupValues?.get(2) ?: "?"}",
        "displayId=${Regex("displayId=(\\d+)").find(detail)?.groupValues?.get(1) ?: "?"}",
    )
    return fields.joinToString(" | ")
}

private suspend fun executeFollowTest(
    width: Float,
    height: Float,
    displayId: Int,
    onPoint: (Offset) -> Unit,
    onInject: (String, ShizukuMotionProbe) -> ShizukuInjectionAttempt,
    addAttempt: (ShizukuInjectionAttempt) -> Unit,
): Boolean {
    val started = SystemClock.uptimeMillis()
    val downTime = started
    val centerX = width / 2f
    val centerY = height / 2f
    val radiusX = width * 0.12f
    val radiusY = height * 0.12f
    val duration = FOLLOW_DURATION_MS
    var index = 0
    var allAccepted = true
    while (SystemClock.uptimeMillis() - started < duration) {
        val now = SystemClock.uptimeMillis()
        val progress = ((now - started).toFloat() / duration).coerceIn(0f, 1f)
        val angle = 2.0 * PI * progress
        val x = centerX + radiusX * cos(angle).toFloat()
        val y = centerY + radiusY * sin(angle).toFloat()
        onPoint(Offset(x / width, y / height))
        val action = if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
        val probe = probe(action, x, y, 0.5f, InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 0, 0, downTime, now, displayId)
        val attempt = withContext(Dispatchers.IO) { onInject("FOLLOW_TEST_${if (index == 0) "DOWN" else "MOVE"}", probe) }
        addAttempt(attempt)
        allAccepted = allAccepted && attempt.returned == true && attempt.exception == null
        index++
        delay(FOLLOW_SAMPLE_MS)
    }
    val ended = SystemClock.uptimeMillis()
    val release = probe(MotionEvent.ACTION_UP, centerX + radiusX, centerY, 0f, InputDevice.SOURCE_STYLUS,
        MotionEvent.TOOL_TYPE_STYLUS, 0, 0, downTime, ended, displayId)
    val releaseAttempt = withContext(Dispatchers.IO) { onInject("FOLLOW_TEST_UP", release) }
    addAttempt(releaseAttempt)
    return allAccepted && releaseAttempt.returned == true && releaseAttempt.exception == null
}

private fun probe(
    action: Int,
    x: Float,
    y: Float,
    pressure: Float,
    source: Int,
    toolType: Int,
    buttonState: Int,
    actionButton: Int,
    downTime: Long,
    eventTime: Long,
    displayId: Int = 0,
) = ShizukuMotionProbe(action, x, y, pressure, source, toolType, buttonState, actionButton, downTime, eventTime, displayId)

private fun confirmedBit(report: ParsedCtl472Report, status: HypothesisStatus, candidate: String?): Boolean? {
    if (status != HypothesisStatus.CONFIRMED) return null
    val match = Regex("byte (\\d+), bit (\\d+)").find(candidate.orEmpty()) ?: return null
    val byte = match.groupValues[1].toIntOrNull() ?: return null
    val bit = match.groupValues[2].toIntOrNull() ?: return null
    return report.flagBytes[byte]?.let { ((it shr bit) and 1) == 1 }
}

private fun Boolean?.displayValue(): String = this?.toString()?.uppercase() ?: "UNKNOWN"
private fun hoverValue(event: MappedStylusEvent): String? = when {
    event.inRange == null || event.tip == null -> null
    event.inRange && !event.tip -> "YES"
    else -> "NO"
}
private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
private fun Float.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
private fun Float.format3(): String = String.format(java.util.Locale.US, "%.3f", this)

private const val MAX_VISIBLE_ATTEMPTS = 120
private const val EVENT_GAP_MS = 40L
private const val TAP_HOLD_MS = 80L
private const val FOLLOW_DURATION_MS = 5_000L
private const val FOLLOW_SAMPLE_MS = 50L
private const val LIVE_TOTAL_WINDOW_SECONDS = 25
