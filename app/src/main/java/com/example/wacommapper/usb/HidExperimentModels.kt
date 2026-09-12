package com.example.wacommapper.usb

data class HidExperimentScenario(
    val id: String,
    val title: String,
    val instructions: String,
    val durationSeconds: Int,
)

data class HidExperimentSession(
    val scenario: HidExperimentScenario,
    val interfaceId: Int,
    val endpointAddress: Int,
    val packetSize: Int,
    val startedAt: Long,
    val endedAt: Long,
    val reports: List<HidRawReport>,
    val phaseMarkers: List<ExperimentPhaseMarker> = emptyList(),
)

data class ExperimentPhaseMarker(
    val monotonicTimestamp: Long,
    val wallTimestamp: Long,
    val phase: String,
    val nearestReportIndex: Int? = null,
)

data class ByteStatistics(
    val index: Int,
    val min: Int,
    val max: Int,
    val distinctValues: Int,
    val changes: Int,
    val changeFrequencyHz: Double,
) {
    val range: Int get() = max - min
}

data class Candidate16Statistics(
    val name: String,
    val byteOffsets: IntRange,
    val littleEndianRange: Long,
    val littleEndianDistinct: Int,
    val littleEndianChanges: Int,
    val bigEndianRange: Long,
    val bigEndianDistinct: Int,
    val bigEndianChanges: Int,
)

data class ExperimentSessionAnalysis(
    val session: HidExperimentSession,
    val bytes: List<ByteStatistics>,
    val candidates16: List<Candidate16Statistics>,
    val reportsPerSecond: Double,
    val varyingByteCount: Int,
    val approximateEntropyBits: Double,
)

object HidExperimentScenarios {
    val all = listOf(
        HidExperimentScenario("OUT_OF_RANGE", "OUT OF RANGE", "Retira completamente el stylus de la tableta y no lo acerques.", 2),
        HidExperimentScenario("HOVER_CENTER", "HOVER CENTER", "Coloca el stylus suspendido aproximadamente en el centro, sin tocar la superficie.", 3),
        HidExperimentScenario("TIP_DOWN_CENTER", "TIP DOWN CENTER", "Apoya suavemente la punta en el centro y mantenla quieta.", 3),
        HidExperimentScenario("PRESSURE_CENTER", "PRESSURE CENTER", "Mantén la punta en el centro. Empieza con poca presión, aumenta progresivamente y luego disminuye.", 5),
        HidExperimentScenario("MOVE_X_ONLY", "MOVE X ONLY", "Sin tocar la superficie, mueve el stylus lentamente de izquierda a derecha manteniendo aproximadamente la misma altura Y.", 5),
        HidExperimentScenario("MOVE_Y_ONLY", "MOVE Y ONLY", "Sin tocar la superficie, mueve el stylus lentamente de arriba hacia abajo manteniendo aproximadamente la misma posición X.", 5),
        HidExperimentScenario("DIAGONAL", "DIAGONAL", "Mueve el stylus desde la esquina superior izquierda hasta la esquina inferior derecha.", 5),
        HidExperimentScenario("SIDE_BUTTON_HOVER", "SIDE BUTTON HOVER", "Mantén el stylus quieto en hover y pulsa/suelta el botón lateral varias veces.", 5),
        HidExperimentScenario("TIP_UP_DOWN", "TIP UP / DOWN", "Mantén el stylus en el mismo punto y alterna entre hover y contacto varias veces.", 5),
        HidExperimentScenario("EDGES_X", "EDGES X", "Coloca el stylus primero cerca del extremo izquierdo y luego cerca del extremo derecho.", 5),
        HidExperimentScenario("EDGES_Y", "EDGES Y", "Coloca el stylus primero cerca del extremo superior y luego cerca del extremo inferior.", 5),
    )

    val roundTwo = listOf(
        HidExperimentScenario("STATIC_HOVER", "STATIC HOVER", "Mantén el stylus inmóvil en hover, sin tocar y sin pulsar botones.", 5),
        HidExperimentScenario("STATIC_TIP_LIGHT", "STATIC TIP — LIGHT", "En la misma posición, apoya la punta con presión mínima estable. No pulses el botón lateral.", 5),
        HidExperimentScenario("STATIC_TIP_MEDIUM", "STATIC TIP — MEDIUM", "Mantén la misma posición con presión media constante. No pulses botones.", 5),
        HidExperimentScenario("STATIC_TIP_MAX", "STATIC TIP — HIGH", "Mantén la misma posición con presión alta estable, sin exceder lo cómodo. No pulses botones.", 5),
        HidExperimentScenario("SIDE_BUTTON_HOLD_HOVER", "SIDE BUTTON HOLD — HOVER", "Mantén el stylus quieto en hover y el botón lateral PRESIONADO durante toda la captura. No toques.", 5),
        HidExperimentScenario("SIDE_BUTTON_RELEASE_HOVER", "SIDE BUTTON RELEASE — HOVER", "Misma posición en hover que el escenario anterior; botón lateral totalmente LIBERADO durante toda la captura.", 5),
        HidExperimentScenario("SIDE_BUTTON_HOLD_TIP", "SIDE BUTTON HOLD — TIP", "Apoya suavemente la punta y mantén el botón lateral PRESIONADO; conserva posición y presión.", 5),
        HidExperimentScenario("TIP_TOGGLE_STATIC", "TIP TOGGLE — STATIC POSITION", "Mantén X/Y en el mismo punto. Empieza en hover. Alterna solo hover ↔ contacto cuatro veces; no uses el botón lateral. Toca MARK cada vez que cambies físicamente de estado.", 8),
    )

