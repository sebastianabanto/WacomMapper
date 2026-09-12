package com.example.wacommapper.output

/** Coordinates are display pixels; pressure is normalized to 0..1. Unknown flags remain null. */
data class MappedStylusEvent(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tip: Boolean?,
    val sideButton: Boolean?,
    val inRange: Boolean?,
    val eventType: com.example.wacommapper.mapping.StylusEventType? = null,
    val sideButton2: Boolean = false,
    val displayId: Int = 0,
    val inputElapsedNanos: Long = 0L,
)

enum class OutputBackendKind { NONE, ACCESSIBILITY, SHIZUKU, UINPUT }
enum class OutputSupport { SUPPORTED, PARTIAL, UNSUPPORTED }

data class OutputResult(
    val support: OutputSupport,
    val message: String,
    val attemptedEvents: Int = 0,
    val injectedEvents: Int = 0,
    val rejectedEvents: Int = 0,
    val lastMethod: String? = null,
    val lastResult: Boolean? = null,
    val lastException: String? = null,
)

data class LiveWacomMetrics(
    val hidReports: Long = 0,
    val tabletReports: Long = 0,
    val outOfRangeReports: Long = 0,
    val genericReports: Long = 0,
    val invalidReports: Long = 0,
    val parsedEvents: Long = 0,
    val mappedEvents: Long = 0,
    val eventsSubmitted: Long = 0,
    val injectedEvents: Long = 0,
    val rejectedEvents: Long = 0,
    val hoverEnterEvents: Long = 0,
    val hoverMoveEvents: Long = 0,
    val hoverExitEvents: Long = 0,
    val downEvents: Long = 0,
    val moveEvents: Long = 0,
    val upEvents: Long = 0,
    val inputReportsPerSecond: Float = 0f,
    val outputEventsPerSecond: Float = 0f,
    val averageLatencyMillis: Float = 0f,
    val maxLatencyMillis: Long = 0,
    val usbToMapperAverageMillis: Float = 0f,
    val usbToMapperP50Millis: Float = 0f,
    val usbToMapperP95Millis: Float = 0f,
    val usbToMapperMaxMillis: Float = 0f,
    val mapperToInjectAverageMillis: Float = 0f,
    val mapperToInjectP50Millis: Float = 0f,
    val mapperToInjectP95Millis: Float = 0f,
    val mapperToInjectMaxMillis: Float = 0f,
    val usbToInjectAverageMillis: Float = 0f,
    val usbToInjectP50Millis: Float = 0f,
    val usbToInjectP95Millis: Float = 0f,
    val usbToInjectMaxMillis: Float = 0f,
    val eventsReceived: Long = 0,
    val possibleDoubleInput: Boolean = false,
    val lastMethod: String? = null,
    val lastResult: Boolean? = null,
    val lastException: String? = null,
    val usbTransfersAttempted: Long = 0,
    val usbTransfersSuccessful: Long = 0,
    val usbBytesTransferred: Long = 0,
    val usbTimeouts: Long = 0,
    val usbErrors: Long = 0,
    val ignoredPackets: Long = 0,
    val tenByteReports: Long = 0,
    val parseErrors: Long = 0,
    val pipelineErrors: Long = 0,
    val lastRawReport: String? = null,
    val lastRawAgeMillis: Long? = null,
    val lastXRaw: Int? = null,
    val lastYRaw: Int? = null,
    val lastPressureRaw: Int? = null,
    val lastTip: Boolean? = null,
    val lastSideButton: Boolean? = null,
    val lastSideButton2: Boolean? = null,
    val lastProximityCandidate: Boolean? = null,
    val lastHoverCandidate: Boolean? = null,
    val lastFlagBytes: String? = null,
    val xValueChanges: Long = 0,
    val yValueChanges: Long = 0,
    val mappedX: Float? = null,
    val mappedY: Float? = null,
    val mappedPressure: Float? = null,
    val pressureMinRaw: Int? = null,
    val pressureMaxRaw: Int? = null,
    val usbDeviceFound: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val connectionOpened: Boolean = false,
    val requestedInterface: Int = 0,
    val claimedInterface: Int? = null,
    val requestedEndpoint: Int = 0x81,
    val activeEndpoint: Int? = null,
    val maxPacketSize: Int? = null,
    val liveState: LiveWacomState = LiveWacomState.IDLE,
    val liveError: String? = null,
    val hidOnly: Boolean = false,
    val requestedDurationMillis: Long = 10_000L,
    val unboundedSession: Boolean = false,
    val actualDurationMillis: Long = 0L,
    val startedAtMillis: Long? = null,
    val stoppedAtMillis: Long? = null,
    val stopReason: LiveStopReason? = null,
    val startTrace: List<String> = emptyList(),
    val lastReachedStage: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val exceptionCause: String? = null,
    val exceptionStackTrace: String? = null,
    val coroutineDiagnostics: String? = null,
)

