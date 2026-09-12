package com.example.wacommapper

import com.example.wacommapper.mapping.StylusStateMapper
import com.example.wacommapper.usb.Ctl472RawReportParser
import com.example.wacommapper.usb.Ctl472ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ctl472RawReportParserTest {
    private val parser = Ctl472RawReportParser()

    // Literal 10-byte samples copied from the checked-in physical capture exports.
    // wacom_round3_retry_20260912.txt:24
    private val realHover = bytes("02 E0 D1 1D 3D 16 00 00 0D 00")
    // wacom_round2_20260912.txt:700
    private val realPressure = bytes("02 E1 DC 1D C1 14 E8 00 1D 00")
    // wacom_round2_20260912.txt:2790
    private val realSideButton1 = bytes("02 E2 92 1D A5 17 00 00 10 00")
    // wacom_round3_20260912.txt:24
    private val realOutOfRange = bytes("02 80 00 00 00 00 00 00 00 00")
    // wacom_round2_20260912.txt:335
    private val realGenericId = bytes("C0 00 00 00 00 00 00 00 00 01")

    @Test
    fun parsesRealHoverSampleAndLittleEndianCoordinates() {
        val parsed = parser.parse(realHover)

        assertEquals(Ctl472ReportType.TABLET_REPORT, parsed.type)
        assertEquals(0x02, parsed.reportId)
        assertEquals(0xE0, parsed.statusByte)
        assertEquals(0x1DD1, parsed.x)
        assertEquals(0x163D, parsed.y)
        assertEquals(0, parsed.pressure)
        assertTrue(parsed.nearProximity)
    }

    @Test
    fun parsesRealPressureSampleAsUnmaskedLittleEndian16BitValue() {
        val parsed = parser.parse(realPressure)

        assertEquals(Ctl472ReportType.TABLET_REPORT, parsed.type)
        assertEquals(0x1DDC, parsed.x)
        assertEquals(0x14C1, parsed.y)
        assertEquals(0x00E8, parsed.pressure)
        assertEquals(0x1D, parsed.hoverDistance)
    }

    @Test
    fun decodesSideButtonOneFromRealButtonSample() {
        val parsed = parser.parse(realSideButton1)

        assertTrue(parsed.sideButton1)
        assertFalse(parsed.sideButton2)
        assertEquals(0x10, parsed.hoverDistance)
    }

    @Test
    fun decodesSideButtonTwoWhenStatusBitTwoIsSet() {
        val parsed = parser.parse(bytes("02 E4 01 00 02 00 00 00 FF 7A"))

        assertEquals(Ctl472ReportType.TABLET_REPORT, parsed.type)
        assertFalse(parsed.sideButton1)
        assertTrue(parsed.sideButton2)
    }

    @Test
    fun identifiesExactOutOfRangeMarker() {
        val parsed = parser.parse(realOutOfRange)

        assertEquals(Ctl472ReportType.OUT_OF_RANGE, parsed.type)
        assertNull(parsed.x)
        assertNull(parsed.y)
        assertEquals(0, parsed.pressure)
        assertFalse(parsed.sideButton1)
        assertFalse(parsed.nearProximity)
    }

    @Test
    fun non02ReportIdIsPreservedAsGenericBeforeStatusClassification() {
        val parsed = parser.parse(realGenericId)

        assertEquals(Ctl472ReportType.GENERIC_REPORT, parsed.type)
        assertEquals(0xC0, parsed.reportId)
        assertNull(parsed.x)
        assertEquals(realGenericId.toList(), parsed.raw.toList())
    }

    @Test
    fun invalidLengthIsRetainedAsRawAndNotDecoded() {
        val parsed = parser.parse(realHover.copyOf(9))

        assertEquals(Ctl472ReportType.INVALID, parsed.type)
        assertNull(parsed.reportId)
        assertNull(parsed.x)
        assertEquals(9, parsed.raw.size)
    }

    @Test
    fun byteEightIsUnsignedHoverDistanceNotAFlagSet() {
        val parsed = parser.parse(realHover)

        assertEquals(0x0D, parsed.hoverDistance)
        assertFalse(parsed.hoverDistance!! > 0xFF)
        assertEquals(0, parsed.unknownByte9)
    }

    @Test
    fun pressureLe16IsNotMaskedEvenWhenAboveReferenceMaximum() {
        val parsed = parser.parse(bytes("02 E0 00 00 00 00 F0 08 00 00"))

        assertEquals(0x08F0, parsed.pressure)
        assertEquals(false, parsed.pressureInReferenceRange)
    }

    @Test
    fun detectMaskClassificationPrecedesExactOutOfRangeStatus() {
        val parsed = parser.parse(bytes("02 C0 01 00 02 00 03 00 04 05"))

        assertEquals(Ctl472ReportType.TABLET_REPORT, parsed.type)
    }

    @Test
    fun outOfRangeMapperReleasesStateWithoutActiveCoordinatesAndKeepsDiagnosticPosition() {
        val mapper = StylusStateMapper()
        val active = mapper.map(parser.parse(realHover), 1600, 900)
        val out = mapper.map(parser.parse(realOutOfRange), 1600, 900)

        assertTrue(active.present)
        assertFalse(active.contact)
        assertTrue(active.hover)
        assertFalse(out.present)
        assertNull(out.xRaw)
        assertNull(out.yRaw)
        assertEquals(0, out.pressureRaw)
        assertFalse(out.sideButton1)
        assertNull(out.mappedX)
        assertNull(out.mappedY)
    }

    private fun bytes(hex: String) =
        hex.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
