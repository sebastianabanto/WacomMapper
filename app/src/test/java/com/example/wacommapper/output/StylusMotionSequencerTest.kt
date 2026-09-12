package com.example.wacommapper.output

import com.example.wacommapper.mapping.StylusEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusMotionSequencerTest {
    private fun event(
        type: StylusEventType,
        pressure: Float = 0f,
        button1: Boolean = false,
        button2: Boolean = false,
        x: Float = 100f,
        y: Float = 200f,
    ) = MappedStylusEvent(
        timestampMillis = 1L,
        x = x,
        y = y,
        pressure = pressure,
        tip = type == StylusEventType.DOWN || type == StylusEventType.MOVE,
        sideButton = button1,
        inRange = type != StylusEventType.OUT_OF_RANGE,
        eventType = type,
        sideButton2 = button2,
        displayId = 3,
    )

    private val sequencer = StylusMotionSequencer()

    @Test fun mapsHoverDownMoveUpAndOutOfRange() {
        assertEquals(StylusMotionAction.HOVER_MOVE, StylusMotionEventMapping.action(StylusEventType.HOVER_MOVE))
        assertEquals(StylusMotionAction.DOWN, StylusMotionEventMapping.action(StylusEventType.DOWN))
        assertEquals(StylusMotionAction.MOVE, StylusMotionEventMapping.action(StylusEventType.MOVE))
        assertEquals(StylusMotionAction.UP, StylusMotionEventMapping.action(StylusEventType.UP))
        assertNull(StylusMotionEventMapping.action(StylusEventType.OUT_OF_RANGE))
    }

    @Test fun hoverAlwaysHasZeroPressureAndContactKeepsNormalizedPressure() {
        assertEquals(0f, StylusMotionEventMapping.pressure(StylusEventType.HOVER_MOVE, 0.7f), 0f)
        assertEquals(0.5f, StylusMotionEventMapping.pressure(StylusEventType.MOVE, 0.5f), 0f)
        assertEquals(1f, StylusMotionEventMapping.pressure(StylusEventType.MOVE, 1.2f), 0f)
    }

    @Test fun downTimeRemainsStableForStrokeAndUp() {
        val down = sequencer.command(event(StylusEventType.DOWN, pressure = 0.2f), 100L, 32, 64)!!
        val move = sequencer.command(event(StylusEventType.MOVE, pressure = 0.8f), 120L, 32, 64)!!
        val up = sequencer.command(event(StylusEventType.UP), 150L, 32, 64)!!
        assertEquals(100L, down.downTimeMillis)
        assertEquals(down.downTimeMillis, move.downTimeMillis)
        assertEquals(down.downTimeMillis, up.downTimeMillis)
        assertEquals(0f, up.pressure, 0f)
        assertFalse(sequencer.hasContact())
    }

    @Test fun buttonTransitionsCarryPrimaryAndSecondaryButtonState() {
        val first = sequencer.command(event(StylusEventType.BUTTON_PRIMARY_DOWN, button1 = true), 10L, 32, 64)!!
        val second = sequencer.command(event(StylusEventType.BUTTON_SECONDARY_DOWN, button1 = true, button2 = true), 20L, 32, 64)!!
        val release = sequencer.command(event(StylusEventType.BUTTON_PRIMARY_UP, button2 = true), 30L, 32, 64)!!
        assertEquals(StylusMotionAction.BUTTON_PRESS, first.action)
        assertEquals(32, first.actionButton)
        assertEquals(32, first.buttonState)
        assertEquals(64, second.actionButton)
        assertEquals(96, second.buttonState)
        assertEquals(StylusMotionAction.BUTTON_RELEASE, release.action)
        assertEquals(32, release.actionButton)
        assertEquals(64, release.buttonState)
    }

    @Test fun coordinatesAndDisplayIdArePreserved() {
        val command = sequencer.command(event(StylusEventType.HOVER_MOVE, x = 1234f, y = 567f), 10L, 32, 64)!!
        assertEquals(1234f, command.x, 0f)
        assertEquals(567f, command.y, 0f)
        assertEquals(3, command.displayId)
    }

    @Test fun releaseAllResetClearsContactHoverAndButtons() {
        sequencer.command(event(StylusEventType.HOVER_ENTER, button1 = true), 10L, 32, 64)
        sequencer.command(event(StylusEventType.DOWN, button1 = true), 20L, 32, 64)
        assertTrue(sequencer.hasContact())
        assertEquals(32, sequencer.activeButtonState())
        sequencer.reset()
        assertFalse(sequencer.hasContact())
        assertFalse(sequencer.hasHover())
        assertEquals(0, sequencer.activeButtonState())
    }

    @Test fun backendDisconnectDisablesInjectionUntilServiceReconnects() {
        assertTrue(ShizukuConnectionPolicy.canInject(ShizukuBackendStatus.READY, true))
        assertFalse(ShizukuConnectionPolicy.canInject(ShizukuBackendStatus.READY, false))
        assertFalse(ShizukuConnectionPolicy.canInject(ShizukuBackendStatus.NOT_RUNNING, true))
        assertFalse(ShizukuConnectionPolicy.canInject(ShizukuBackendStatus.FAILED, true))
    }
}
