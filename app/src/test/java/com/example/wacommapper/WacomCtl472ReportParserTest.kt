package com.example.wacommapper

import com.example.wacommapper.usb.CandidateByteOrder
import com.example.wacommapper.usb.ExperimentPhaseMarker
import com.example.wacommapper.usb.HidExperimentScenario
import com.example.wacommapper.usb.HidExperimentSession
import com.example.wacommapper.usb.HidScenarioComparisonAnalyzer
import com.example.wacommapper.usb.RoundTwoExperimentAnalyzer
import com.example.wacommapper.usb.RoundThreeExperimentAnalyzer
import com.example.wacommapper.usb.HidRawReport
import com.example.wacommapper.usb.HypothesisStatus
import com.example.wacommapper.usb.WacomCtl472EvidenceAnalyzer
import com.example.wacommapper.usb.WacomCtl472ReportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WacomCtl472ReportParserTest {
    private val parser = WacomCtl472ReportParser()

    @Test
    fun parsesExperimentalLittleEndianCandidatesOnlyForTargetReport() {
        val report = report(byteArrayOf(0x02, 0xA1.toByte(), 0x34, 0x12, 0x78, 0x56, 0x10, 0x02, 0xAB.toByte(), 0xCD.toByte()))

        val parsed = parser.parse(report)

        assertNotNull(parsed)
        assertEquals(0x02, parsed?.reportId)
        assertEquals(0xA1, parsed?.candidateFlags)
        assertEquals(0x1234, parsed?.candidateX)
        assertEquals(0x5678, parsed?.candidateY)
        assertEquals(0x0210, parsed?.candidatePressure)
        assertEquals(0xAB, parsed?.byte8Raw)
        assertEquals(0xCD, parsed?.byte9Raw)
    }

    @Test
    fun rejectsOtherInterfacesEndpointsDevicesAndLengths() {
        assertNull(parser.parse(report(ByteArray(10), interfaceId = 1)))
        assertNull(parser.parse(report(ByteArray(10), endpoint = 0x82)))
        assertNull(parser.parse(report(ByteArray(9))))
        assertNull(parser.parse(report(ByteArray(10), vendorId = 0x1234)))
        assertNull(parser.parse(report(ByteArray(10), productId = 0x0001)))
    }

    @Test
    fun infersByteOrderOnlyWhenOneLowByteActivityClearlyDominates() {
        val moveX = session("MOVE_X_ONLY", (0 until 20).map { index ->
            ByteArray(10).apply { this[2] = index.toByte() }
        })
        val moveY = session("MOVE_Y_ONLY", (0 until 20).map { index ->
            ByteArray(10).apply { this[5] = index.toByte() }
        })

        val evidence = WacomCtl472EvidenceAnalyzer.analyze(listOf(moveX, moveY))

        assertEquals(CandidateByteOrder.LITTLE_ENDIAN, evidence.xByteOrder.selected)
        assertEquals(CandidateByteOrder.BIG_ENDIAN, evidence.yByteOrder.selected)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.x.status)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.y.status)
    }

    @Test
    fun doesNotConfirmFieldsWithoutScenarioEvidence() {
        val evidence = WacomCtl472EvidenceAnalyzer.analyze(emptyList())

        assertEquals(HypothesisStatus.UNCERTAIN, evidence.x.status)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.y.status)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.pressure.status)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.tip.status)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.sideButton.status)
    }

    @Test
    fun proximityBitAnalysisDoesNotInterpretSpecialReportPayloadAsStandardFlags() {
        val outOfRange = session("OUT_OF_RANGE", List(20) {
            byteArrayOf(0xC0.toByte(), 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
        })
        val hover = session("HOVER_CENTER", List(20) {
            byteArrayOf(0x02, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        })

        val evidence = WacomCtl472EvidenceAnalyzer.analyze(listOf(outOfRange, hover))

        assertEquals(emptyList<Pair<Int, Int>>(), evidence.possibleProximityBits)
        assertEquals(HypothesisStatus.UNCERTAIN, evidence.tip.status)
    }

    @Test
    fun rawComparisonIncludesSpecialReportsAndComparesPriorityScenarios() {
        val outOfRange = session("OUT_OF_RANGE", List(12) {
            byteArrayOf(0xC0.toByte(), 0x00, 0, 0, 0, 0, 0, 0, 0, 0)
        })
        val hover = session("HOVER_CENTER", List(12) { index ->
            byteArrayOf(0x02, if (index % 2 == 0) 0x80.toByte() else 0x00.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        })

        val comparison = HidScenarioComparisonAnalyzer.analyze(listOf(outOfRange, hover))
        val byte1 = comparison.bytes[1]

        assertEquals(12, comparison.bytes[0].scenarios.first { it.scenarioId == "OUT_OF_RANGE" }.reportCount)
        assertFalse(byte1.scenarios.any { it.scenarioId == "OUT_OF_RANGE" })
        assertEquals(listOf(7), byte1.scenarios.first { it.scenarioId == "HOVER_CENTER" }.changingBits)
        assertFalse(byte1.pairs.any { it.leftScenarioId == "OUT_OF_RANGE" })
    }

    @Test
    fun pressureMaskAuditDoesNotInventEvidenceWhenScenariosAreMissing() {
        val audits = HidScenarioComparisonAnalyzer.analyze(emptyList()).pressureMasks

        assertEquals(listOf(0x07FF, 0x0FFF, 0xFFFF), audits.map { it.mask })
        assertEquals(List(3) { com.example.wacommapper.usb.HidEvidenceConfidence.UNKNOWN }, audits.map { it.confidence })
        assertEquals(0, audits.first().reportsByScenario.values.sum())
    }

    @Test
    fun pressureEvidenceIgnoresSpecialReportIdsButKeepsThemForRawComparison() {
        val hover = session("HOVER_CENTER", List(10) {
            byteArrayOf(0x02, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        } + List(10) {
            byteArrayOf(0xC0.toByte(), 0, 0, 0, 0, 0, 0xFF.toByte(), 0x7F.toByte(), 0, 0)
        })
        val pressure = session("PRESSURE_CENTER", (0 until 10).map { index ->
            byteArrayOf(0x02, 0xE0.toByte(), 0, 0, 0, 0, (index * 100).toByte(), 0, 0, 0)
        })

        val parserEvidence = WacomCtl472EvidenceAnalyzer.analyze(listOf(hover, pressure))
        val rawComparison = HidScenarioComparisonAnalyzer.analyze(listOf(hover, pressure))

        assertEquals(true, parserEvidence.hoverPressureNearZero)
        assertEquals(10, rawComparison.bytes[6].scenarios.first { it.scenarioId == "HOVER_CENTER" }.reportCount)
        assertEquals(10, rawComparison.pressureMasks.first().reportsByScenario.getValue("HOVER_CENTER"))
    }

    @Test
    fun doesNotConfirmSideButtonBitWhenSameBitAlsoTogglesInTipUpDown() {
        fun statusReports(values: List<Int>) = values.map { flag ->
            byteArrayOf(0x02, flag.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        }
        val hover = session("HOVER_CENTER", statusReports(List(20) { 0xE0 }))
        val side = session("SIDE_BUTTON_HOVER", statusReports(List(20) { if (it % 2 == 0) 0xE0 else 0xE2 }))
        val tipUpDown = session("TIP_UP_DOWN", statusReports(List(20) { if (it % 2 == 0) 0xE0 else 0xE2 }))

        val evidence = WacomCtl472EvidenceAnalyzer.analyze(listOf(hover, side, tipUpDown))

        assertEquals(HypothesisStatus.UNCERTAIN, evidence.sideButton.status)
        assertFalse(evidence.flagBits.first { it.byteOffset == 1 && it.bitIndex == 1 }.sideButtonConfirmed)
    }

    @Test
    fun confirmsTipCandidateOnlyWithStableHoverTipDifferenceAndRepeatedAlternation() {
        fun reports(values: List<Int>) = values.map { byte8 ->
            byteArrayOf(0x02, 0xE0.toByte(), 0, 0, 0, 0, 0, 0, byte8.toByte(), 0)
        }
        val hover = session("HOVER_CENTER", reports(List(20) { 0x14 }))
        val tipDown = session("TIP_DOWN_CENTER", reports(List(20) { 0x0C }))
        val alternating = session("TIP_UP_DOWN", reports(List(20) { if (it % 2 == 0) 0x14 else 0x0C }))

        val evidence = WacomCtl472EvidenceAnalyzer.analyze(listOf(hover, tipDown, alternating))

        assertEquals(HypothesisStatus.CONFIRMED, evidence.tip.status)
        assertEquals("byte 8, bit 3", evidence.tip.candidate)
    }

    @Test
    fun roundTwoAnalyzerReportsConditionalBitProbabilitiesFromStandardReportsOnly() {
        val hover = session("STATIC_HOVER", listOf(
            byteArrayOf(0x02, 0x02, 10, 0, 20, 0, 0, 0, 0x08, 0),
            byteArrayOf(0x02, 0x00, 10, 0, 20, 0, 0, 0, 0x00, 0),
            byteArrayOf(0xC0.toByte(), 0x02, 10, 0, 20, 0, 0, 0, 0x08, 0),
        ))

        val result = RoundTwoExperimentAnalyzer.analyze(listOf(hover))
        val stats = result.scenarios.single()
        val byte1Bit1 = result.bits.single { it.byteIndex == 1 && it.bit == 1 }
        val byte8Bit3 = result.bits.single { it.byteIndex == 8 && it.bit == 3 }

        assertEquals(2, stats.reportCount)
        assertEquals(mapOf(0x00 to 1, 0x02 to 1), stats.byte1ValueFrequencies)
        assertEquals(50.0, byte1Bit1.probabilitiesByScenario["STATIC_HOVER"]!!, 0.0)
        assertEquals(50.0, byte8Bit3.probabilitiesByScenario["STATIC_HOVER"]!!, 0.0)
        assertEquals(10.0, stats.xMean!!, 0.0)
        assertEquals(0.0, stats.pressureMean!!, 0.0)
    }

    @Test
    fun roundThreeConfirmsOnlyRepeatedMarkedButtonAndTipTransitionsWithPositionAndPressureControls() {
        val button = markedSession("BUTTON_TOGGLE_STATIC", listOf(
            "BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED", "BUTTON_PRESSED", "BUTTON_RELEASED",
        )) { state, _, _ -> packet(button = state == "BUTTON_PRESSED") }
        val tip = markedSession("TIP_TOGGLE_STATIC_V2", listOf("HOVER", "TIP_DOWN", "HOVER", "TIP_DOWN", "HOVER")) { state, _, _ ->
            packet(tip = state == "TIP_DOWN", pressure = if (state == "TIP_DOWN") 100 else 0)
        }
        val pressure = markedSession("PRESSURE_WITHOUT_STATE_ANALYSIS", listOf(
            "PRESSURE_MINIMUM", "PRESSURE_MEDIUM", "PRESSURE_HIGH", "PRESSURE_MINIMUM",
        )) { _, index, sample ->
            val pressureValue = listOf(100, 500, 1_200, 100)[index] + sample % 3
            packet(tip = true, pressure = pressureValue)
        }
        val baseline = markedSession("STATIC_BASELINE", listOf("HOVER")) { _, _, _ -> packet() }

        val analysis = RoundThreeExperimentAnalyzer.analyze(listOf(button, tip, pressure, baseline))

        assertEquals("CONFIRMED", analysis.sideButtonStatus)
        assertEquals("byte1.bit1", analysis.sideButtonBit)
        assertEquals("CONFIRMED", analysis.tipStatus)
        assertEquals("byte1.bit0", analysis.tipBit)
        assertEquals("PASS", analysis.positionControlStatus)
        assertEquals(5, analysis.segments.count { it.test == "BUTTON_TOGGLE_STATIC" })
        assertEquals(100.0, analysis.segments.first { it.test == "PRESSURE_WITHOUT_STATE_ANALYSIS" }
            .pressureConditionalB1Bit0Percent.getValue("1-255")!!, 0.0)
    }

    private fun markedSession(
        id: String,
        phases: List<String>,
        packetFor: (String, Int, Int) -> ByteArray,
    ): HidExperimentSession {
        val started = 1_000L
        val reports = phases.flatMapIndexed { phaseIndex, phase ->
            (0 until 30).map { sample ->
                val timestamp = started + phaseIndex * 300L + sample * 10L
                report(packetFor(phase, phaseIndex, sample), timestamp = timestamp)
            }
        }
        val markers = phases.mapIndexed { index, phase ->
            ExperimentPhaseMarker(index * 300L, started + index * 300L, phase, index * 30)
        }
        return HidExperimentSession(
            scenario = HidExperimentScenario(id, id, "test", 12),
            interfaceId = 0,
            endpointAddress = 0x81,
            packetSize = 16,
            startedAt = started,
            endedAt = started + phases.size * 300L,
            reports = reports,
            phaseMarkers = markers,
        )
    }

    private fun packet(button: Boolean = false, tip: Boolean = false, pressure: Int = 0): ByteArray = byteArrayOf(
        0x02,
        (0xE0 or (if (tip) 1 else 0) or (if (button) 2 else 0)).toByte(),
        0xE8.toByte(), 0x03, 0xD0.toByte(), 0x07,
        pressure.toByte(), (pressure shr 8).toByte(),
        (if (tip) 8 else 0).toByte(), 0,
    )

    private fun session(id: String, packets: List<ByteArray>) = HidExperimentSession(
        scenario = HidExperimentScenario(id, id, "test", 1),
        interfaceId = 0,
        endpointAddress = 0x81,
        packetSize = 16,
        startedAt = 1L,
        endedAt = 1_001L,
        reports = packets.mapIndexed { index, bytes -> report(bytes, timestamp = index.toLong()) },
    )

    private fun report(
        bytes: ByteArray,
        interfaceId: Int = 0,
        endpoint: Int = 0x81,
        vendorId: Int = 0x056A,
        productId: Int = 0x037A,
        timestamp: Long = 0L,
    ) = HidRawReport(timestamp, interfaceId, endpoint, bytes, vendorId, productId)
}
