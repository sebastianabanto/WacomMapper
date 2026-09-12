package com.example.wacommapper.usb

data class ParsedCtl472Report(
    val reportId: Int,
    val candidateFlags: Int,
    val candidateX: Int,
    val candidateY: Int,
    val candidatePressure: Int,
    val byte6Raw: Int,
    val byte8Raw: Int,
    val byte9Raw: Int,
    val xByteOrder: CandidateByteOrder,
    val yByteOrder: CandidateByteOrder,
    val pressureByteOrder: CandidateByteOrder,
    val raw: ByteArray,
    val rawHex: String,
) {
    val flagBytes: Map<Int, Int>
        get() = mapOf(1 to candidateFlags, 6 to byte6Raw, 8 to byte8Raw, 9 to byte9Raw)
}

enum class CandidateByteOrder { LITTLE_ENDIAN, BIG_ENDIAN, UNCERTAIN }

data class EndianEvidence(
    val selected: CandidateByteOrder,
    val littleEndianActivity: Int,
    val bigEndianActivity: Int,
) {
    val selectedLabel: String
        get() = when (selected) {
            CandidateByteOrder.LITTLE_ENDIAN -> "little-endian (experimental evidence)"
            CandidateByteOrder.BIG_ENDIAN -> "big-endian (experimental evidence)"
            CandidateByteOrder.UNCERTAIN -> "uncertain; displaying little-endian provisionally"
        }
}

/**
 * Experimental CTL-472 candidate decoder. Offsets and inferred byte order are
 * hypotheses from controlled captures, not a final protocol definition.
 */
class WacomCtl472ReportParser {
    fun parse(
        report: HidRawReport,
        xOrder: CandidateByteOrder = CandidateByteOrder.LITTLE_ENDIAN,
        yOrder: CandidateByteOrder = CandidateByteOrder.LITTLE_ENDIAN,
        pressureOrder: CandidateByteOrder = CandidateByteOrder.LITTLE_ENDIAN,
    ): ParsedCtl472Report? {
        if (report.interfaceId != REQUIRED_INTERFACE_ID ||
            report.endpointAddress != REQUIRED_ENDPOINT_ADDRESS ||
            report.bytes.size != REQUIRED_REPORT_LENGTH ||
            report.vendorId != REQUIRED_VENDOR_ID ||
            report.productId != REQUIRED_PRODUCT_ID
        ) return null

        val bytes = report.bytes
        fun u8(index: Int) = bytes[index].toInt() and 0xFF
        fun value16(offset: Int, order: CandidateByteOrder): Int {
            val first = u8(offset)
            val second = u8(offset + 1)
            return if (order == CandidateByteOrder.BIG_ENDIAN) (first shl 8) or second else first or (second shl 8)
        }

        return ParsedCtl472Report(
            reportId = u8(0),
            candidateFlags = u8(1),
            candidateX = value16(2, xOrder),
            candidateY = value16(4, yOrder),
            candidatePressure = value16(6, pressureOrder),
            byte6Raw = u8(6),
            byte8Raw = u8(8),
            byte9Raw = u8(9),
            xByteOrder = xOrder,
            yByteOrder = yOrder,
            pressureByteOrder = pressureOrder,
            raw = bytes.copyOf(),
            rawHex = report.hex,
        )
    }

    companion object {
        const val REQUIRED_INTERFACE_ID = 0
        const val REQUIRED_ENDPOINT_ADDRESS = 0x81
        const val REQUIRED_REPORT_LENGTH = 10
        const val REQUIRED_VENDOR_ID = 0x056A
        const val REQUIRED_PRODUCT_ID = 0x037A
    }
}

enum class HypothesisStatus { CONFIRMED, UNCERTAIN }

data class ParserHypothesis(
    val status: HypothesisStatus,
    val evidence: String,
    val candidate: String? = null,
)

data class FlagBitEvidence(
    val byteOffset: Int,
    val bitIndex: Int,
    val hoverOnePercent: Int?,
    val tipDownOnePercent: Int?,
    val tipUpDownTransitions: Int,
    val sideButtonTransitions: Int,
    val outOfRangeOnePercent: Int?,
    val proximityCandidate: Boolean,
    val tipConfirmed: Boolean,
    val sideButtonConfirmed: Boolean,
)

data class ParsedEdgeBounds(
    val minX: Int?,
    val maxX: Int?,
    val minY: Int?,
    val maxY: Int?,
) {
    val xUsable: Boolean get() = minX != null && maxX != null && maxX > minX
    val yUsable: Boolean get() = minY != null && maxY != null && maxY > minY
}

data class ParsedPressureBounds(val min: Int?, val max: Int?) {
    val usable: Boolean get() = min != null && max != null && max > min
}

