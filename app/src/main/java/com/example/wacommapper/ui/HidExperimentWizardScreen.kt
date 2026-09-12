package com.example.wacommapper.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wacommapper.usb.ExperimentSessionAnalysis
import com.example.wacommapper.usb.HidConnectionState
import com.example.wacommapper.usb.HidExperimentAnalyzer
import com.example.wacommapper.usb.HidExperimentScenarios
import com.example.wacommapper.usb.HidExperimentScenario
import com.example.wacommapper.usb.HidExperimentSession
import com.example.wacommapper.usb.ExperimentPhaseMarker
import com.example.wacommapper.usb.HidEvidenceConfidence
import com.example.wacommapper.usb.RoundTwoExperimentAnalysis
import com.example.wacommapper.usb.RoundTwoExperimentAnalyzer
import com.example.wacommapper.usb.RoundThreeExperimentAnalyzer
import com.example.wacommapper.usb.UsbDeviceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HidExperimentWizardScreen(
    device: UsbDeviceInfo?,
    selectedInterface: Int,
    connection: HidConnectionState,
    sessions: List<HidExperimentSession>,
    captureReportCount: Int,
    errorMessage: String?,
    onSelectInterface: (Int) -> Unit,
    onClaimInterface: (Int) -> Boolean,
    onReleaseInterface: () -> Unit,
    onPrepareCapture: (HidExperimentScenario) -> Boolean,
    onBeginCapture: (HidExperimentScenario, Long) -> Unit,
    onFinishCapture: (HidExperimentScenario, Long, Long, List<ExperimentPhaseMarker>) -> HidExperimentSession?,
    onExport: () -> Unit,
) {
    var showSummary by remember { mutableStateOf(false) }
    var selectedRound by remember { mutableIntStateOf(3) }
    var scenarioIndex by remember(selectedInterface) { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var preparationCountdown by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var activeStartedAt by remember { mutableStateOf(0L) }
    var localError by remember { mutableStateOf<String?>(null) }
    val phaseMarkers = remember { mutableStateListOf<ExperimentPhaseMarker>() }
    var nextMarkerIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val endpoint = device?.interfaces
        ?.firstOrNull { it.id == selectedInterface }
        ?.endpoints
        ?.firstOrNull { it.directionName == "IN" && it.typeName == "INTERRUPT" }
    val isClaimedMatch = connection.claimedInterfaceId == selectedInterface
    val endpointMatch = endpoint?.address == connection.activeEndpointAddress
    val packetSizeMatch = endpoint?.maxPacketSize == connection.packetSize
    val canCapture = device != null && endpoint != null && isClaimedMatch && endpointMatch && packetSizeMatch && connection.isOpen
    val activeScenarios = when (selectedRound) {
        2 -> HidExperimentScenarios.roundTwo
        3 -> HidExperimentScenarios.roundThree
        else -> HidExperimentScenarios.all
    }
    val scenario = activeScenarios[scenarioIndex.coerceIn(activeScenarios.indices)]
    val markerSequence = when (scenario.id) {
        "BUTTON_TOGGLE_STATIC" -> listOf("BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED")
        "TIP_TOGGLE_STATIC_V2" -> listOf("HOVER", "TIP_DOWN", "HOVER", "TIP_DOWN", "HOVER")
        "PRESSURE_WITHOUT_STATE_ANALYSIS" -> listOf("PRESSURE_MINIMUM", "PRESSURE_MEDIUM", "PRESSURE_HIGH", "PRESSURE_MINIMUM")
        "TIP_TOGGLE_STATIC" -> listOf("TIP", "HOVER", "TIP", "HOVER")
        else -> emptyList()
    }
    val scenarioDone = sessions.any { it.interfaceId == selectedInterface && it.scenario.id == scenario.id }
    val completedCount = sessions.count { it.interfaceId == selectedInterface && activeScenarios.any { step -> step.id == it.scenario.id } }
    val analyses = remember(sessions, selectedInterface) {
        sessions.filter { it.interfaceId == selectedInterface }.map(HidExperimentAnalyzer::analyze)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Calibration / HID Experiment Wizard")
        Text("CTL-472")
        Text("VID: 0x056A   PID: 0x037A")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSummary = false }, enabled = showSummary) { Text("Wizard") }
            OutlinedButton(onClick = { showSummary = true }, enabled = !showSummary) { Text("Summary") }
            Button(onClick = onExport, enabled = sessions.isNotEmpty()) { Text("Export wacom_experiment.txt") }
        }
        if (showSummary) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { round ->
                        OutlinedButton(onClick = { selectedRound = round }, enabled = selectedRound != round) { Text("Round $round") }
                    }
                }
                when (selectedRound) {
                    2 -> RoundTwoSummary(sessions.filter { it.interfaceId == selectedInterface })
                    3 -> RoundThreeSummary(sessions.filter { it.interfaceId == selectedInterface })
                    else -> SummaryContent(selectedInterface, sessions, analyses)
                }
            }
        } else {
            Text("Select interface")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { round ->
                    OutlinedButton(onClick = { selectedRound = round; scenarioIndex = 0 }, enabled = selectedRound != round && !capturing) {
                        Text("Round $round${if (round == 3) " — controlled" else ""}")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectInterface(0); scenarioIndex = 0 }, enabled = selectedInterface != 0 && !connection.isOpen) {
                    Text("Interface 0 — EP 0x81")
                }
                OutlinedButton(onClick = { onSelectInterface(1); scenarioIndex = 0 }, enabled = selectedInterface != 1 && !connection.isOpen) {
                    Text("Interface 1 — EP 0x82")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Selected interface: $selectedInterface")
                    Text("Claimed interface: ${connection.claimedInterfaceId?.toString() ?: "NONE"}")
                    Text("Active endpoint: ${connection.activeEndpointAddress?.let(::endpointHex) ?: "NONE"}")
                    Text("Selected endpoint: ${endpoint?.address?.let(::endpointHex) ?: "NONE"}")
                    Text("Packet size: ${endpoint?.maxPacketSize?.toString() ?: "—"} bytes")
                    Text("Connection: ${if (connection.isOpen) "OPEN" else "CLOSED"}")
                    Text(if (canCapture) "Interface and endpoint verified" else "Safety check failed: selected and claimed interface/endpoint/packet size must match")
                    if (endpoint != null && !connection.isOpen) {
                        Button(onClick = {
                            localError = if (onClaimInterface(selectedInterface)) null else "Could not claim the selected interface"
                        }) { Text("Claim selected interface") }
                    }
                    if (connection.isOpen) {
                        OutlinedButton(onClick = onReleaseInterface, enabled = !capturing) { Text("Release interface") }
                    }
                }
            }
            Text("ROUND $selectedRound — Experiment ${scenarioIndex + 1} of ${activeScenarios.size} — $completedCount completed on this interface")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(scenario.title)
                    Text(scenario.instructions)
                    Text("Suggested duration: ${scenario.durationSeconds} seconds")
                    if (preparationCountdown > 0) Text("Preparing capture — waiting 500 ms…")
                    if (capturing) {
                        Text("CAPTURING…")
                        Text("Countdown: $remainingSeconds…")
                        Text("Progress: $progress%")
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Captured reports: $captureReportCount")
                        if (markerSequence.isNotEmpty()) {
                            Text("Marked physical states: ${phaseMarkers.joinToString(" → ") { it.phase }}")
                            OutlinedButton(onClick = {
                                val phase = markerSequence.getOrNull(nextMarkerIndex) ?: return@OutlinedButton
                                phaseMarkers.add(ExperimentPhaseMarker(SystemClock.elapsedRealtime(), System.currentTimeMillis(), phase))
                                nextMarkerIndex++
                            }, enabled = nextMarkerIndex < markerSequence.size) {
                                Text("MARK ${markerSequence.getOrNull(nextMarkerIndex) ?: "SEQUENCE COMPLETE"}")
                            }
                        }
                    }
                    if (scenarioDone && !capturing) Text("Scenario saved. You may continue or repeat it.")
                    Button(
                        onClick = {
                            localError = null
                            if (!canCapture) {
                                localError = "Interfaz no reclamada o endpoint activo distinto del seleccionado. No se inició la captura."
                            } else if (!onPrepareCapture(scenario)) {
                                localError = "No se pudo preparar el buffer; la captura no se inició."
                            } else {
                                scope.launch {
                                    capturing = true
                                    preparationCountdown = 1
                                    progress = 0
                                    remainingSeconds = scenario.durationSeconds
                                    phaseMarkers.clear()
                                    nextMarkerIndex = 0
                                    delay(500)
                                    preparationCountdown = 0
                                    activeStartedAt = System.currentTimeMillis()
                                    val initialPhase = when (scenario.id) {
                                        "STATIC_BASELINE", "TIP_TOGGLE_STATIC" -> "HOVER"
                                        else -> null
                                    }
                                    if (initialPhase != null) {
                                        phaseMarkers.add(ExperimentPhaseMarker(SystemClock.elapsedRealtime(), activeStartedAt, initialPhase))
                                    }
                                    onBeginCapture(scenario, activeStartedAt)
                                    val durationMs = scenario.durationSeconds * 1_000L
                                    val startedElapsed = System.currentTimeMillis()
                                    while (System.currentTimeMillis() - startedElapsed < durationMs) {
                                        val elapsed = System.currentTimeMillis() - startedElapsed
                                        progress = ((elapsed * 100) / durationMs).toInt().coerceIn(0, 100)
                                        remainingSeconds = ((durationMs - elapsed + 999) / 1_000L).toInt().coerceAtLeast(0)
                                        delay(100)
                                    }
                                    progress = 100
                                    remainingSeconds = 0
                                    onFinishCapture(scenario, activeStartedAt, System.currentTimeMillis(), phaseMarkers.toList())
                                    capturing = false
                                }
                            }
                        },
                        enabled = !capturing && canCapture,
                    ) { Text(if (capturing) "CAPTURING" else "START") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (scenarioIndex > 0) scenarioIndex-- }, enabled = !capturing && scenarioIndex > 0) { Text("Previous") }
                        Button(onClick = { if (scenarioIndex < activeScenarios.lastIndex) scenarioIndex++ }, enabled = !capturing && scenarioDone && scenarioIndex < activeScenarios.lastIndex) { Text("NEXT") }
                    }
                    (localError ?: errorMessage)?.let { Text("Error: $it") }
                }
            }
            val thisInterfaceSessions = sessions.filter { it.interfaceId == selectedInterface }
            if (thisInterfaceSessions.isNotEmpty()) {
                Text("Saved scenarios on Interface $selectedInterface")
                thisInterfaceSessions.forEach { Text("${it.scenario.id}: ${it.reports.size} reports, ${it.reports.firstOrNull()?.bytes?.size ?: 0} bytes/report") }
            }
            analyses.firstOrNull { it.session.scenario.id == scenario.id }?.let { analysis ->
                Text("Analysis for ${scenario.id} — possible candidates only")
                analysis.bytes.forEach { stat ->
                    Text("Byte ${stat.index}: min ${stat.min}, max ${stat.max}, range ${stat.range}, distinct ${stat.distinctValues}, changes ${stat.changes}, ${stat.changeFrequencyHz.formatOneDecimal()} changes/sec")
                }
                analysis.candidates16.forEach { candidate ->
                    Text("${candidate.name} bytes ${candidate.byteOffsets.first}-${candidate.byteOffsets.last}: LE range ${candidate.littleEndianRange}, distinct ${candidate.littleEndianDistinct}; BE range ${candidate.bigEndianRange}, distinct ${candidate.bigEndianDistinct}")
                }
            }
        }
    }
}

