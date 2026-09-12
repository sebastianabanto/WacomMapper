package com.example.wacommapper.output

import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicLong

/** Runs under Shizuku's shell identity; hidden-API calls stay out of the normal app process. */
@Keep
class ShizukuInjectionUserService : IShizukuInjectionService.Stub() {
    private data class InjectionExecution(val api: String, val returned: Boolean, val priorException: String? = null)
    @Volatile private var stylusContactActive = false
    @Volatile private var stylusHoverActive = false
    @Volatile private var stylusButtons = 0
    @Volatile private var stylusX = 0f
    @Volatile private var stylusY = 0f
    @Volatile private var stylusDisplayId = 0
    @Volatile private var stylusContactDownTime = 0L
    @Volatile private var stylusHoverDownTime = 0L

    override fun getRunningUid(): Int = Process.myUid()

    override fun injectMotion(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        source: Int,
        toolType: Int,
        buttonState: Int,
        actionButton: Int,
        downTimeMillis: Long,
        eventTimeMillis: Long,
        displayId: Int,
        injectionMode: Int,
    ): String {
        val event = buildEvent(
            action,
            x,
            y,
            pressure,
            source,
            toolType,
            buttonState,
            actionButton,
            downTimeMillis,
            eventTimeMillis,
            displayId,
        )
        try {
            Log.d(
                "GLOBAL_GEOMETRY_EVENT",
                "MotionEvent.getX=${event.getX(0)} getY=${event.getY(0)} displayId=$displayId " +
                    "action=${event.actionMasked} source=0x${event.source.toString(16)} toolType=${event.getToolType(0)} " +
                    "pressure=${event.getPressure(0)}",
            )
            val execution = injectThroughInputManager(event, injectionMode)
            if (execution.returned && source and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS) {
                rememberStylusState(action, x, y, buttonState, downTimeMillis, displayId)
            }
            val priorException = execution.priorException?.replace("\r", "")?.replace("\n", "\\n") ?: "none"
            val result = "api=${execution.api};returned=${execution.returned};priorException=$priorException;uid=${Process.myUid()};displayId=$displayId;x=$x;y=$y;downTime=$downTimeMillis;eventTime=$eventTimeMillis;source=0x${source.toString(16)};toolType=$toolType"
            val count = successfulInjectionCount.incrementAndGet()
            if (count <= 10L || count % 25L == 0L) {
                Log.i(TAG_INJECTION, "$result action=$action x=$x y=$y pressure=$pressure source=0x${source.toString(16)} toolType=$toolType buttons=0x${buttonState.toString(16)} actionButton=0x${actionButton.toString(16)}")
            }
            return result
        } catch (throwable: Throwable) {
            val cause = unwrap(throwable)
            Log.e(TAG_ERROR, "MotionEvent injection failed: ${cause.javaClass.name}: ${cause.message}", throwable)
            if (cause is SecurityException) throw cause
            throw RemoteException(Log.getStackTraceString(throwable))
        } finally {
            event.recycle()
        }
    }

    override fun emergencyStop(x: Float, y: Float, eventTimeMillis: Long): String {
        val snapshot = synchronized(this) {
            listOf(stylusContactActive, stylusHoverActive, stylusButtons != 0, stylusX, stylusY,
                stylusDisplayId, stylusContactDownTime, stylusHoverDownTime, stylusButtons)
        }
        val contact = snapshot[0] as Boolean
        val hover = snapshot[1] as Boolean
        val hasButtons = snapshot[2] as Boolean
        val lastX = snapshot[3] as Float
        val lastY = snapshot[4] as Float
        val targetDisplay = snapshot[5] as Int
        val contactDown = snapshot[6] as Long
        val hoverDown = snapshot[7] as Long
        var buttons = snapshot[8] as Int
        val failures = mutableListOf<String>()

        fun release(action: Int, actionButton: Int, nextButtons: Int, downTime: Long) {
            runCatching {
                val response = injectMotion(
                    action = action,
                    x = lastX,
                    y = lastY,
                    pressure = 0f,
                    source = InputDevice.SOURCE_STYLUS,
                    toolType = MotionEvent.TOOL_TYPE_STYLUS,
                    buttonState = nextButtons,
                    actionButton = actionButton,
                    downTimeMillis = downTime.takeIf { it > 0L } ?: eventTimeMillis,
                    eventTimeMillis = eventTimeMillis,
                    displayId = targetDisplay,
                    injectionMode = ShizukuInputManagerBackend.INJECT_ASYNC,
                )
                if ("returned=false" in response) failures += "InputManager returned false for action=$action"
            }.onFailure { failures += "${it.javaClass.simpleName}:${it.message}" }
        }

        if (hasButtons && buttons and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) {
            buttons = buttons and MotionEvent.BUTTON_STYLUS_PRIMARY.inv()
            release(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_STYLUS_PRIMARY, buttons, contactDown.takeIf { it > 0L } ?: hoverDown)
        }
        if (hasButtons && buttons and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) {
            buttons = buttons and MotionEvent.BUTTON_STYLUS_SECONDARY.inv()
            release(MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.BUTTON_STYLUS_SECONDARY, buttons, contactDown.takeIf { it > 0L } ?: hoverDown)
        }
        if (contact) release(MotionEvent.ACTION_UP, 0, 0, contactDown)
        if (hover) release(MotionEvent.ACTION_HOVER_EXIT, 0, 0, hoverDown)
        synchronized(this) {
            stylusContactActive = false
            stylusHoverActive = false
            stylusButtons = 0
        }
        return if (failures.isEmpty()) "Stylus release sent (contact=$contact hover=$hover buttons=${snapshot[8]}); uid=${Process.myUid()}"
        else "Stylus release failed: ${failures.joinToString()}; uid=${Process.myUid()}"
    }