    val roundThree = listOf(
        HidExperimentScenario("BUTTON_TOGGLE_STATIC", "BUTTON TOGGLE — STATIC HOVER", "Stylus siempre en hover, sin tocar. Colócalo en el punto elegido y espera a que X/Y se estabilicen; entonces marca BUTTON_RELEASED. Luego alterna PRESIONADO/LIBERADO marcando cada cambio. Secuencia marcada: R→P→R→P→R.", 12),
        HidExperimentScenario("TIP_TOGGLE_STATIC_V2", "TIP TOGGLE V2 — STATIC POSITION", "No uses side button. Coloca el stylus en el punto elegido y espera a estabilizar X/Y en hover; marca HOVER. Alterna contacto muy suave y hover, marcando cada transición. Secuencia marcada: H→T→H→T→H.", 12),
        HidExperimentScenario("PRESSURE_WITHOUT_STATE_ANALYSIS", "PRESSURE LEVELS — KEEP CONTACT", "Apoya la punta sin levantarla y mantén el punto estable. Cuando la presión mínima esté estable, marca PRESSURE_MINIMUM; luego marca MEDIA, ALTA y vuelve a MÍNIMA sin levantar la punta.", 12),
        HidExperimentScenario("STATIC_BASELINE", "STATIC BASELINE — HOVER", "Mantén el stylus inmóvil en hover durante toda la captura, sin contacto y sin side button.", 5),
    )
}

data class RoundTwoScenarioStatistics(
    val scenarioId: String,
    val reportCount: Int,
    val byte1ValueFrequencies: Map<Int, Int>,
    val byte8ValueFrequencies: Map<Int, Int>,
    val byte1BitOnePercent: List<Double?>,
    val byte8BitOnePercent: List<Double?>,
    val pressureMin: Int?,
    val pressureMean: Double?,
    val pressureMax: Int?,
    val xMean: Double?,
    val xStandardDeviation: Double?,
    val yMean: Double?,
    val yStandardDeviation: Double?,
    val representativeRaw: List<String>,
    val markedTransitions: List<String>,
)

data class RoundTwoBitEvidence(
    val byteIndex: Int,
    val bit: Int,
    val probabilitiesByScenario: Map<String, Double?>,
    val confidence: HidEvidenceConfidence,
    val interpretation: String,
)

data class RoundTwoExperimentAnalysis(
    val scenarios: List<RoundTwoScenarioStatistics>,
    val bits: List<RoundTwoBitEvidence>,
    val comparisons: List<String>,
)

/** Statistical view of only the new controlled captures; it does not alter parser output. */
object RoundTwoExperimentAnalyzer {
    private val ids = HidExperimentScenarios.roundTwo.map { it.id }
    private const val STANDARD_REPORT_ID = 0x02

    fun analyze(sessions: List<HidExperimentSession>): RoundTwoExperimentAnalysis {
        val sessionsById = sessions.filter { it.scenario.id in ids }.associateBy { it.scenario.id }
        val reportsById = ids.associateWith { id ->
            sessionsById[id]?.reports.orEmpty().filter { report ->
                report.interfaceId == 0 && report.endpointAddress == 0x81 && report.bytes.size == 10 &&
                    (report.bytes[0].toInt() and 0xFF) == STANDARD_REPORT_ID
            }
        }
        val stats = ids.mapNotNull { id ->
            val reports = reportsById[id].orEmpty()
            if (reports.isEmpty()) return@mapNotNull null
            val byte1 = reports.map { it.bytes[1].u8() }
            val byte8 = reports.map { it.bytes[8].u8() }
            val x = reports.map { it.le16(2) }
            val y = reports.map { it.le16(4) }
            val pressure = reports.map { it.le16(6) }
            val session = sessionsById[id]
            val markers = session?.phaseMarkers.orEmpty().sortedBy { it.wallTimestamp }
            val toggleTransitions = if (id == "TIP_TOGGLE_STATIC") {
                val tipBits = reports.map { it to (it.bytes[8].u8() and (1 shl 3) != 0) }
                markers.mapIndexedNotNull { index, marker ->
                    val nextTime = markers.getOrNull(index + 1)?.wallTimestamp ?: Long.MAX_VALUE
                    val inside = tipBits.filter { (report, _) -> report.timestamp in marker.wallTimestamp until nextTime }
                    val onePercent = inside.takeIf { it.isNotEmpty() }?.count { it.second }?.times(100.0)?.div(inside.size)
                    "${marker.phase}@${marker.monotonicTimestamp}ms/report#${marker.nearestReportIndex ?: "?"}: ${inside.size} reports; b8.bit3=${onePercent?.let { percent(it) } ?: "no reports"}"
                }
            } else emptyList()
            RoundTwoScenarioStatistics(
                scenarioId = id,
                reportCount = reports.size,
                byte1ValueFrequencies = byte1.groupingBy { it }.eachCount().toSortedMap(),
                byte8ValueFrequencies = byte8.groupingBy { it }.eachCount().toSortedMap(),
                byte1BitOnePercent = bitProbabilities(byte1),
                byte8BitOnePercent = bitProbabilities(byte8),
                pressureMin = pressure.minOrNull(),
                pressureMean = pressure.average(),
                pressureMax = pressure.maxOrNull(),
                xMean = x.average(),
                xStandardDeviation = standardDeviation(x),
                yMean = y.average(),
                yStandardDeviation = standardDeviation(y),
                representativeRaw = listOf(reports.first(), reports[reports.size / 2], reports.last()).distinctBy { it.hex }.map { it.hex },
                markedTransitions = toggleTransitions,
            )
        }
        val bits = (listOf(1, 8).flatMap { byte -> (0..7).map { byte to it } }).map { (byte, bit) ->
            val probabilities = ids.associateWith { id ->
                reportsById[id].orEmpty().takeIf { it.isNotEmpty() }
                    ?.count { (it.bytes[byte].u8() shr bit and 1) == 1 }
                    ?.times(100.0)?.div(reportsById[id].orEmpty().size)
            }
            val (confidence, meaning) = confidenceFor(byte, bit, reportsById, sessionsById)
            RoundTwoBitEvidence(byte, bit, probabilities, confidence, meaning)
        }
        return RoundTwoExperimentAnalysis(stats, bits, comparisonNotes(stats))
    }

