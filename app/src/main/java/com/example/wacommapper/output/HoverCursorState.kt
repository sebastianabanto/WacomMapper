package com.example.wacommapper.output

import com.example.wacommapper.mapping.StylusEventType

data class HoverCursorVisualState(
    val visible: Boolean = false,
    val screenX: Float = 0f,
    val screenY: Float = 0f,
)

/** Pure event-to-visibility policy; coordinates are already the final display pixels. */
object HoverCursorStateReducer {
    fun onEvent(
        current: HoverCursorVisualState,
        event: MappedStylusEvent,
        sessionActive: Boolean = true,
    ): HoverCursorVisualState {
        if (!sessionActive) return current.copy(visible = false)

        val visible = when (event.eventType) {
            StylusEventType.HOVER_ENTER,
            StylusEventType.HOVER_MOVE,
            StylusEventType.UP,
            -> true

            StylusEventType.DOWN,
            StylusEventType.MOVE,
            StylusEventType.HOVER_EXIT,
            StylusEventType.OUT_OF_RANGE,
            -> false

            else -> current.visible
        }

        // Keep the exact final coordinates passed to ShizukuInputManagerBackend; do not remap.
        return HoverCursorVisualState(visible, event.x, event.y)
    }
}
