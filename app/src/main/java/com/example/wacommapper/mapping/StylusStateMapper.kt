package com.example.wacommapper.mapping

import com.example.wacommapper.usb.Ctl472Report
import com.example.wacommapper.usb.Ctl472ReportType

enum class StylusPresenceState { OUT_OF_RANGE, HOVER, CONTACT }

enum class StylusEventType {
    HOVER_ENTER,
    HOVER_MOVE,
    HOVER_EXIT,
    DOWN,
    MOVE,
    UP,
    BUTTON_PRIMARY_DOWN,
    BUTTON_PRIMARY_UP,
    BUTTON_SECONDARY_DOWN,
    BUTTON_SECONDARY_UP,
    OUT_OF_RANGE,
}

data class StylusInternalEvent(
    val type: StylusEventType,
    val timestampMillis: Long,
    val fromState: StylusPresenceState,
    val toState: StylusPresenceState,
    val xRaw: Int?,
    val yRaw: Int?,
    val normalizedX: Float?,
    val normalizedY: Float?,
    val pressureRaw: Int,
    val pressureNormalized: Float,
    val sideButton1: Boolean,
    val sideButton2: Boolean,
)

data class StylusState(
    val state: StylusPresenceState,
    val present: Boolean,
    val xRaw: Int?,
    val yRaw: Int?,
    val pressureRaw: Int,
    val pressureNormalized: Float,
    val contact: Boolean,
    val hover: Boolean,
    val sideButton1: Boolean,
    val sideButton2: Boolean,
    val eraser: Boolean,
    val nearProximity: Boolean,
    val statusBit0: Boolean?,
    val normalizedX: Float?,
    val normalizedY: Float?,
    val mappedX: Float?,
    val mappedY: Float?,
    val reportType: Ctl472ReportType,
    val transition: String,
)

data class StylusStateUpdate(
    val state: StylusState,
    val events: List<StylusInternalEvent>,
)

data class StylusCanvasPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val strokeId: Long,
)

data class LiveStylusEventFrame(
    val rawReport: Ctl472Report? = null,
    val update: StylusStateUpdate? = null,
    val lastEvent: StylusInternalEvent? = null,
    val eventCount: Long = 0,
    val eventsPerSecond: Float = 0f,
    val canvasPoints: List<StylusCanvasPoint> = emptyList(),
    val tipDownThreshold: Int = StylusStateMapper.DEFAULT_TIP_DOWN_THRESHOLD,
    val tipUpThreshold: Int = StylusStateMapper.DEFAULT_TIP_UP_THRESHOLD,
)