    private fun confidenceFor(
        byte: Int,
        bit: Int,
        reports: Map<String, List<HidRawReport>>,
        sessions: Map<String, HidExperimentSession>,
    ): Pair<HidEvidenceConfidence, String> {
        fun probability(id: String): Double? = reports[id].orEmpty().takeIf { it.size >= 100 }
            ?.let { values -> values.count { (it.bytes[byte].u8() shr bit and 1) == 1 } * 100.0 / values.size }
        val enough = ids.filter { it != "TIP_TOGGLE_STATIC" }.all { (reports[it]?.size ?: 0) >= 100 }
        val staticIds = listOf("STATIC_HOVER", "STATIC_TIP_LIGHT", "STATIC_TIP_MEDIUM", "STATIC_TIP_MAX", "SIDE_BUTTON_HOLD_HOVER", "SIDE_BUTTON_RELEASE_HOVER", "SIDE_BUTTON_HOLD_TIP")
        val stablePosition = staticIds.all { id ->
            val samples = reports[id].orEmpty()
            samples.size >= 100 && standardDeviation(samples.map { it.le16(2) }) <= 152.0 &&
                standardDeviation(samples.map { it.le16(4) }) <= 95.0
        }
        val hover = probability("STATIC_HOVER")
        val light = probability("STATIC_TIP_LIGHT")
        val medium = probability("STATIC_TIP_MEDIUM")
        val max = probability("STATIC_TIP_MAX")
        val holdHover = probability("SIDE_BUTTON_HOLD_HOVER")
        val releaseHover = probability("SIDE_BUTTON_RELEASE_HOVER")
        val holdTip = probability("SIDE_BUTTON_HOLD_TIP")
        val toggle = reports["TIP_TOGGLE_STATIC"].orEmpty()
        val toggleSession = sessions["TIP_TOGGLE_STATIC"]
        val toggleHasMarkers = (toggleSession?.phaseMarkers?.size ?: 0) >= 4
        val meansPressure = ids.associateWith { id -> reports[id].orEmpty().map { it.le16(6) }.average() }
        val lightPressure = meansPressure["STATIC_TIP_LIGHT"] ?: Double.NaN
        val mediumPressure = meansPressure["STATIC_TIP_MEDIUM"] ?: Double.NaN
        val maxPressure = meansPressure["STATIC_TIP_MAX"] ?: Double.NaN
        val pressureIndependent = byte == 8 && bit == 3 && listOf(hover, light, medium, max).all { it != null } &&
            lightPressure.isFinite() && mediumPressure.isFinite() && maxPressure.isFinite() &&
            lightPressure < mediumPressure && mediumPressure < maxPressure &&
            maxOf(light!!, medium!!, max!!) - minOf(light, medium, max) <= 5.0 &&
            kotlin.math.abs(hover!! - light) >= 90.0
        val tipTransitionsAlign = if (byte == 8 && bit == 3 && toggleHasMarkers) {
            val markers = toggleSession!!.phaseMarkers.sortedBy { it.wallTimestamp }
            markers.count { marker ->
                val next = markers.getOrNull(markers.indexOf(marker) + 1)?.wallTimestamp ?: Long.MAX_VALUE
                val group = toggle.filter { it.timestamp in marker.wallTimestamp until next }
                val p = group.takeIf { it.isNotEmpty() }?.count { (it.bytes[8].u8() and 8) != 0 }?.times(100.0)?.div(group.size)
                p != null && if (marker.phase == "TIP") p >= 95.0 else p <= 5.0
            } >= 4
        } else false
        if (enough && stablePosition && byte == 8 && bit == 3 && hover != null && light != null &&
            kotlin.math.abs(hover - light) >= 90.0 && tipTransitionsAlign && pressureIndependent
        ) return HidEvidenceConfidence.CONFIRMED to "Tip/contact: tracks marked physical transitions at stable X/Y; pressure-level controls also required."
        if (enough && stablePosition && byte == 1 && bit == 1 && holdHover != null && releaseHover != null &&
            kotlin.math.abs(holdHover - releaseHover) >= 90.0 && (holdTip == null || kotlin.math.abs(holdTip - light.orZero()) >= 80.0) &&
            hover?.let { kotlin.math.abs(it - releaseHover) <= 10.0 } == true &&
            listOf("SIDE_BUTTON_HOLD_HOVER", "SIDE_BUTTON_RELEASE_HOVER").all { id ->
                val pressureValues = reports[id].orEmpty().map { it.le16(6) }
                pressureValues.isNotEmpty() && pressureValues.maxOrNull()!! <= 10
            }
        ) return HidEvidenceConfidence.CONFIRMED to "Side button: isolated by held/released hover and supported by held-tip control."
        if (byte == 1 && bit == 1 && (light != null && medium != null && max != null) &&
            light == medium && medium == max
        ) return HidEvidenceConfidence.UNKNOWN to "No pressure-level variation detected; controlled capture evidence is not sufficient for another meaning."
        val hasContrast = listOf(hover, light, medium, max, holdHover, releaseHover, holdTip).filterNotNull().distinct().size > 1
        return if (hasContrast) HidEvidenceConfidence.STRONG_CANDIDATE to "Bit differs across controlled states, but one or more confirmation gates (sample count, stable position, pressure independence, or transition alignment) remain unmet."
        else HidEvidenceConfidence.UNKNOWN to "Insufficient contrasting evidence."
    }

    private fun comparisonNotes(stats: List<RoundTwoScenarioStatistics>): List<String> {
        val byId = stats.associateBy { it.scenarioId }
        fun contrast(a: String, b: String, label: String): String {
            val left = byId[a]
            val right = byId[b]
            return if (left == null || right == null) "$label: missing capture(s)"
            else "$label: ${a} n=${left.reportCount}, pressure=${triple(left.pressureMin, left.pressureMean, left.pressureMax)}; ${b} n=${right.reportCount}, pressure=${triple(right.pressureMin, right.pressureMean, right.pressureMax)}; bit probabilities shown below."
        }
        return listOf(
            contrast("STATIC_HOVER", "STATIC_TIP_LIGHT", "Tip isolation"),
            contrast("STATIC_TIP_LIGHT", "STATIC_TIP_MEDIUM", "Pressure level light vs medium"),
            contrast("STATIC_TIP_MEDIUM", "STATIC_TIP_MAX", "Pressure level medium vs high"),
            contrast("SIDE_BUTTON_RELEASE_HOVER", "SIDE_BUTTON_HOLD_HOVER", "Side button isolated in hover"),
            contrast("STATIC_TIP_LIGHT", "SIDE_BUTTON_HOLD_TIP", "Side button with contact"),
            "TIP_TOGGLE_STATIC: ${byId["TIP_TOGGLE_STATIC"]?.markedTransitions?.joinToString("; ") ?: "no phase markers / capture"}",
        )
    }