data class Ctl472ParserEvidence(
    val xByteOrder: EndianEvidence,
    val yByteOrder: EndianEvidence,
    val pressureByteOrder: EndianEvidence,
    val x: ParserHypothesis,
    val y: ParserHypothesis,
    val pressure: ParserHypothesis,
    val tip: ParserHypothesis,
    val sideButton: ParserHypothesis,
    val edgeBounds: ParsedEdgeBounds,
    val pressureBounds: ParsedPressureBounds,
    val hoverPressureNearZero: Boolean?,
    val flagBits: List<FlagBitEvidence>,
    val possibleProximityBits: List<Pair<Int, Int>>,
)

object WacomCtl472EvidenceAnalyzer {
    private const val MIN_REPORTS = 10
    private val flagOffsets = listOf(1, 6, 8, 9)

    fun analyze(sessions: List<HidExperimentSession>): Ctl472ParserEvidence {
        val validSessions = sessions.filter { it.interfaceId == 0 && it.endpointAddress == 0x81 }
        val parser = WacomCtl472ReportParser()
        val rawByScenario = validSessions.groupBy { it.scenario.id }.mapValues { (_, group) ->
            group.flatMap { session ->
                session.reports.filter {
                    it.vendorId == WacomCtl472ReportParser.REQUIRED_VENDOR_ID &&
                        it.productId == WacomCtl472ReportParser.REQUIRED_PRODUCT_ID &&
                        it.bytes.size == WacomCtl472ReportParser.REQUIRED_REPORT_LENGTH
                }
            }
        }
        val xByteOrder = inferByteOrder(rawByScenario["MOVE_X_ONLY"].orEmpty(), 2)
        val yByteOrder = inferByteOrder(rawByScenario["MOVE_Y_ONLY"].orEmpty(), 4)
        val pressureByteOrder = inferByteOrder(rawByScenario["PRESSURE_CENTER"].orEmpty(), 6)
        val parsedByScenario = rawByScenario.mapValues { (_, reports) ->
            reports.asSequence()
                .filter { (it.bytes[0].toInt() and 0xFF) == 0x02 }
                .mapNotNull { parser.parse(it, xByteOrder.selected, yByteOrder.selected, pressureByteOrder.selected) }
                .toList()
        }
        val xMove = candidateXs(parsedByScenario["MOVE_X_ONLY"].orEmpty())
        val yMove = candidateYs(parsedByScenario["MOVE_Y_ONLY"].orEmpty())
        val pressureMove = candidatePressures(parsedByScenario["PRESSURE_CENTER"].orEmpty())
        val pressureX = candidateXs(parsedByScenario["PRESSURE_CENTER"].orEmpty())
        val pressureY = candidateYs(parsedByScenario["PRESSURE_CENTER"].orEmpty())
        val hoverPressure = candidatePressures(parsedByScenario["HOVER_CENTER"].orEmpty())
        val pressureValues = parsedByScenario["PRESSURE_CENTER"].orEmpty().map { it.candidatePressure }
        val xPositionStable = isRelativelyStable(pressureX, xMove)
        val yPositionStable = isRelativelyStable(pressureY, yMove)
        val pressureRange = valueRange(pressureMove)
        val hoverMaxPressure = hoverPressure.maxOrNull()
        val hoverPressureNearZero = if (hoverPressure.size >= MIN_REPORTS && pressureValues.size >= MIN_REPORTS) {
            val fullExcursion = (pressureValues.maxOrNull() ?: 0) - (hoverPressure.minOrNull() ?: 0)
            fullExcursion > 0 && (hoverMaxPressure ?: Int.MAX_VALUE) <= maxOf(1, fullExcursion / 20)
        } else null

        val xStatus = if (xByteOrder.selected != CandidateByteOrder.UNCERTAIN && yByteOrder.selected != CandidateByteOrder.UNCERTAIN &&
            xMove.size >= MIN_REPORTS && yMove.size >= MIN_REPORTS &&
            valueRange(xMove) > 0 && valueRange(xMove) >= valueRange(yMove) * 3L
        ) ParserHypothesis(HypothesisStatus.CONFIRMED, "MOVE_X_ONLY range is at least 3x MOVE_Y_ONLY and byte order has movement evidence", "bytes 2-3 ${xByteOrder.selected.name.lowercase()}")
        else ParserHypothesis(HypothesisStatus.UNCERTAIN, "Need >=$MIN_REPORTS reports, strong movement separation, and distinguishable byte order", "bytes 2-3 ${xByteOrder.selectedLabel}")

        val yStatus = if (yByteOrder.selected != CandidateByteOrder.UNCERTAIN && xByteOrder.selected != CandidateByteOrder.UNCERTAIN &&
            xMove.size >= MIN_REPORTS && yMove.size >= MIN_REPORTS &&
            valueRange(yMove) > 0 && valueRange(yMove) >= valueRange(xMove) * 3L
        ) ParserHypothesis(HypothesisStatus.CONFIRMED, "MOVE_Y_ONLY range is at least 3x MOVE_X_ONLY and byte order has movement evidence", "bytes 4-5 ${yByteOrder.selected.name.lowercase()}")
        else ParserHypothesis(HypothesisStatus.UNCERTAIN, "Need >=$MIN_REPORTS reports, strong movement separation, and distinguishable byte order", "bytes 4-5 ${yByteOrder.selectedLabel}")

        val hoverPressureRange = valueRange(hoverPressure)
        val pressureConfirmed = pressureMove.size >= MIN_REPORTS && hoverPressure.size >= MIN_REPORTS &&
            pressureByteOrder.selected != CandidateByteOrder.UNCERTAIN &&
            pressureRange >= maxOf(1L, hoverPressureRange) * 3L && xPositionStable && yPositionStable && hoverPressureNearZero == true
        val pressureStatus = if (pressureConfirmed) {
            ParserHypothesis(HypothesisStatus.CONFIRMED, "Pressure candidate varies while X/Y stay stable; hover candidate is near zero", "bytes 6-7 ${pressureByteOrder.selected.name.lowercase()}")
        } else {
            ParserHypothesis(HypothesisStatus.UNCERTAIN, "Need pressure movement, stable X/Y, near-zero hover, and distinguishable byte order", "bytes 6-7 ${pressureByteOrder.selectedLabel}")
        }

        // Flag comparison must retain special 10-byte RAW reports (including non-0x02 IDs).
        fun standardReports(scenarioId: String): List<HidRawReport> = rawByScenario[scenarioId].orEmpty()
            .filter { (it.bytes[0].toInt() and 0xFF) == STANDARD_REPORT_ID }
        val hover = standardReports("HOVER_CENTER")
        val tipDown = standardReports("TIP_DOWN_CENTER")
        val tipUpDown = standardReports("TIP_UP_DOWN")
        // Keep button analysis on zero-pressure standard reports; special records and
        // incidental tip contact during this scenario must not masquerade as button bits.
        val sideButton = standardReports("SIDE_BUTTON_HOVER").filter { report ->
            raw16Pressure(report) == 0
        }
        val outOfRange = standardReports("OUT_OF_RANGE")
        val bitRows = flagOffsets.flatMap { offset ->
            (0..7).map { bit ->
                val hoverSeries = hover.map { rawBit(it, offset, bit) }
                val tipDownSeries = tipDown.map { rawBit(it, offset, bit) }
                val alternatingSeries = tipUpDown.map { rawBit(it, offset, bit) }
                val buttonSeries = sideButton.map { rawBit(it, offset, bit) }
                val outSeries = outOfRange.map { rawBit(it, offset, bit) }
                val stableHover = isStable(hoverSeries)
                val stableTip = isStable(tipDownSeries)
                val tipChangedState = stableHover && stableTip && hoverSeries.firstOrNull() != tipDownSeries.firstOrNull()
                val tipReproducible = tipDownSeries.size >= MIN_REPORTS && alternatingSeries.size >= MIN_REPORTS &&
                    tipChangedState && transitions(alternatingSeries) >= 2
                val buttonReproducible = buttonSeries.size >= MIN_REPORTS && hoverSeries.size >= MIN_REPORTS &&
                    stableHover && transitions(buttonSeries) >= 2 && buttonSeries.toSet().size == 2 &&
                    transitions(alternatingSeries) == 0
                val proximityCandidate = outSeries.size >= MIN_REPORTS && hoverSeries.size >= MIN_REPORTS &&
                    isStable(outSeries) && stableHover && outSeries.firstOrNull() != hoverSeries.firstOrNull()
                FlagBitEvidence(
                    byteOffset = offset,
                    bitIndex = bit,
                    hoverOnePercent = onePercent(hoverSeries),
                    tipDownOnePercent = onePercent(tipDownSeries),
                    tipUpDownTransitions = transitions(alternatingSeries),
                    sideButtonTransitions = transitions(buttonSeries),
                    outOfRangeOnePercent = onePercent(outSeries),
                    proximityCandidate = proximityCandidate,
                    tipConfirmed = tipReproducible,
                    sideButtonConfirmed = buttonReproducible,
                )
            }
        }
        val tipCandidate = bitRows.firstOrNull { it.tipConfirmed }
        val buttonCandidate = bitRows.firstOrNull { it.sideButtonConfirmed }
        val tipStatus = tipCandidate?.let {
            ParserHypothesis(HypothesisStatus.CONFIRMED, "TIP_DOWN_CENTER differs from hover and TIP_UP_DOWN repeats transitions", "byte ${it.byteOffset}, bit ${it.bitIndex}")
        } ?: ParserHypothesis(HypothesisStatus.UNCERTAIN, "Need stable hover/tip-down difference and repeated TIP_UP_DOWN transitions")
        val buttonStatus = buttonCandidate?.let {
            ParserHypothesis(HypothesisStatus.CONFIRMED, "SIDE_BUTTON_HOVER bit toggles repeatedly while HOVER_CENTER stays stable", "byte ${it.byteOffset}, bit ${it.bitIndex}")
        } ?: ParserHypothesis(HypothesisStatus.UNCERTAIN, "Need repeated side-button bit transitions and stable hover baseline")

        val edgeX = parsedByScenario["EDGES_X"].orEmpty().map { it.candidateX }
        val edgeY = parsedByScenario["EDGES_Y"].orEmpty().map { it.candidateY }
        val bounds = ParsedEdgeBounds(
            edgeX.minOrNull().takeIf { edgeX.size >= MIN_REPORTS },
            edgeX.maxOrNull().takeIf { edgeX.size >= MIN_REPORTS },
            edgeY.minOrNull().takeIf { edgeY.size >= MIN_REPORTS },
            edgeY.maxOrNull().takeIf { edgeY.size >= MIN_REPORTS },
        )
        val observedPressure = parsedByScenario["PRESSURE_CENTER"].orEmpty().map { it.candidatePressure }
        val pressureBounds = ParsedPressureBounds(
            observedPressure.minOrNull().takeIf { observedPressure.size >= MIN_REPORTS },
            observedPressure.maxOrNull().takeIf { observedPressure.size >= MIN_REPORTS },
        )

        return Ctl472ParserEvidence(
            xByteOrder = xByteOrder,
            yByteOrder = yByteOrder,
            pressureByteOrder = pressureByteOrder,
            x = xStatus,
            y = yStatus,
            pressure = pressureStatus,
            tip = tipStatus,
            sideButton = buttonStatus,
            edgeBounds = bounds,
            pressureBounds = pressureBounds,
            hoverPressureNearZero = hoverPressureNearZero,
            flagBits = bitRows,
            possibleProximityBits = bitRows.filter { it.proximityCandidate }.map { it.byteOffset to it.bitIndex },
        )
    }