    @Synchronized
    private fun rememberStylusState(action: Int, x: Float, y: Float, buttonState: Int, downTime: Long, displayId: Int) {
        stylusX = x
        stylusY = y
        stylusDisplayId = displayId
        stylusButtons = buttonState
        when (action) {
            MotionEvent.ACTION_DOWN -> { stylusContactActive = true; stylusContactDownTime = downTime }
            MotionEvent.ACTION_UP -> stylusContactActive = false
            MotionEvent.ACTION_HOVER_ENTER -> { stylusHoverActive = true; stylusHoverDownTime = downTime }
            MotionEvent.ACTION_HOVER_EXIT -> stylusHoverActive = false
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> Unit
            else -> if (action == MotionEvent.ACTION_MOVE) stylusContactActive = true
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DESTROY_TRANSACTION) {
            Log.i(TAG_STATUS, "Shizuku user service destroy requested")
            Thread { Process.killProcess(Process.myPid()) }.start()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun buildEvent(
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        source: Int,
        toolType: Int,
        buttonState: Int,
        actionButton: Int,
        downTimeMillis: Long,
        eventTimeMillis: Long,
        displayId: Int,
    ): MotionEvent {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite" }
        require(pressure.isFinite() && pressure in 0f..1f) { "Pressure must be in 0..1" }
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 0.1f
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                setAxisValue(MotionEvent.AXIS_DISTANCE, 1f)
            }
        }
        return MotionEvent.obtain(
            downTimeMillis,
            eventTimeMillis,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            displayId,
        ).also { event ->
            if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                event.javaClass.getDeclaredMethod("setActionButton", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
                    .invoke(event, actionButton)
            }
        }
    }

    private fun injectThroughInputManager(event: MotionEvent, injectionMode: Int): InjectionExecution {
        var inputManagerFailure: Throwable? = null
        try {
            val managerClass = Class.forName("android.hardware.input.InputManager")
            val instance = managerClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val method = managerClass.getDeclaredMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            return InjectionExecution(
                "InputManager.injectInputEvent -> InputManagerGlobal -> IInputManager (${modeName(injectionMode)})",
                method.invoke(instance, event, injectionMode) as Boolean,
            )
        } catch (throwable: Throwable) {
            inputManagerFailure = throwable
            Log.w(TAG_INJECTION, "InputManager path unavailable; trying InputManagerGlobal", throwable)
        }

        try {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
            val method = globalClass.getDeclaredMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            return InjectionExecution(
                "InputManagerGlobal.injectInputEvent -> IInputManager (${modeName(injectionMode)}, targetUid=-1)",
                method.invoke(global, event, injectionMode, -1) as Boolean,
                inputManagerFailure?.let(Log::getStackTraceString),
            )
        } catch (globalFailure: Throwable) {
            inputManagerFailure?.let(globalFailure::addSuppressed)
            throw globalFailure
        }
    }

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) throwable.targetException else throwable

    private fun modeName(mode: Int): String = when (mode) {
        0 -> "ASYNC"
        1 -> "WAIT_FOR_RESULT"
        2 -> "WAIT_FOR_FINISH"
        else -> "UNKNOWN($mode)"
    }

    companion object {
        private const val TAG_STATUS = "SHIZUKU_STATUS"
        private const val TAG_INJECTION = "SHIZUKU_INJECTION"
        private const val TAG_ERROR = "SHIZUKU_ERROR"
        private const val DESTROY_TRANSACTION = 16_777_115
        private val successfulInjectionCount = AtomicLong(0L)
    }
}