    private fun bitProbabilities(values: List<Int>): List<Double?> = (0..7).map { bit ->
        values.takeIf { it.isNotEmpty() }?.count { (it shr bit and 1) == 1 }?.times(100.0)?.div(values.size)
    }
    private fun standardDeviation(values: List<Int>): Double = if (values.isEmpty()) 0.0 else {
        val mean = values.average()
        kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
    private fun Byte.u8() = toInt() and 0xFF
    private fun HidRawReport.le16(offset: Int) = bytes[offset].u8() or (bytes[offset + 1].u8() shl 8)
    private fun percent(value: Double) = "${"%.1f".format(java.util.Locale.US, value)}%"
    private fun Double?.orZero() = this ?: 0.0
    private fun triple(min: Int?, mean: Double?, max: Int?) = if (min == null) "n/a" else "$min / ${"%.1f".format(java.util.Locale.US, mean)} / $max"
}

data class RoundThreeSegmentStatistics(
    val test: String,
    val segmentIndex: Int,
    val physicalState: String,
    val reportCount: Int,
    val meanX: Double?,
    val meanY: Double?,
    val stdX: Double?,
    val stdY: Double?,
    val pressureMean: Double?,
    val pressureMin: Int?,
    val pressureMax: Int?,
    val bitProbabilitiesPercent: Map<String, Double?>,
    val pressureConditionalB1Bit0Percent: Map<String, Double?>,
    val startReportIndex: Int?,
    val endReportIndex: Int?,
)

data class RoundThreeAssessment(
    val segments: List<RoundThreeSegmentStatistics>,
    val sideButtonStatus: String,
    val sideButtonBit: String,
    val tipStatus: String,
    val tipBit: String,
    val positionControlStatus: String,
    val conclusions: List<String>,
)

/** Analyzes marked physical states, excluding +/-50 ms around every transition. */
object RoundThreeExperimentAnalyzer {
    private const val TRANSITION_EXCLUSION_MS = 50L
    private val requiredIds = setOf(
        "BUTTON_TOGGLE_STATIC", "TIP_TOGGLE_STATIC_V2",
        "PRESSURE_WITHOUT_STATE_ANALYSIS", "STATIC_BASELINE",
    )

    fun analyze(sessions: List<HidExperimentSession>): RoundThreeAssessment {
        val roundSessions = sessions.filter { it.scenario.id in requiredIds }
        val segmentRows = roundSessions.flatMap(::segmentsFor)
        val button = segmentRows.filter { it.test == "BUTTON_TOGGLE_STATIC" }
        val tip = segmentRows.filter { it.test == "TIP_TOGGLE_STATIC_V2" }
        val pressure = segmentRows.filter { it.test == "PRESSURE_WITHOUT_STATE_ANALYSIS" }
        val buttonStates = listOf("BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED")
        val tipStates = listOf("HOVER", "TIP_DOWN", "HOVER", "TIP_DOWN", "HOVER")
        val buttonSequenceComplete = button.map { it.physicalState } == buttonStates && button.all { it.reportCount >= 10 }
        val tipSequenceComplete = tip.map { it.physicalState } == tipStates && tip.all { it.reportCount >= 10 }
        val buttonPositionPass = equivalentPosition(button)
        val tipPositionPass = equivalentPosition(tip)
        val buttonPressuresZero = button.isNotEmpty() && button.all { it.pressureMax == 0 }
        val sideProbabilities = button.mapNotNull { row -> row.bitProbabilitiesPercent["b1.bit1"]?.let { row.physicalState to it } }
        val sideStatePass = buttonSequenceComplete && sideProbabilities.size == button.size && sideProbabilities.all { (state, p) ->
            if (state == "BUTTON_PRESSED") p >= 95.0 else p <= 5.0
        }
        val sideAlternativePass = classificationAccuracy(button, "BUTTON_PRESSED", 1, 1) >= 98.0 &&
            classificationAccuracy(button, "BUTTON_PRESSED", 1, 1) >= bestOtherAccuracy(button, "BUTTON_PRESSED")
        val sideConfirmed = sideStatePass && buttonPressuresZero && buttonPositionPass && sideAlternativePass

        val tipBitCandidates = listOf(1 to 0) + (0..7).map { 8 to it }
        val tipScores = tipBitCandidates.map { (byte, bit) ->
            Triple(byte, bit, classificationAccuracy(tip, "TIP_DOWN", byte, bit))
        }.sortedWith(compareByDescending<Triple<Int, Int, Double>> { it.third }.thenBy { if (it.first == 1) 0 else 1 })
        val bestTip = tipScores.firstOrNull()
        val tipBitCandidate = bestTip?.let { "byte${it.first}.bit${it.second}" } ?: "undetermined"
        val tipPatternPass = tipSequenceComplete && bestTip != null && bestTip.third >= 98.0
        val sideReleasedDuringTip = tip.isNotEmpty() && tip.all { (it.bitProbabilitiesPercent["b1.bit1"] ?: 100.0) <= 5.0 }
        val pressureContactKept = pressure.map { it.physicalState }.let { it == listOf("PRESSURE_MINIMUM", "PRESSURE_MEDIUM", "PRESSURE_HIGH", "PRESSURE_MINIMUM") } &&
            pressure.size == 4 && pressure.all { (it.bitProbabilitiesPercent["b1.bit0"] ?: 0.0) >= 95.0 } &&
            pressure.mapNotNull { it.pressureMean }.let { values -> values.size == 4 && values[0] < values[1] && values[1] < values[2] && values[3] < values[1] }
        val tipConfirmed = tipPatternPass && tipPositionPass && sideReleasedDuringTip && pressureContactKept &&
            bestTip?.let { it.first == 1 && it.second == 0 } == true

        val overallPosition = when {
            buttonPositionPass && tipPositionPass -> "PASS"
            button.isNotEmpty() || tip.isNotEmpty() -> "POSITION_CONTROL_FAILED"
            else -> "INCOMPLETE"
        }
        val sideStatus = when {
            sideConfirmed -> "CONFIRMED"
            buttonSequenceComplete && buttonPositionPass && buttonPressuresZero && !sideStatePass -> "REJECTED"
            else -> "STRONG_CANDIDATE"
        }
        val tipStatus = when {
            tipConfirmed -> "CONFIRMED"
            tipSequenceComplete && tipPositionPass && bestTip != null && bestTip.third < 70.0 -> "REJECTED"
            else -> "STRONG_CANDIDATE"
        }
        val conclusions = buildList {
            add("Side button confirmation gates: sequence=${if (buttonSequenceComplete) "PASS" else "INCOMPLETE"}, position=${if (buttonPositionPass) "PASS" else "POSITION_CONTROL_FAILED"}, pressure-zero=${if (buttonPressuresZero) "PASS" else "FAIL/INCOMPLETE"}, b1.bit1-pattern=${if (sideStatePass) "PASS" else "FAIL/INCOMPLETE"}, competing-bit check=${if (sideAlternativePass) "PASS" else "FAIL/INCOMPLETE"}.")
            add("Tip confirmation gates: sequence=${if (tipSequenceComplete) "PASS" else "INCOMPLETE"}, position=${if (tipPositionPass) "PASS" else "POSITION_CONTROL_FAILED"}, best bit=${tipBitCandidate} accuracy=${bestTip?.third?.let(::fmtPercent) ?: "n/a"}, side released=${if (sideReleasedDuringTip) "PASS" else "FAIL/INCOMPLETE"}, pressure-only contact control=${if (pressureContactKept) "PASS" else "FAIL/INCOMPLETE"}.")
            if (button.isEmpty()) add("BUTTON_TOGGLE_STATIC has not been captured.")
            if (tip.isEmpty()) add("TIP_TOGGLE_STATIC_V2 has not been captured.")
            if (pressure.size != 4) add("PRESSURE_WITHOUT_STATE_ANALYSIS needs all four marked pressure segments.")
            if (button.isNotEmpty() && !buttonPositionPass) add("POSITION_CONTROL_FAILED: at least one button segment mean differs from the initial segment by 50 raw units or more on X or Y.")
            if (tip.isNotEmpty() && !tipPositionPass) add("POSITION_CONTROL_FAILED: at least one tip segment mean differs from the initial segment by 50 raw units or more on X or Y.")
            if (bestTip != null) add("Tip bit ranking: ${tipScores.joinToString { "byte${it.first}.bit${it.second}=${fmtPercent(it.third)}" }}.")
        }
        return RoundThreeAssessment(segmentRows, sideStatus, "byte1.bit1", tipStatus, tipBitCandidate, overallPosition, conclusions)
    }