data class LiveParsedTestSummary(
    val passed: Boolean,
    val tabletReports: Long,
    val xChanges: Long,
    val yChanges: Long,
    val usbErrors: Long,
) {
    companion object {
        fun from(metrics: LiveWacomMetrics) = LiveParsedTestSummary(
            passed = metrics.tabletReports > 0 && metrics.xValueChanges > 0 &&
                metrics.yValueChanges > 0 && metrics.usbErrors == 0L,
            tabletReports = metrics.tabletReports,
            xChanges = metrics.xValueChanges,
            yChanges = metrics.yValueChanges,
            usbErrors = metrics.usbErrors,
        )
    }
}

enum class LiveStopReason {
    START_EXCEPTION,
    OPEN_DEVICE_FAILED,
    CLAIM_INTERFACE_FAILED,
    ENDPOINT_NOT_FOUND,
    SESSION_JOB_CANCELLED,
    PARENT_SCOPE_CANCELLED,
    CLEANUP_CANCELLED_SESSION,
    LIFECYCLE_CANCELLED,
    TIME_LIMIT,
    USER_STOP,
    USB_DISCONNECTED,
    BACKEND_DISCONNECTED,
    USB_FATAL_ERROR,
    COROUTINE_CANCELLED,
}

data class UsbOpenOnlyDiagnostic(
    val deviceFound: Boolean = false,
    val permissionGranted: Boolean = false,
    val connectionOpened: Boolean = false,
    val actualDurationMillis: Long = 0L,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val exceptionStackTrace: String? = null,
    val running: Boolean = false,
)

data class LegacyHidDiagnostic(
    val running: Boolean = false,
    val deviceFound: Boolean = false,
    val permissionGranted: Boolean = false,
    val connectionOpened: Boolean = false,
    val durationMillis: Long = 0L,
    val reportsReceived: Long = 0L,
    val lastRaw: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val exceptionCause: String? = null,
    val exceptionStackTrace: String? = null,
)

enum class LiveWacomState {
    IDLE,
    OPENING_USB,
    CLAIMING_INTERFACE,
    WAITING_FOR_HID_REPORTS,
    HID_ACTIVE,
    PARSER_ACTIVE,
    MAPPING_ACTIVE,
    OUTPUT_ACTIVE,
    STOPPING,
    FINISHED,
    ERROR,
}

/** Output boundary intentionally contains no HID/parser dependency. */
interface StylusOutputBackend {
    fun send(event: MappedStylusEvent): OutputResult
    fun releaseAllInput(): OutputResult
}

/** Safe Phase 5 preview backend: records no system input and never injects events. */
object DiagnosticsOnlyBackend : StylusOutputBackend {
    override fun send(event: MappedStylusEvent) = OutputResult(
        support = OutputSupport.UNSUPPORTED,
        message = "Preview only; no event was sent to Android or another application.",
    )

    override fun releaseAllInput() = OutputResult(
        support = OutputSupport.SUPPORTED,
        message = "Internal canvas backend has no global input to release.",
    )
}

/** Local canvas sink. It deliberately never calls Android's global input APIs. */
object InternalCanvasBackend : StylusOutputBackend {
    @Volatile
    var lastEvent: MappedStylusEvent? = null
        private set

    override fun send(event: MappedStylusEvent): OutputResult {
        lastEvent = event
        return OutputResult(OutputSupport.SUPPORTED, "Event retained for the in-app canvas.", 1, 1)
    }

    override fun releaseAllInput(): OutputResult = OutputResult(
        OutputSupport.SUPPORTED,
        "Canvas backend reset; it owns no global pointer state.",
    ).also { lastEvent = null }
}
