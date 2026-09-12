package com.example.wacommapper.output

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicLong

/** Global stylus sink. ShizukuBackend owns permission and the shell UserService connection. */
class ShizukuInputManagerBackend(
    private val shizuku: ShizukuBackend,
) : StylusOutputBackend {
    private val sequencer = StylusMotionSequencer()

    @Synchronized
    override fun send(event: MappedStylusEvent): OutputResult {
        val semantic = event.eventType
        if (semantic == com.example.wacommapper.mapping.StylusEventType.OUT_OF_RANGE) {
            return releaseAllInput()
        }
        val command = sequencer.command(
            event = event,
            now = SystemClock.uptimeMillis(),
            primaryButton = MotionEvent.BUTTON_STYLUS_PRIMARY,
            secondaryButton = MotionEvent.BUTTON_STYLUS_SECONDARY,
        ) ?: return OutputResult(OutputSupport.PARTIAL, "No MotionEvent is emitted for this internal state marker.")
        val attempt = shizuku.injectManual(
            name = "LIVE_GLOBAL_${command.action}",
            action = command.action.toAndroidAction(),
            x = command.x,
            y = command.y,
            pressure = command.pressure,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            buttonState = command.buttonState,
            actionButton = command.actionButton,
            downTimeMillis = command.downTimeMillis,
            eventTimeMillis = command.eventTimeMillis,
            displayId = command.displayId,
            injectionMode = INJECT_ASYNC,
        )
        val accepted = attempt.returned == true && attempt.exception == null
        val count = sentCount.incrementAndGet()
        if (command.action !in setOf(StylusMotionAction.MOVE, StylusMotionAction.HOVER_MOVE) || count == 1L || count % 25L == 0L || !accepted) {
            Log.i(
                "STYLUS_GLOBAL",
                "action=${command.action} source=SOURCE_STYLUS tool=TOOL_TYPE_STYLUS x=${command.x} y=${command.y} " +
                    "pressure=${command.pressure} buttons=0x${command.buttonState.toString(16)} accepted=$accepted",
            )
        }
        return OutputResult(
            support = if (accepted) OutputSupport.SUPPORTED else OutputSupport.PARTIAL,
            message = attempt.detail,
            attemptedEvents = 1,
            injectedEvents = if (accepted) 1 else 0,
            rejectedEvents = if (accepted) 0 else 1,
            lastMethod = attempt.methodApi,
            lastResult = attempt.returned,
            lastException = attempt.exception,
        )
    }

    @Synchronized
    override fun releaseAllInput(): OutputResult {
        val result = shizuku.emergencyStop(0f, 0f)
        sequencer.reset()
        val accepted = result.returned == true && result.exception == null
        return OutputResult(
            support = if (accepted) OutputSupport.SUPPORTED else OutputSupport.PARTIAL,
            message = result.detail,
            attemptedEvents = 1,
            injectedEvents = if (accepted) 1 else 0,
            rejectedEvents = if (accepted) 0 else 1,
            lastMethod = result.methodApi,
            lastResult = result.returned,
            lastException = result.exception,
        )
    }

    private fun StylusMotionAction.toAndroidAction(): Int = when (this) {
        StylusMotionAction.HOVER_ENTER -> MotionEvent.ACTION_HOVER_ENTER
        StylusMotionAction.HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
        StylusMotionAction.HOVER_EXIT -> MotionEvent.ACTION_HOVER_EXIT
        StylusMotionAction.DOWN -> MotionEvent.ACTION_DOWN
        StylusMotionAction.MOVE -> MotionEvent.ACTION_MOVE
        StylusMotionAction.UP -> MotionEvent.ACTION_UP
        StylusMotionAction.BUTTON_PRESS -> MotionEvent.ACTION_BUTTON_PRESS
        StylusMotionAction.BUTTON_RELEASE -> MotionEvent.ACTION_BUTTON_RELEASE
    }

    companion object {
        /** InputManager's public mode value: enqueue without waiting for dispatch completion. */
        const val INJECT_ASYNC = 0
        private val sentCount = AtomicLong(0L)
    }
}

object ShizukuConnectionPolicy {
    fun canInject(status: ShizukuBackendStatus, serviceConnected: Boolean): Boolean =
        status == ShizukuBackendStatus.READY && serviceConnected
}