    private fun segmentsFor(session: HidExperimentSession): List<RoundThreeSegmentStatistics> {
        val reports = session.reports.filter { report ->
            report.interfaceId == 0 && report.endpointAddress == 0x81 && report.bytes.size == 10 && report.u8(0) == 0x02
        }.sortedBy { it.timestamp }
        val markers = session.phaseMarkers.sortedBy { it.wallTimestamp }
        return markers.mapIndexedNotNull { index, marker ->
            val starts = marker.wallTimestamp + TRANSITION_EXCLUSION_MS
            val ends = (markers.getOrNull(index + 1)?.wallTimestamp ?: session.endedAt) - TRANSITION_EXCLUSION_MS
            val segment = reports.filter { it.timestamp >= starts && it.timestamp < ends }
            if (segment.isEmpty()) return@mapIndexedNotNull null
            val xs = segment.map { it.le16(2) }
            val ys = segment.map { it.le16(4) }
            val ps = segment.map { it.le16(6) }
            val probabilities = buildMap {
                (listOf(1, 8)).forEach { byte -> (0..7).forEach { bit ->
                    put("b$byte.bit$bit", segment.count { (it.u8(byte) shr bit and 1) == 1 } * 100.0 / segment.size)
                } }
            }
            val pressureBins = listOf(0..0, 1..255, 256..511, 512..1023, 1024..1535, 1536..2047)
            val pressureConditional = if (session.scenario.id == "PRESSURE_WITHOUT_STATE_ANALYSIS") {
                pressureBins.associate { range ->
                    val inBin = segment.filter { it.le16(6) in range }
                    val label = if (range.first == range.last) "${range.first}" else "${range.first}-${range.last}"
                    label to inBin.takeIf { it.isNotEmpty() }?.count { it.u8(1) and 1 == 1 }?.times(100.0)?.div(inBin.size)
                }
            } else emptyMap()
            val startIndex = reports.indexOf(segment.first())
            val endIndex = reports.indexOf(segment.last())
            RoundThreeSegmentStatistics(
                test = session.scenario.id,
                segmentIndex = index + 1,
                physicalState = marker.phase,
                reportCount = segment.size,
                meanX = xs.average(), meanY = ys.average(),
                stdX = stddev(xs), stdY = stddev(ys),
                pressureMean = ps.average(), pressureMin = ps.minOrNull(), pressureMax = ps.maxOrNull(),
                bitProbabilitiesPercent = probabilities,
                pressureConditionalB1Bit0Percent = pressureConditional,
                startReportIndex = startIndex,
                endReportIndex = endIndex,
            )
        }
    }

    private fun equivalentPosition(rows: List<RoundThreeSegmentStatistics>): Boolean {
        if (rows.size < 2 || rows.any { it.meanX == null || it.meanY == null }) return false
        val anchor = rows.first()
        return rows.drop(1).all { row ->
            kotlin.math.abs(row.meanX!! - anchor.meanX!!) < 50.0 &&
                kotlin.math.abs(row.meanY!! - anchor.meanY!!) < 50.0
        }
    }

