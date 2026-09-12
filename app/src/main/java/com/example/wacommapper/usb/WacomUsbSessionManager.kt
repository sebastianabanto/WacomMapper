package com.example.wacommapper.usb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/** Owns the app's sole USB reader/connection and serializes clean session changes. */
class WacomUsbSessionManager(
    context: Context,
    onConnectionChanged: (HidConnectionState) -> Unit,
    onReport: (HidRawReport) -> Unit,
    onError: (String) -> Unit,
    onTransferCounters: (HidTransferCounters) -> Unit,
    onLiveTrace: (String) -> Unit = {},
    onFatal: (Throwable) -> Unit = {},
    onConsumerError: (String, Throwable) -> Unit = { _, throwable -> onFatal(throwable) },
) {
    @Volatile private var activeConnectionState = HidConnectionState()
    private val reader = HidRawReader(
        context = context.applicationContext,
        onConnectionChanged = { state ->
            activeConnectionState = state
            onConnectionChanged(state)
        },
        onReport = onReport,
        onError = onError,
        onFatal = onFatal,
        onConsumerError = onConsumerError,
        onTransferCounters = onTransferCounters,
        onLiveTrace = onLiveTrace,
    )
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queuedOperation: Job? = null

    /** For legacy diagnostic buttons; the same owned reader is reused and never duplicated. */
    @Synchronized
    fun start(deviceName: String, interfaceId: Int): Boolean {
        queuedOperation?.cancel()
        queuedOperation = scope.launch {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    reader.stopAndWait()
                    reader.start(deviceName, interfaceId)
                }
            }
        }
        return true
    }

    /** Stops and joins any previous transfer loop before opening a new exclusive session. */
    suspend fun startFresh(
        deviceName: String,
        interfaceId: Int,
        endpointAddress: Int,
    ): Boolean {
        cancelQueuedOperationUnlessCaller()
        return mutex.withLock {
        withContext(Dispatchers.IO) {
            reader.stopAndWait()
            reader.start(deviceName, interfaceId, endpointAddress, throwOnStartFailure = true)
        }
        }
    }

    fun startFreshAsync(deviceName: String, interfaceId: Int, endpointAddress: Int) {
        queuedOperation?.cancel()
        queuedOperation = scope.launch { startFresh(deviceName, interfaceId, endpointAddress) }
    }

    suspend fun stopAndWait() {
        cancelQueuedOperationUnlessCaller()
        mutex.withLock { withContext(Dispatchers.IO) { reader.stopAndWait() } }
    }

    fun stop() {
        queuedOperation?.cancel()
        queuedOperation = scope.launch {
            mutex.withLock { withContext(Dispatchers.IO) { reader.stopAndWait() } }
        }
    }

    fun counters(): HidTransferCounters = reader.counters()

    fun connectionState(): HidConnectionState = activeConnectionState

    fun coroutineDiagnostics(previousJob: Job?, parentJob: Job?): String {
        val scopeJob = scope.coroutineContext[Job]
        val activeReaderJob = reader.readJobDiagnostics()
        return "liveScope active=${scopeJob?.isActive} cancelled=${scopeJob?.isCancelled}; " +
            "parentJob active=${parentJob?.isActive} cancelled=${parentJob?.isCancelled}; " +
            "previousLiveJob active=${previousJob?.isActive} cancelled=${previousJob?.isCancelled}; " +
            "queuedJob active=${queuedOperation?.isActive} cancelled=${queuedOperation?.isCancelled}; " +
            "readLoop active=${activeReaderJob.first} cancelled=${activeReaderJob.second}"
    }

    private suspend fun cancelQueuedOperationUnlessCaller() {
        val caller = currentCoroutineContext()[Job]
        val queued = queuedOperation
        if (queued != null && queued !== caller) queued.cancel()
    }

    fun close() {
        queuedOperation?.cancel()
        scope.cancel()
        reader.close()
    }
}