/** HID fields are consumed as physical values; contact policy is pressure hysteresis only. */
class StylusStateMapper(
    private val coordinateConfig: TabletCoordinateConfig = TabletCoordinateConfig(),
    tipDownThreshold: Int = DEFAULT_TIP_DOWN_THRESHOLD,
    tipUpThreshold: Int = DEFAULT_TIP_UP_THRESHOLD,
) {
    var tipDownThreshold: Int = tipDownThreshold
        private set
    var tipUpThreshold: Int = tipUpThreshold
        private set

    var currentState: StylusPresenceState = StylusPresenceState.OUT_OF_RANGE
        private set
    private var button1 = false
    private var button2 = false
    private var lastX: Int? = null
    private var lastY: Int? = null

    init {
        require(tipDownThreshold >= 0 && tipUpThreshold >= 0 && tipUpThreshold < tipDownThreshold)
    }

    fun setThresholds(down: Int, up: Int) {
        require(down >= 0 && up >= 0 && up < down) { "Thresholds must satisfy 0 <= UP < DOWN" }
        tipDownThreshold = down
        tipUpThreshold = up
    }

    fun reset() {
        currentState = StylusPresenceState.OUT_OF_RANGE
        button1 = false
        button2 = false
        lastX = null
        lastY = null
    }

    fun process(report: Ctl472Report, timestampMillis: Long = System.currentTimeMillis()): StylusStateUpdate {
        val events = mutableListOf<StylusInternalEvent>()
        val oldState = currentState
        var reportX: Int? = null
        var reportY: Int? = null
        var pressure = 0
        var button1Next = false
        var button2Next = false

        if (report.type == Ctl472ReportType.TABLET_REPORT) {
            reportX = report.x
            reportY = report.y
            pressure = (report.pressure ?: 0).coerceAtLeast(0)
            button1Next = report.sideButton1
            button2Next = report.sideButton2
            if (reportX != null && reportY != null) {
                lastX = reportX
                lastY = reportY
            }

            if (currentState == StylusPresenceState.OUT_OF_RANGE) {
                currentState = StylusPresenceState.HOVER
                events += event(StylusEventType.HOVER_ENTER, report, oldState, currentState, pressure, timestampMillis)
            }

            when (currentState) {
                StylusPresenceState.HOVER -> if (pressure >= tipDownThreshold) {
                    val before = currentState
                    currentState = StylusPresenceState.CONTACT
                    events += event(StylusEventType.DOWN, report, before, currentState, pressure, timestampMillis)
                } else {
                    events += event(StylusEventType.HOVER_MOVE, report, currentState, currentState, pressure, timestampMillis)
                }
                StylusPresenceState.CONTACT -> if (pressure <= tipUpThreshold) {
                    val before = currentState
                    currentState = StylusPresenceState.HOVER
                    events += event(StylusEventType.UP, report, before, currentState, pressure, timestampMillis)
                } else {
                    events += event(StylusEventType.MOVE, report, currentState, currentState, pressure, timestampMillis)
                }
                StylusPresenceState.OUT_OF_RANGE -> Unit
            }
            appendButtonTransitions(report, events, pressure, button1Next, button2Next, timestampMillis)
        } else if (report.type == Ctl472ReportType.OUT_OF_RANGE) {
            val wasAlreadyOutOfRange = currentState == StylusPresenceState.OUT_OF_RANGE
            if (currentState == StylusPresenceState.CONTACT) {
                events += event(StylusEventType.UP, report, currentState, StylusPresenceState.OUT_OF_RANGE, 0, timestampMillis)
            } else if (currentState == StylusPresenceState.HOVER) {
                events += event(StylusEventType.HOVER_EXIT, report, currentState, StylusPresenceState.OUT_OF_RANGE, 0, timestampMillis)
            }
            if (button1) events += event(StylusEventType.BUTTON_PRIMARY_UP, report, currentState, currentState, 0, timestampMillis)
            if (button2) events += event(StylusEventType.BUTTON_SECONDARY_UP, report, currentState, currentState, 0, timestampMillis)
            button1 = false
            button2 = false
            currentState = StylusPresenceState.OUT_OF_RANGE
            if (!wasAlreadyOutOfRange) {
                events += event(StylusEventType.OUT_OF_RANGE, report, oldState, currentState, 0, timestampMillis)
            }
        }

        val inRange = report.type == Ctl472ReportType.TABLET_REPORT
        val normalizedX = if (inRange && reportX != null) {
            ((reportX - coordinateConfig.bounds.minX) / coordinateConfig.bounds.width).toFloat().coerceIn(0f, 1f)
        } else null
        val normalizedY = if (inRange && reportY != null) {
            ((reportY - coordinateConfig.bounds.minY) / coordinateConfig.bounds.height).toFloat().coerceIn(0f, 1f)
        } else null
        val pressureNormalized = (pressure / coordinateConfig.pressureBounds.max.toFloat()).coerceIn(0f, 1f)
        val transition = if (oldState == currentState) oldState.name else "${oldState.name} → ${currentState.name}"
        return StylusStateUpdate(
            state = StylusState(
                state = currentState,
                present = inRange,
                xRaw = reportX ?: if (inRange) lastX else null,
                yRaw = reportY ?: if (inRange) lastY else null,
                pressureRaw = pressure,
                pressureNormalized = pressureNormalized,
                contact = currentState == StylusPresenceState.CONTACT,
                hover = currentState == StylusPresenceState.HOVER,
                sideButton1 = button1,
                sideButton2 = button2,
                eraser = inRange && report.eraser,
                nearProximity = inRange && report.nearProximity,
                statusBit0 = if (inRange) report.statusBit0 else false,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                mappedX = null,
                mappedY = null,
                reportType = report.type,
                transition = transition,
            ),
            events = events,
        )
    }

    /** Compatibility snapshot used by the existing parser-only diagnostic; does not infer contact. */
    fun map(
        report: Ctl472Report,
        usableWidth: Int,
        usableHeight: Int,
        options: MappingOptions = MappingOptions(),
    ): StylusState {
        val update = process(report)
        if (report.type == Ctl472ReportType.TABLET_REPORT && report.x != null && report.y != null) {
            val mapped = CoordinateMapper.map(report.x.toDouble(), report.y.toDouble(), usableWidth, usableHeight, coordinateConfig, options)
            return update.state.copy(
                normalizedX = if (usableWidth > 0) (mapped.screenX / usableWidth).toFloat() else update.state.normalizedX,
                normalizedY = if (usableHeight > 0) (mapped.screenY / usableHeight).toFloat() else update.state.normalizedY,
                mappedX = mapped.screenX.toFloat(),
                mappedY = mapped.screenY.toFloat(),
            )
        }
        return update.state
    }

    private fun appendButtonTransitions(
        report: Ctl472Report,
        events: MutableList<StylusInternalEvent>,
        pressure: Int,
        next1: Boolean,
        next2: Boolean,
        timestampMillis: Long,
    ) {
        if (next1 != button1) {
            events += event(if (next1) StylusEventType.BUTTON_PRIMARY_DOWN else StylusEventType.BUTTON_PRIMARY_UP, report, currentState, currentState, pressure, timestampMillis)
        }
        if (next2 != button2) {
            events += event(if (next2) StylusEventType.BUTTON_SECONDARY_DOWN else StylusEventType.BUTTON_SECONDARY_UP, report, currentState, currentState, pressure, timestampMillis)
        }
        button1 = next1
        button2 = next2
    }

    private fun event(
        type: StylusEventType,
        report: Ctl472Report,
        from: StylusPresenceState,
        to: StylusPresenceState,
        pressure: Int,
        timestampMillis: Long,
    ) = StylusInternalEvent(
        type = type,
        timestampMillis = timestampMillis,
        fromState = from,
        toState = to,
        xRaw = report.x,
        yRaw = report.y,
        normalizedX = report.x?.let { ((it - coordinateConfig.bounds.minX) / coordinateConfig.bounds.width).toFloat().coerceIn(0f, 1f) },
        normalizedY = report.y?.let { ((it - coordinateConfig.bounds.minY) / coordinateConfig.bounds.height).toFloat().coerceIn(0f, 1f) },
        pressureRaw = pressure,
        pressureNormalized = (pressure / coordinateConfig.pressureBounds.max.toFloat()).coerceIn(0f, 1f),
        sideButton1 = report.sideButton1,
        sideButton2 = report.sideButton2,
    )

    companion object {
        const val DEFAULT_TIP_DOWN_THRESHOLD = 5
        const val DEFAULT_TIP_UP_THRESHOLD = 2
    }
}