    private fun classificationAccuracy(rows: List<RoundThreeSegmentStatistics>, positiveState: String, byte: Int, bit: Int): Double {
        val applicable = rows.filter { it.physicalState == positiveState || it.physicalState in negativeStates(positiveState) }
        val total = applicable.sumOf { it.reportCount }
        if (total == 0) return 0.0
        val correct = applicable.sumOf { row ->
            val positive = row.physicalState == positiveState
            val probability = row.bitProbabilitiesPercent["b$byte.bit$bit"] ?: return@sumOf 0
            val correctFraction = if (positive) probability else 100.0 - probability
            (correctFraction * row.reportCount / 100.0).toInt()
        }
        return correct * 100.0 / total
    }

    private fun bestOtherAccuracy(rows: List<RoundThreeSegmentStatistics>, positiveState: String): Double =
        (listOf(1, 8).flatMap { byte -> (0..7).map { byte to it } }
            .filterNot { it.first == 1 && it.second == 1 })
            .maxOfOrNull { (byte, bit) -> classificationAccuracy(rows, positiveState, byte, bit) } ?: 0.0

    private fun negativeStates(positive: String): Set<String> = when (positive) {
        "BUTTON_PRESSED" -> setOf("BUTTON_RELEASED")
        "TIP_DOWN" -> setOf("HOVER")
        else -> emptySet()
    }
    private fun HidRawReport.u8(offset: Int) = bytes[offset].toInt() and 0xFF
    private fun HidRawReport.le16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
    private fun stddev(values: List<Int>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
    private fun fmtPercent(value: Double) = "${"%.1f".format(java.util.Locale.US, value)}%"
}

object HidExperimentAnalyzer {
    private val candidateOffsets = listOf(2, 4, 6)

    fun analyze(session: HidExperimentSession): ExperimentSessionAnalysis {
        val reportCount = session.reports.size
        val maxLength = session.reports.maxOfOrNull { it.bytes.size } ?: 0
        val elapsedSeconds = ((session.endedAt - session.startedAt).coerceAtLeast(1L)) / 1_000.0
        val bytes = (0 until maxLength).map { index ->
            val values = session.reports.mapNotNull { it.bytes.getOrNull(index)?.toInt()?.and(0xFF) }
            val transitions = values.zipWithNext().count { (before, after) -> before != after }
            ByteStatistics(
                index = index,
                min = values.minOrNull() ?: 0,
                max = values.maxOrNull() ?: 0,
                distinctValues = values.distinct().size,
                changes = transitions,
                changeFrequencyHz = transitions / elapsedSeconds,
            )
        }
        val candidates = candidateOffsets.mapNotNull { lowOffset ->
            val lowHighPairs = session.reports.mapNotNull { report ->
                val bytes = report.bytes
                if (bytes.size <= lowOffset + 1) null else {
                    val first = bytes[lowOffset].toInt() and 0xFF
                    val second = bytes[lowOffset + 1].toInt() and 0xFF
                    (first to second)
                }
            }
            if (lowHighPairs.isEmpty()) return@mapNotNull null
            val little = lowHighPairs.map { (low, high) -> low.toLong() + (high.toLong() shl 8) }
            val big = lowHighPairs.map { (low, high) -> (low.toLong() shl 8) + high }
            Candidate16Statistics(
                name = "Candidate16_${'A' + lowOffset / 2 - 1}",
                byteOffsets = lowOffset..(lowOffset + 1),
                littleEndianRange = (little.maxOrNull() ?: 0) - (little.minOrNull() ?: 0),
                littleEndianDistinct = little.distinct().size,
                littleEndianChanges = little.zipWithNext().count { (a, b) -> a != b },
                bigEndianRange = (big.maxOrNull() ?: 0) - (big.minOrNull() ?: 0),
                bigEndianDistinct = big.distinct().size,
                bigEndianChanges = big.zipWithNext().count { (a, b) -> a != b },
            )
        }
        val varyingBytes = bytes.count { it.distinctValues > 1 }
        val entropy = (0 until maxLength).map { index ->
            val frequencies = session.reports.mapNotNull { it.bytes.getOrNull(index)?.toInt()?.and(0xFF) }
                .groupingBy { it }
                .eachCount()
            frequencies.values.sumOf { frequency ->
                val probability = frequency.toDouble() / reportCount.coerceAtLeast(1)
                -probability * (kotlin.math.ln(probability) / kotlin.math.ln(2.0))
            }
        }.average().takeIf { it.isFinite() } ?: 0.0
        return ExperimentSessionAnalysis(
            session = session,
            bytes = bytes,
            candidates16 = candidates,
            reportsPerSecond = reportCount / elapsedSeconds,
            varyingByteCount = varyingBytes,
            approximateEntropyBits = entropy,
        )
    }