@Composable
private fun SummaryContent(
    selectedInterface: Int,
    allSessions: List<HidExperimentSession>,
    selectedInterfaceAnalyses: List<ExperimentSessionAnalysis>,
) {
    val interfaces = allSessions.groupBy { it.interfaceId }.toSortedMap()
    Text("EXPERIMENTAL SUMMARY — all labels are possible candidates, not definitive protocol fields")
    val analysisByKey = remember(allSessions) {
        allSessions.associateBy({ "${it.interfaceId}:${it.scenario.id}" }, HidExperimentAnalyzer::analyze)
    }
    listOf("MOVE_X_ONLY", "MOVE_Y_ONLY", "PRESSURE_CENTER", "HOVER_CENTER").forEach { id ->
        Text("$id")
        interfaces.keys.forEach { interfaceId ->
            val analysis = analysisByKey["$interfaceId:$id"]
            Text("Interface $interfaceId: ${analysis?.reportsPerSecond?.formatOneDecimal() ?: "not captured"} reports/sec; ${analysis?.varyingByteCount ?: 0} varying bytes")
        }
    }
    interfaces.keys.forEach { interfaceId ->
        Text("Possible candidates — Interface $interfaceId")
        val x = analysisByKey["$interfaceId:MOVE_X_ONLY"]
        val y = analysisByKey["$interfaceId:MOVE_Y_ONLY"]
        val pressure = analysisByKey["$interfaceId:PRESSURE_CENTER"]
        val hover = analysisByKey["$interfaceId:HOVER_CENTER"]
        val movementByteCount = listOfNotNull(x, y).minOfOrNull { it.bytes.size } ?: 0
        val possibleX = if (x != null && y != null) (0 until movementByteCount)
            .maxByOrNull { index -> x.bytes[index].range - y.bytes[index].range }
            ?.takeIf { x.bytes[it].range > y.bytes[it].range } else null
        val possibleY = if (x != null && y != null) (0 until movementByteCount)
            .maxByOrNull { index -> y.bytes[index].range - x.bytes[index].range }
            ?.takeIf { y.bytes[it].range > x.bytes[it].range } else null
        Text("Possible X candidate: ${possibleX?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
        Text("Possible Y candidate: ${possibleY?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
        val xPairMap = x?.candidates16?.associateBy { it.name }.orEmpty()
        val yPairMap = y?.candidates16?.associateBy { it.name }.orEmpty()
        val possibleXPair = if (x != null && y != null) x.candidates16.maxByOrNull { candidate ->
            candidate.littleEndianRange - (yPairMap[candidate.name]?.littleEndianRange ?: 0L)
        }?.takeIf { candidate ->
            candidate.littleEndianRange > (yPairMap[candidate.name]?.littleEndianRange ?: 0L)
        } else null
        val possibleYPair = if (x != null && y != null) y.candidates16.maxByOrNull { candidate ->
            candidate.littleEndianRange - (xPairMap[candidate.name]?.littleEndianRange ?: 0L)
        }?.takeIf { candidate ->
            candidate.littleEndianRange > (xPairMap[candidate.name]?.littleEndianRange ?: 0L)
        } else null
        Text("Possible X 16-bit candidate: ${possibleXPair?.let { "${it.name}, bytes ${it.byteOffsets.first}-${it.byteOffsets.last}" } ?: "insufficient/discriminating samples"}")
        Text("Possible Y 16-bit candidate: ${possibleYPair?.let { "${it.name}, bytes ${it.byteOffsets.first}-${it.byteOffsets.last}" } ?: "insufficient/discriminating samples"}")
        val possiblePressure = if (x != null && y != null && pressure != null) {
            pressure.candidates16.filter { candidate ->
                candidate.littleEndianRange > maxOf(
                    xPairMap[candidate.name]?.littleEndianRange ?: 0L,
                    yPairMap[candidate.name]?.littleEndianRange ?: 0L,
                )
            }.maxByOrNull { it.littleEndianRange }
        } else null
        Text("Possible pressure candidate: ${possiblePressure?.name ?: "insufficient/discriminating samples"} (LE range ${possiblePressure?.littleEndianRange ?: "—"}; position-stability check is experimental)")
        val touch = analysisByKey["$interfaceId:TIP_UP_DOWN"]
        val sideButton = analysisByKey["$interfaceId:SIDE_BUTTON_HOVER"]
        val candidateByteCount = listOfNotNull(hover, touch, sideButton).minOfOrNull { it.bytes.size } ?: 0
        val touchByte = if (touch != null && hover != null) (0 until candidateByteCount)
            .maxByOrNull { index -> touch.bytes[index].range - hover.bytes[index].range }
            ?.takeIf { touch.bytes[it].range > hover.bytes[it].range } else null
        val buttonByte = if (sideButton != null && hover != null) (0 until candidateByteCount)
            .maxByOrNull { index -> sideButton.bytes[index].range - hover.bytes[index].range }
            ?.takeIf { sideButton.bytes[it].range > hover.bytes[it].range } else null
        Text("Possible contact/hover flag: ${touchByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
        Text("Possible side-button flag: ${buttonByte?.let { "byte $it" } ?: "insufficient/discriminating samples"}")
    }
    interfaces.forEach { (interfaceId, sessions) ->
        val analyses = sessions.map(HidExperimentAnalyzer::analyze)
        val reportsPerSecond = analyses.map { it.reportsPerSecond }.average().takeIf { it.isFinite() } ?: 0.0
        val varyingBytes = analyses.maxOfOrNull { it.varyingByteCount } ?: 0
        val entropy = analyses.map { it.approximateEntropyBits }.average().takeIf { it.isFinite() } ?: 0.0
        val movementEvidence = HidExperimentAnalyzer.movementVariationEvidence(sessions)
        Text("Interface $interfaceId: ${sessions.size} scenarios; ${reportsPerSecond.formatOneDecimal()} avg reports/sec; max varying bytes $varyingBytes; approximate entropy ${entropy.formatOneDecimal()} bits/byte; movement-related variation vs hover baseline $movementEvidence (not a correlation coefficient)")
    }
    val comparableInterfaces = interfaces.filterValues { sessions ->
        val ids = sessions.map { it.scenario.id }.toSet()
        "MOVE_X_ONLY" in ids && "MOVE_Y_ONLY" in ids && "HOVER_CENTER" in ids
    }
    val recommended = if (comparableInterfaces.size >= 2) comparableInterfaces.maxByOrNull { (_, sessions) ->
        val summaries = sessions.map(HidExperimentAnalyzer::analyze)
        (summaries.map { it.reportsPerSecond }.average().takeIf { it.isFinite() } ?: 0.0) +
            HidExperimentAnalyzer.movementVariationEvidence(sessions) * 2.0
    }?.key else null
    Text("Recommended stylus interface (experimental): ${recommended?.let { "Interface $it" } ?: "insufficient comparison; capture HOVER_CENTER, MOVE_X_ONLY and MOVE_Y_ONLY on both interfaces"}")
    if (selectedInterfaceAnalyses.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Per-byte analysis for Interface $selectedInterface")
        selectedInterfaceAnalyses.forEach { analysis ->
            Text("${analysis.session.scenario.id}: ${analysis.session.reports.size} reports @ ${analysis.reportsPerSecond.formatOneDecimal()} reports/sec")
            analysis.bytes.forEach { stat ->
                Text("Byte ${stat.index}: min ${stat.min}, max ${stat.max}, range ${stat.range}, distinct ${stat.distinctValues}, changes ${stat.changes}, change frequency ${stat.changeFrequencyHz.formatOneDecimal()} Hz")
            }
            analysis.candidates16.forEach { candidate ->
                Text("${candidate.name} bytes ${candidate.byteOffsets.first}-${candidate.byteOffsets.last}: LE range ${candidate.littleEndianRange}/distinct ${candidate.littleEndianDistinct}/changes ${candidate.littleEndianChanges}; BE range ${candidate.bigEndianRange}/distinct ${candidate.bigEndianDistinct}/changes ${candidate.bigEndianChanges}")
            }
        }
    }
}

@Composable
private fun RoundTwoSummary(sessions: List<HidExperimentSession>) {
    val analysis = remember(sessions) { RoundTwoExperimentAnalyzer.analyze(sessions) }
    Text("ROUND 2 — statistical evidence only; parser unchanged")
    Text("Expected device: CTL-472, Interface 0 / EP 0x81; only 10-byte report ID 0x02 samples are included.")
    Text("Each scenario should have at least 100 useful reports. Position SD gates: X ≤ 152 raw, Y ≤ 95 raw (1% of validated tablet ranges). These thresholds are diagnostics, not parser rules.")
    if (analysis.scenarios.isEmpty()) {
        Text("No Round 2 captures yet. Select Round 2 in Wizard, capture each condition, then return here.")
        return
    }
    analysis.scenarios.forEach { scenario ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${scenario.scenarioId}: ${scenario.reportCount} standard reports${if (scenario.reportCount >= 100) " — sample target reached" else " — target: 100"}")
                Text("Pressure raw min / mean / max: ${scenario.pressureMin ?: "—"} / ${scenario.pressureMean?.formatOneDecimal() ?: "—"} / ${scenario.pressureMax ?: "—"}")
                Text("X mean / SD: ${scenario.xMean?.formatOneDecimal() ?: "—"} / ${scenario.xStandardDeviation?.formatOneDecimal() ?: "—"}; Y mean / SD: ${scenario.yMean?.formatOneDecimal() ?: "—"} / ${scenario.yStandardDeviation?.formatOneDecimal() ?: "—"}")
                Text("Byte 1 value frequencies: ${scenario.byte1ValueFrequencies.entries.joinToString { (value, count) -> "0x${value.toString(16).padStart(2, '0').uppercase()}=$count" }}")
                Text("Byte 8 value frequencies: ${scenario.byte8ValueFrequencies.entries.joinToString { (value, count) -> "0x${value.toString(16).padStart(2, '0').uppercase()}=$count" }}")
                Text("RAW first / middle / last:")
                scenario.representativeRaw.forEach { Text(it) }
                scenario.markedTransitions.takeIf { it.isNotEmpty() }?.forEach { Text("Toggle phase: $it") }
            }
        }
    }
    Text("Exploratory bit probability P(bit=1 | scenario), in percent; not a definitive parser classification")
    analysis.bits.forEach { bit ->
        val values = bit.probabilitiesByScenario.filterValues { it != null }
            .entries.joinToString { (id, value) -> "$id=${value!!.formatOneDecimal()}%" }
        Text("byte${bit.byteIndex}.bit${bit.bit}: $values")
    }
    Text("Controlled comparisons")
    analysis.comparisons.forEach { Text("• $it") }
    Text("These probabilities are historical experiment diagnostics only; tip/contact, hover, and proximity are not inferred as confirmed fields by this screen.")
}

@Composable
private fun RoundThreeSummary(sessions: List<HidExperimentSession>) {
    val analysis = remember(sessions) { RoundThreeExperimentAnalyzer.analyze(sessions) }
    Text("ROUND 3 — strictly marked segments; parser remains unchanged")
    Text("Captures must use Interface 0 / EP 0x81. Every MARK records monotonic time, wall time, and is linked at export to the nearest RAW report index. Samples within ±50 ms of each MARK are excluded.")
    Text("Position equivalence requires each segment mean to remain within <50 raw units of the first segment on both X and Y.")
    if (analysis.segments.isEmpty()) {
        Text("No Round 3 captures yet. Run the four Round 3 scenarios, then return here.")
        return
    }
    Text("Historical statistical classifications are not used by the CTL-472 physical parser. Contact interpretation remains UNKNOWN.")
    Text("Position control: ${analysis.positionControlStatus}")
    analysis.conclusions.forEach { Text(it) }
    Text("Per-segment results. B8 probabilities are bit0…bit7.")
    analysis.segments.forEach { row ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${row.test} / ${row.segmentIndex}: ${row.physicalState} — ${row.reportCount} reports (#${row.startReportIndex}–#${row.endReportIndex})")
                Text("X mean ${row.meanX?.formatOneDecimal() ?: "—"}, SD ${row.stdX?.formatOneDecimal() ?: "—"}; Y mean ${row.meanY?.formatOneDecimal() ?: "—"}, SD ${row.stdY?.formatOneDecimal() ?: "—"}")
                Text("Pressure mean ${row.pressureMean?.formatOneDecimal() ?: "—"} (range ${row.pressureMin ?: "—"}..${row.pressureMax ?: "—"})")
                Text("B1.bit0=${row.bitProbabilitiesPercent["b1.bit0"]?.formatOneDecimal() ?: "—"}%; B1.bit1=${row.bitProbabilitiesPercent["b1.bit1"]?.formatOneDecimal() ?: "—"}%")
                Text("B8 bits 0..7: ${(0..7).joinToString(" / ") { bit -> row.bitProbabilitiesPercent["b8.bit$bit"]?.formatOneDecimal() ?: "—" }} %")
                if (row.pressureConditionalB1Bit0Percent.isNotEmpty()) {
                    Text("P(B1.bit0=1 | pressure bin): ${row.pressureConditionalB1Bit0Percent.entries.joinToString { (range, probability) -> "$range=${probability?.formatOneDecimal() ?: "n/a"}%" }}")
                }
            }
        }
    }
}

private fun endpointHex(address: Int): String = "0x${address.toString(16).padStart(2, '0').uppercase()}"
private fun Double.formatOneDecimal(): String = String.format(java.util.Locale.US, "%.1f", this)
