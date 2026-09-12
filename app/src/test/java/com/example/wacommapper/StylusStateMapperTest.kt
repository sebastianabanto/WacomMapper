package com.example.wacommapper

import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.StylusEventType
import com.example.wacommapper.mapping.StylusPresenceState
import com.example.wacommapper.mapping.StylusStateMapper
import com.example.wacommapper.usb.Ctl472RawReportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusStateMapperTest {
    private val parser = Ctl472RawReportParser()

    @Test
    fun firstHoverReportEntersHoverAndEmitsEnterThenMove() {
        val update = StylusStateMapper().process(report(pressure = 0))

        assertEquals(listOf(StylusEventType.HOVER_ENTER, StylusEventType.HOVER_MOVE), update.events.map { it.type })
        assertEquals(StylusPresenceState.HOVER, update.state.state)
        assertTrue(update.state.hover)
    }

    @Test
    fun firstReportAlreadyAboveThresholdEmitsEnterThenDown() {
        val update = StylusStateMapper().process(report(pressure = 20))

        assertEquals(listOf(StylusEventType.HOVER_ENTER, StylusEventType.DOWN), update.events.map { it.type })
        assertEquals(StylusPresenceState.CONTACT, update.state.state)
    }

    @Test
    fun contactMovementAndLiftUsePressureHysteresis() {
        val mapper = StylusStateMapper()
        mapper.process(report(pressure = 0))
        assertEquals(listOf(StylusEventType.DOWN), mapper.process(report(pressure = 20)).events.map { it.type })
        assertEquals(listOf(StylusEventType.MOVE), mapper.process(report(pressure = 500)).events.map { it.type })
        val lift = mapper.process(report(pressure = 0))
        assertEquals(listOf(StylusEventType.UP), lift.events.map { it.type })
        assertEquals(StylusPresenceState.HOVER, lift.state.state)
    }

    @Test
    fun outOfRangeFromHoverExitsAndClearsActiveValues() {
        val mapper = StylusStateMapper()
        mapper.process(report(pressure = 0))
        val update = mapper.process(parser.parse(outOfRange()))

        assertEquals(listOf(StylusEventType.HOVER_EXIT, StylusEventType.OUT_OF_RANGE), update.events.map { it.type })
        assertEquals(StylusPresenceState.OUT_OF_RANGE, update.state.state)
        assertFalse(update.state.present)
        assertFalse(update.state.contact)
        assertFalse(update.state.sideButton1)
        assertFalse(update.state.sideButton2)
        assertEquals(0, update.state.pressureRaw)
        assertNull(update.state.normalizedX)
        assertNull(update.state.normalizedY)
    }

    @Test
    fun outOfRangeFromContactEmitsUpBeforeOutOfRange() {
        val mapper = StylusStateMapper()
        mapper.process(report(pressure = 0))
        mapper.process(report(pressure = 20))
        val update = mapper.process(parser.parse(outOfRange()))

        assertEquals(listOf(StylusEventType.UP, StylusEventType.OUT_OF_RANGE), update.events.map { it.type })
        assertEquals(StylusPresenceState.OUT_OF_RANGE, update.state.state)
    }

    @Test
    fun repeatedOutOfRangeReportsDoNotEmitDuplicateExitEvents() {
        val mapper = StylusStateMapper()
        mapper.process(report(pressure = 0))
        val first = mapper.process(parser.parse(outOfRange()))
        val repeated = mapper.process(parser.parse(outOfRange()))

        assertEquals(listOf(StylusEventType.HOVER_EXIT, StylusEventType.OUT_OF_RANGE), first.events.map { it.type })
        assertTrue(repeated.events.isEmpty())
        assertEquals(StylusPresenceState.OUT_OF_RANGE, repeated.state.state)
    }

    @Test
    fun buttonTransitionsEmitOnlyOnEdges() {
        val mapper = StylusStateMapper()
        val down = mapper.process(report(status = 0xC2))
        assertTrue(down.events.any { it.type == StylusEventType.BUTTON_PRIMARY_DOWN })
        val held = mapper.process(report(status = 0xC2))
        assertFalse(held.events.any { it.type == StylusEventType.BUTTON_PRIMARY_DOWN })
        val up = mapper.process(report(status = 0xC0))
        assertTrue(up.events.any { it.type == StylusEventType.BUTTON_PRIMARY_UP })
    }

    @Test
    fun secondaryButtonEmitsItsOwnTransition() {
        val mapper = StylusStateMapper()
        assertTrue(mapper.process(report(status = 0xC4)).events.any { it.type == StylusEventType.BUTTON_SECONDARY_DOWN })
        assertTrue(mapper.process(report(status = 0xC0)).events.any { it.type == StylusEventType.BUTTON_SECONDARY_UP })
    }

    @Test
    fun pressureThresholdsUseHysteresisWithoutChatter() {
        val mapper = StylusStateMapper(tipDownThreshold = 5, tipUpThreshold = 2)
        val states = listOf(0, 6, 4, 3, 2).map { pressure ->
            val update = mapper.process(report(pressure = pressure))
            update.state.state to update.events.map { it.type }
        }

        assertEquals(StylusPresenceState.HOVER, states[0].first)
        assertTrue(states[1].second.contains(StylusEventType.DOWN))
        assertEquals(StylusPresenceState.CONTACT, states[1].first)
        assertEquals(StylusPresenceState.CONTACT, states[2].first)
        assertEquals(StylusPresenceState.CONTACT, states[3].first)
        assertEquals(listOf(StylusEventType.UP), states[4].second)
        assertEquals(StylusPresenceState.HOVER, states[4].first)
    }

    @Test
    fun thresholdsCanBeChangedAndStatusBitZeroRemainsDiagnosticOnly() {
        val mapper = StylusStateMapper()
        mapper.setThresholds(down = 10, up = 4)
        val update = mapper.process(report(status = 0xC1, pressure = 5))

        assertEquals(StylusPresenceState.HOVER, update.state.state)
        assertTrue(update.state.statusBit0 == true)
        assertFalse(update.state.contact)
    }

    @Test
    fun normalizedPressureAndCoordinatesClampToFullCanvas() {
        val mapper = StylusStateMapper()
        val update = mapper.process(report(x = 15_200, y = 9_500, pressure = 2_047))

        assertEquals(1f, update.state.normalizedX!!, 0f)
        assertEquals(1f, update.state.normalizedY!!, 0f)
        assertEquals(1f, update.state.pressureNormalized, 0f)
        assertEquals(500f to 250f, CoordinateMapper.mapToCanvas(7_600, 4_750, 1_000f, 500f))
        assertEquals(0f to 0f, CoordinateMapper.mapToCanvas(-100, -100, 1_000f, 500f))
    }

    private fun report(
        status: Int = 0xC0,
        x: Int = 1_000,
        y: Int = 2_000,
        pressure: Int = 0,
    ) = parser.parse(
        byteArrayOf(
            0x02,
            status.toByte(),
            x.toByte(), (x ushr 8).toByte(),
            y.toByte(), (y ushr 8).toByte(),
            pressure.toByte(), (pressure ushr 8).toByte(),
            0x10,
            0x00,
        ),
    )

    private fun outOfRange() = byteArrayOf(0x02, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
}