    fun movementVariationEvidence(sessions: List<HidExperimentSession>): Double {
        val analyzed = sessions.associateBy({ it.scenario.id }, ::analyze)
        val x = analyzed["MOVE_X_ONLY"] ?: return 0.0
        val y = analyzed["MOVE_Y_ONLY"] ?: return 0.0
        val hover = analyzed["HOVER_CENTER"]
        val byteCount = minOf(x.bytes.size, y.bytes.size)
        val byteVariation = (0 until byteCount).sumOf { index ->
            (maxOf(x.bytes[index].range, y.bytes[index].range) - (hover?.bytes?.getOrNull(index)?.range ?: 0)).coerceAtLeast(0)
        }
        val xPairs = x.candidates16.associateBy { it.name }
        val yPairs = y.candidates16.associateBy { it.name }
        val hoverPairs = hover?.candidates16?.associateBy { it.name }.orEmpty()
        val pairVariation = xPairs.keys.intersect(yPairs.keys).sumOf { name ->
            val movingRange = maxOf(xPairs.getValue(name).littleEndianRange, yPairs.getValue(name).littleEndianRange)
            (movingRange - (hoverPairs[name]?.littleEndianRange ?: 0L)).coerceAtLeast(0L)
        }
        return (byteVariation + pairVariation).toDouble()
    }
}

enum class HidEvidenceConfidence { CONFIRMED, STRONG_CANDIDATE, UNKNOWN }

data class RawByteScenarioProfile(
    val scenarioId: String,
    val reportCount: Int,
    val min: Int?,
    val max: Int?,
    val distinctValues: Int,
    val firstHex: String?,
    val middleHex: String?,
    val lastHex: String?,
    val changingBits: List<Int>,
    val transitionsByBit: List<Int>,
    val onePercentByBit: List<Int?>,
)

data class RawBytePairComparison(
    val leftScenarioId: String,
    val rightScenarioId: String,
    val leftChangingBits: List<Int>,
    val rightChangingBits: List<Int>,
    val stateDifferenceBits: List<Int>,
)

data class RawByteFieldComparison(
    val byteIndex: Int,
    val possibleMeaning: String,
    val confidence: HidEvidenceConfidence,
    val scenarios: List<RawByteScenarioProfile>,
    val pairs: List<RawBytePairComparison>,
)

data class PressureMaskAudit(
    val mask: Int,
    val reportsByScenario: Map<String, Int>,
    val rangeByScenario: Map<String, LongRange?>,
    val upperBitsChangingByScenario: Map<String, List<Int>>,
    val confidence: HidEvidenceConfidence,
    val note: String,
)

data class HidScenarioComparison(
    val bytes: List<RawByteFieldComparison>,
    val pressureMasks: List<PressureMaskAudit>,
)

/** Compares saved RAW captures, including 10-byte special reports rejected by the normal parser. */
object HidScenarioComparisonAnalyzer {
    private val scenarioIds = listOf(
        "OUT_OF_RANGE", "HOVER_CENTER", "TIP_DOWN_CENTER", "PRESSURE_CENTER",
        "SIDE_BUTTON_HOVER", "MOVE_X_ONLY", "MOVE_Y_ONLY",
    )
    private val priorityPairs = listOf(
        "OUT_OF_RANGE" to "HOVER_CENTER",
        "HOVER_CENTER" to "TIP_DOWN_CENTER",
        "HOVER_CENTER" to "SIDE_BUTTON_HOVER",
        "TIP_DOWN_CENTER" to "PRESSURE_CENTER",
        "MOVE_X_ONLY" to "MOVE_Y_ONLY",
    )

