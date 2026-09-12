package com.example.wacommapper.output

import com.example.wacommapper.mapping.StylusEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoverCursorStateReducerTest {
    @Test
    fun hoverEnterShowsCursorAtExactInjectedCoordinates() {
        val event = event(StylusEventType.HOVER_ENTER, 1234.5f, 678.25f)

        val next = HoverCursorStateReducer.onEvent(HoverCursorVisualState(), event)

        assertTrue(next.visible)
        assertEquals(event.x, next.screenX, 0f)
        assertEquals(event.y, next.screenY, 0f)
    }

    @Test
    fun hoverMoveMovesCursorWithoutIndependentRemapping() {
        val moved = event(StylusEventType.HOVER_MOVE, 2200f, 1400f)

        val next = HoverCursorStateReducer.onEvent(HoverCursorVisualState(true, 20f, 30f), moved)

        assertTrue(next.visible)
        assertEquals(moved.x, next.screenX, 0f)
        assertEquals(moved.y, next.screenY, 0f)
    }

    @Test
    fun downAndContactMoveHideCursor() {
        val visible = HoverCursorVisualState(true, 500f, 600f)

        val down = HoverCursorStateReducer.onEvent(visible, event(StylusEventType.DOWN, 510f, 610f))
        val contactMove = HoverCursorStateReducer.onEvent(down, event(StylusEventType.MOVE, 520f, 620f))

        assertFalse(down.visible)
        assertFalse(contactMove.visible)
    }

    @Test
    fun upShowsCursorAtReleasePosition() {
        val next = HoverCursorStateReducer.onEvent(
            HoverCursorVisualState(false, 0f, 0f),
            event(StylusEventType.UP, 900f, 700f),
        )

        assertTrue(next.visible)
        assertEquals(900f, next.screenX, 0f)
        assertEquals(700f, next.screenY, 0f)
    }

    @Test
    fun hoverExitAndOutOfRangeHideCursor() {
        val visible = HoverCursorVisualState(true, 1f, 2f)

        assertFalse(HoverCursorStateReducer.onEvent(visible, event(StylusEventType.HOVER_EXIT)).visible)
        assertFalse(HoverCursorStateReducer.onEvent(visible, event(StylusEventType.OUT_OF_RANGE)).visible)
    }

    @Test
    fun stoppingGlobalSessionHidesCursor() {
        val next = HoverCursorStateReducer.onEvent(
            HoverCursorVisualState(true, 100f, 200f),
            event(StylusEventType.HOVER_MOVE, 100f, 200f),
            sessionActive = false,
        )

        assertFalse(next.visible)
    }

    @Test
    fun buttonEventPreservesHoverVisibility() {
        val next = HoverCursorStateReducer.onEvent(
            HoverCursorVisualState(true, 10f, 20f),
            event(StylusEventType.BUTTON_PRIMARY_DOWN, 30f, 40f),
        )

        assertTrue(next.visible)
        assertEquals(30f, next.screenX, 0f)
        assertEquals(40f, next.screenY, 0f)
    }

    private fun event(type: StylusEventType, x: Float = 0f, y: Float = 0f) = MappedStylusEvent(
        timestampMillis = 1L,
        x = x,
        y = y,
        pressure = 0f,
        tip = false,
        sideButton = false,
        inRange = true,
        eventType = type,
    )
}