    private fun candidateXs(reports: List<ParsedCtl472Report>) = reports.map { it.candidateX }
    private fun candidateYs(reports: List<ParsedCtl472Report>) = reports.map { it.candidateY }
    private fun candidatePressures(reports: List<ParsedCtl472Report>) = reports.map { it.candidatePressure }
    private fun inferByteOrder(reports: List<HidRawReport>, offset: Int): EndianEvidence {
        if (reports.size < MIN_REPORTS) return EndianEvidence(CandidateByteOrder.UNCERTAIN, 0, 0)
        val firstByteActivity = reports.map { it.bytes[offset].toInt() and 0xFF }
            .zipWithNext().count { (a, b) -> a != b }
        val secondByteActivity = reports.map { it.bytes[offset + 1].toInt() and 0xFF }
            .zipWithNext().count { (a, b) -> a != b }
        val selected = when {
            firstByteActivity > 0 && firstByteActivity >= secondByteActivity * 3 -> CandidateByteOrder.LITTLE_ENDIAN
            secondByteActivity > 0 && secondByteActivity >= firstByteActivity * 3 -> CandidateByteOrder.BIG_ENDIAN
            else -> CandidateByteOrder.UNCERTAIN
        }
        return EndianEvidence(selected, firstByteActivity, secondByteActivity)
    }
    private fun valueRange(values: List<Int>): Long = (values.maxOrNull()?.toLong() ?: 0L) - (values.minOrNull()?.toLong() ?: 0L)
    private fun isRelativelyStable(values: List<Int>, movement: List<Int>): Boolean = values.size >= MIN_REPORTS &&
        movement.size >= MIN_REPORTS && valueRange(values) * 10 <= valueRange(movement)
    private fun bitAt(value: Int, bit: Int): Int = (value shr bit) and 1
    private fun rawBit(report: HidRawReport, byteOffset: Int, bit: Int): Int =
        ((report.bytes[byteOffset].toInt() and 0xFF) shr bit) and 1
    private fun transitions(values: List<Int>): Int = values.zipWithNext().count { (a, b) -> a != b }
    private fun isStable(values: List<Int>): Boolean = values.size >= MIN_REPORTS &&
        (values.count { it == 1 }.toDouble() / values.size >= 0.95 || values.count { it == 0 }.toDouble() / values.size >= 0.95)
    private fun onePercent(values: List<Int>): Int? = if (values.isEmpty()) null else values.count { it == 1 } * 100 / values.size
    private fun raw16Pressure(report: HidRawReport): Int =
        (report.bytes[6].toInt() and 0xFF) or ((report.bytes[7].toInt() and 0xFF) shl 8)

    private const val STANDARD_REPORT_ID = 0x02
}