    fun analyze(sessions: List<HidExperimentSession>): HidScenarioComparison {
        val rawByScenario = sessions.asSequence()
            .filter { it.interfaceId == WacomCtl472ReportParser.REQUIRED_INTERFACE_ID && it.endpointAddress == WacomCtl472ReportParser.REQUIRED_ENDPOINT_ADDRESS }
            .flatMap { session -> session.reports.asSequence().map { session.scenario.id to it } }
            .filter { (_, report) ->
                report.vendorId == WacomCtl472ReportParser.REQUIRED_VENDOR_ID &&
                    report.productId == WacomCtl472ReportParser.REQUIRED_PRODUCT_ID &&
                    report.bytes.size == WacomCtl472ReportParser.REQUIRED_REPORT_LENGTH
            }
            .groupBy({ it.first }, { it.second })

        val evidence = WacomCtl472EvidenceAnalyzer.analyze(sessions)
        val byteComparisons = (0 until WacomCtl472ReportParser.REQUIRED_REPORT_LENGTH).map { byteIndex ->
            val profiles = scenarioIds.mapNotNull { scenarioId ->
                val scenarioReports = rawByScenario[scenarioId].orEmpty()
                // Byte 0 is the report ID itself. For bytes 1..9, compare only the standard
                // 0x02 layout; special IDs remain visible in the byte-0 profile and RAW samples.
                val reports = if (byteIndex == 0) scenarioReports else scenarioReports.filter {
                    (it.bytes[0].toInt() and 0xFF) == STANDARD_REPORT_ID
                }
                reports.takeIf { it.isNotEmpty() }?.let { profile(scenarioId, byteIndex, it) }
            }
            val pairs = priorityPairs.mapNotNull { (leftId, rightId) ->
                val left = profiles.firstOrNull { it.scenarioId == leftId } ?: return@mapNotNull null
                val right = profiles.firstOrNull { it.scenarioId == rightId } ?: return@mapNotNull null
                RawBytePairComparison(
                    leftScenarioId = leftId,
                    rightScenarioId = rightId,
                    leftChangingBits = left.changingBits,
                    rightChangingBits = right.changingBits,
                    stateDifferenceBits = (0..7).filter { bit ->
                        val leftPercent = left.onePercentByBit[bit]
                        val rightPercent = right.onePercentByBit[bit]
                        leftPercent != null && rightPercent != null && kotlin.math.abs(leftPercent - rightPercent) >= 50
                    },
                )
            }
            val bitEvidence = evidence.flagBits.filter { it.byteOffset == byteIndex }
            val confirmedBits = bitEvidence.filter { it.tipConfirmed || it.sideButtonConfirmed }
            val strongBits = bitEvidence.filter { it.proximityCandidate || it.tipUpDownTransitions > 0 || it.sideButtonTransitions > 0 }
            val hoverTipChange = pairs.firstOrNull {
                it.leftScenarioId == "HOVER_CENTER" && it.rightScenarioId == "TIP_DOWN_CENTER"
            }?.stateDifferenceBits.orEmpty()
            val hoverSideChange = pairs.firstOrNull {
                it.leftScenarioId == "HOVER_CENTER" && it.rightScenarioId == "SIDE_BUTTON_HOVER"
            }?.stateDifferenceBits.orEmpty()
            val valuesAcrossScenarios = profiles.mapNotNull { profile -> profile.min to profile.max }
            val reportIdsVary = byteIndex == 0 && valuesAcrossScenarios.map { it.first to it.second }.distinct().size > 1
            val (meaning, confidence) = when {
                byteIndex in 2..3 -> "X candidate; bytes 2–3 little-endian, prior physical validation" to HidEvidenceConfidence.CONFIRMED
                byteIndex in 4..5 -> "Y candidate; bytes 4–5 little-endian, prior physical validation" to HidEvidenceConfidence.CONFIRMED
                byteIndex in 6..7 -> "Pressure candidate; pair bytes 6–7; mask not assumed" to HidEvidenceConfidence.STRONG_CANDIDATE
                byteIndex == 0 && reportIdsVary -> "Report ID / special-state candidate" to HidEvidenceConfidence.STRONG_CANDIDATE
                confirmedBits.isNotEmpty() -> {
                    val roles = buildList {
                        if (confirmedBits.any { it.tipConfirmed }) add("tip/contact")
                        if (confirmedBits.any { it.sideButtonConfirmed }) add("side button")
                    }.joinToString(" + ")
                    "$roles bit confirmed by controlled captures" to HidEvidenceConfidence.CONFIRMED
                }
                hoverTipChange.isNotEmpty() -> "Possible tip/contact bit; stable hover vs tip-down state difference, needs repeat validation" to HidEvidenceConfidence.STRONG_CANDIDATE
                hoverSideChange.isNotEmpty() && bitEvidence.any {
                    it.sideButtonTransitions > 0 && it.tipUpDownTransitions > 0
                } -> "Possible side-button/state bit, but it also changes in TIP_UP_DOWN; meaning is ambiguous" to HidEvidenceConfidence.STRONG_CANDIDATE
                hoverSideChange.isNotEmpty() -> "Possible side-button bit; hover vs side-button scenario difference, needs repeated toggles" to HidEvidenceConfidence.STRONG_CANDIDATE
                strongBits.isNotEmpty() -> "Possible state flag; inspect per-bit scenario evidence" to HidEvidenceConfidence.STRONG_CANDIDATE
                byteIndex in listOf(1, 6, 8, 9) -> "Unclassified flag/state byte" to HidEvidenceConfidence.UNKNOWN
                else -> "No assigned meaning" to HidEvidenceConfidence.UNKNOWN
            }
            RawByteFieldComparison(byteIndex, meaning, confidence, profiles, pairs)
        }

        val maskAudits = listOf(0x07FF, 0x0FFF, 0xFFFF).map { mask ->
            val relevant = listOf("HOVER_CENTER", "TIP_DOWN_CENTER", "PRESSURE_CENTER")
            val pressureReports = relevant.associateWith { scenarioId ->
                rawByScenario[scenarioId].orEmpty().filter { (it.bytes[0].toInt() and 0xFF) == 0x02 }
            }
            val reportCounts = pressureReports.mapValues { (_, reports) -> reports.size }
            val ranges = relevant.associateWith { scenarioId ->
                val values = pressureReports[scenarioId].orEmpty().map { report ->
                    val raw16 = (report.bytes[6].toInt() and 0xFF) or ((report.bytes[7].toInt() and 0xFF) shl 8)
                    raw16 and mask
                }
                values.minOrNull()?.toLong()?.let { min -> min..values.maxOrNull()!!.toLong() }
            }
            val upperChanging = relevant.associateWith { scenarioId ->
                val values = pressureReports[scenarioId].orEmpty().map { report ->
                    val raw16 = (report.bytes[6].toInt() and 0xFF) or ((report.bytes[7].toInt() and 0xFF) shl 8)
                    raw16 and mask.inv() and 0xFFFF
                }
                (0..15).filter { bit -> values.map { (it shr bit) and 1 }.distinct().size > 1 }
            }
            val enough = relevant.all { reportCounts.getValue(it) >= 10 }
            val hoverRange = ranges["HOVER_CENTER"]?.let { it.last - it.first } ?: Long.MAX_VALUE
            val pressureRange = ranges["PRESSURE_CENTER"]?.let { it.last - it.first } ?: 0L
            val separates = enough && pressureRange > maxOf(hoverRange, 0L) && pressureRange > 0L
            val observedUnmaskedRange = ranges["PRESSURE_CENTER"]
            PressureMaskAudit(
                mask = mask,
                reportsByScenario = reportCounts,
                rangeByScenario = ranges,
                upperBitsChangingByScenario = upperChanging,
                // Plausible numeric range alone cannot distinguish a protocol mask from a wider field
                // whose high bits simply were not exercised in these captures.
                confidence = HidEvidenceConfidence.UNKNOWN,
                note = when {
                    !enough -> "Need at least 10 RAW reports in hover, tip-down, and pressure captures."
                    separates && observedUnmaskedRange?.last == 2047L &&
                        upperChanging["PRESSURE_CENTER"].orEmpty().isEmpty() ->
                        "Observed pressure raw range is 0..2047 and discarded upper bits stayed zero. This is consistent with an 11-bit candidate, but cannot establish a required mask; wider masks produce the same observed values."
                    separates -> "Masked pressure excursion exceeds hover variation, but this does not establish the protocol-defined mask."
                    else -> "This mask does not yet separate pressure from hover evidence."
                },
            )
        }
        return HidScenarioComparison(byteComparisons, maskAudits)
    }

    private fun profile(scenarioId: String, byteIndex: Int, reports: List<HidRawReport>): RawByteScenarioProfile {
        val values = reports.map { it.bytes[byteIndex].toInt() and 0xFF }
        val transitions = (0..7).map { bit ->
            values.map { (it shr bit) and 1 }.zipWithNext().count { (before, after) -> before != after }
        }
        val onesPercent = (0..7).map { bit -> values.count { ((it shr bit) and 1) == 1 } * 100 / values.size }
        val middle = reports[reports.size / 2]
        return RawByteScenarioProfile(
            scenarioId = scenarioId,
            reportCount = reports.size,
            min = values.minOrNull(),
            max = values.maxOrNull(),
            distinctValues = values.distinct().size,
            firstHex = reports.first().bytes[byteIndex].hexByte(),
            middleHex = middle.bytes[byteIndex].hexByte(),
            lastHex = reports.last().bytes[byteIndex].hexByte(),
            changingBits = transitions.indices.filter { transitions[it] > 0 },
            transitionsByBit = transitions,
            onePercentByBit = onesPercent,
        )
    }

    private fun Byte.hexByte(): String = "%02X".format(toInt() and 0xFF)

    private const val STANDARD_REPORT_ID = 0x02
}
