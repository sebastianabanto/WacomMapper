package com.example.wacommapper.output

import com.example.wacommapper.mapping.StylusEventType

enum class StylusMotionAction {
    HOVER_ENTER, HOVER_MOVE, HOVER_EXIT, DOWN, MOVE, UP, BUTTON_PRESS, BUTTON_RELEASE,
}

data class StylusMotionCommand(
    val action: StylusMotionAction,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val buttonState: Int,
    val actionButton: Int,
    val downTimeMillis: Long,
    val eventTimeMillis: Long,
    val displayId: Int,
)

/** Converts validated internal mapper events into stylus MotionEvent semantics. */
object StylusMotionEventMapping {
    fun action(type: StylusEventType): StylusMotionAction? = when (type) {
        StylusEventType.HOVER_ENTER -> StylusMotionAction.HOVER_ENTER
        StylusEventType.HOVER_MOVE -> StylusMotionAction.HOVER_MOVE
        StylusEventType.HOVER_EXIT -> StylusMotionAction.HOVER_EXIT
        StylusEventType.DOWN -> StylusMotionAction.DOWN
        StylusEventType.MOVE -> StylusMotionAction.MOVE
        StylusEventType.UP -> StylusMotionAction.UP
        StylusEventType.BUTTON_PRIMARY_DOWN,
        StylusEventType.BUTTON_SECONDARY_DOWN -> StylusMotionAction.BUTTON_PRESS
        StylusEventType.BUTTON_PRIMARY_UP,
        StylusEventType.BUTTON_SECONDARY_UP -> StylusMotionAction.BUTTON_RELEASE
        StylusEventType.OUT_OF_RANGE -> null
    }

    fun pressure(type: StylusEventType, normalized: Float, contact: Boolean = false): Float = when (type) {
        StylusEventType.HOVER_ENTER, StylusEventType.HOVER_MOVE, StylusEventType.HOVER_EXIT,
        StylusEventType.UP, StylusEventType.OUT_OF_RANGE -> 0f
        StylusEventType.BUTTON_PRIMARY_DOWN, StylusEventType.BUTTON_PRIMARY_UP,
        StylusEventType.BUTTON_SECONDARY_DOWN, StylusEventType.BUTTON_SECONDARY_UP ->
            if (contact) normalized.coerceIn(0f, 1f) else 0f
        else -> normalized.coerceIn(0f, 1f)
    }
}

/** Maintains Android's downTime and button-state invariants across one stylus stream. */
class StylusMotionSequencer {
    private var contactDownTime: Long? = null
    private var hoverDownTime: Long? = null
    private var buttons = 0

    fun command(event: MappedStylusEvent, now: Long, primaryButton: Int, secondaryButton: Int): StylusMotionCommand? {
        val type = event.eventType ?: when {
            event.tip == true -> if (contactDownTime == null) StylusEventType.DOWN else StylusEventType.MOVE
            event.inRange == true -> if (hoverDownTime == null) StylusEventType.HOVER_ENTER else StylusEventType.HOVER_MOVE
            event.inRange == false -> StylusEventType.HOVER_EXIT
            else -> return null
        }
        val action = StylusMotionEventMapping.action(type) ?: return null
        val nextButtons = (if (event.sideButton == true) primaryButton else 0) or
            (if (event.sideButton2) secondaryButton else 0)
        val actionButton = when (type) {
            StylusEventType.BUTTON_PRIMARY_DOWN, StylusEventType.BUTTON_PRIMARY_UP -> primaryButton
            StylusEventType.BUTTON_SECONDARY_DOWN, StylusEventType.BUTTON_SECONDARY_UP -> secondaryButton
            else -> 0
        }
        val eventTime = now
        val downTime = when (type) {
            StylusEventType.DOWN -> now.also { contactDownTime = it }
            StylusEventType.MOVE, StylusEventType.UP -> contactDownTime ?: now.also { contactDownTime = it }
            StylusEventType.HOVER_ENTER -> now.also { hoverDownTime = it }
            StylusEventType.HOVER_MOVE, StylusEventType.HOVER_EXIT -> hoverDownTime ?: now.also { hoverDownTime = it }
            StylusEventType.BUTTON_PRIMARY_DOWN, StylusEventType.BUTTON_PRIMARY_UP,
            StylusEventType.BUTTON_SECONDARY_DOWN, StylusEventType.BUTTON_SECONDARY_UP ->
                contactDownTime ?: hoverDownTime ?: now
            StylusEventType.OUT_OF_RANGE -> now
        }
        val command = StylusMotionCommand(
            action = action,
            x = event.x,
            y = event.y,
            pressure = StylusMotionEventMapping.pressure(type, event.pressure, event.tip == true),
            buttonState = nextButtons,
            actionButton = actionButton,
            downTimeMillis = downTime,
            eventTimeMillis = eventTime,
            displayId = event.displayId,
        )
        when (type) {
            StylusEventType.UP -> contactDownTime = null
            StylusEventType.HOVER_EXIT -> hoverDownTime = null
            StylusEventType.BUTTON_PRIMARY_DOWN, StylusEventType.BUTTON_PRIMARY_UP,
            StylusEventType.BUTTON_SECONDARY_DOWN, StylusEventType.BUTTON_SECONDARY_UP -> buttons = nextButtons
            else -> buttons = nextButtons
        }
        return command
    }

    fun activeButtonState(): Int = buttons
    fun hasContact(): Boolean = contactDownTime != null
    fun hasHover(): Boolean = hoverDownTime != null

    fun reset() {
        contactDownTime = null
        hoverDownTime = null
        buttons = 0
    }
}
