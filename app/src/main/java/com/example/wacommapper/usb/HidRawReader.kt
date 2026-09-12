package com.example.wacommapper.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HidRawReport(
    val timestamp: Long,
    val interfaceId: Int,
    val endpointAddress: Int,
    val bytes: ByteArray,
    val vendorId: Int? = null,
    val productId: Int? = null,
) {
    val hex: String = bytes.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}

data class HidConnectionState(
    val isOpen: Boolean = false,
    val claimedInterfaceId: Int? = null,
    val activeEndpointAddress: Int? = null,
    val packetSize: Int? = null,
)

data class HidTransferCounters(
    val attempted: Long = 0,
    val successful: Long = 0,
    val bytesTransferred: Long = 0,
    val timeouts: Long = 0,
    val usbErrors: Long = 0,
)

class HidReaderStartException(
    val failureCode: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HidRawReader(
    context: Context,
    private val onConnectionChanged: (HidConnectionState) -> Unit,
    private val onReport: (HidRawReport) -> Unit,
    private val onError: (String) -> Unit,
    private val onFatal: (Throwable) -> Unit = {},
    private val onConsumerError: (String, Throwable) -> Unit = { stage, throwable ->
        Log.e("LIVE_PIPELINE", "Consumer callback failed at $stage", throwable)
    },
    private val onTransferCounters: (HidTransferCounters) -> Unit = {},
    private val onLiveTrace: (String) -> Unit = {},
) {
    companion object {
        private const val TAG_CONNECTION = "HID_CONNECTION"
        private const val TAG_INTERFACE = "HID_INTERFACE"
        private const val TAG_TRANSFER = "HID_TRANSFER"
        private const val TAG_RAW = "HID_RAW"
        private const val TRANSFER_TIMEOUT_MS = 75
        private const val CTL472_VENDOR_ID = 0x056A
        private const val CTL472_PRODUCT_ID = 0x037A
        private const val CTL472_FEATURE_REPORT_ID = 0x02
        private const val CTL472_FEATURE_INIT_TIMEOUT_MS = 1000
        private const val LOG_EVERY_REPORTS = 50L
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var reportCount = 0L
    @Volatile private var transferCounters = HidTransferCounters()

    @Synchronized
    fun start(
        deviceName: String,
        interfaceId: Int,
        requiredEndpointAddress: Int? = null,
        throwOnStartFailure: Boolean = false,
    ): Boolean {

        val device = usbManager.deviceList[deviceName]
        if (device == null) {
            fail("USB device is no longer connected")
            onLiveTrace("USB device lookup failed: disconnected")
            if (throwOnStartFailure) throw HidReaderStartException("USB_DISCONNECTED", "USB device is no longer connected")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            fail("USB permission is not granted")
            onLiveTrace("USB permission check failed")
            if (throwOnStartFailure) throw HidReaderStartException("START_EXCEPTION", "USB permission is not granted")
            return false
        }

        onLiveTrace("openDevice called")
        val openedConnection = try {
            usbManager.openDevice(device)
        } catch (throwable: Throwable) {
            onLiveTrace("openDevice threw ${throwable::class.java.name}: ${throwable.message}")
            if (throwOnStartFailure) throw HidReaderStartException("START_EXCEPTION", "UsbManager.openDevice threw", throwable)
            throw throwable
        }
        onLiveTrace("openDevice returned; null=${openedConnection == null}")
        if (openedConnection == null) {
            fail("openDevice returned null")
            if (throwOnStartFailure) throw HidReaderStartException("OPEN_DEVICE_FAILED", "UsbManager.openDevice returned null")
            return false
        }

        onLiveTrace("interface lookup: $interfaceId")
        val usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceId }
        if (usbInterface == null) {
            closeAfterStartFailure(openedConnection)
            fail("Interface $interfaceId was not found")
            if (throwOnStartFailure) throw HidReaderStartException("START_EXCEPTION", "Interface $interfaceId was not found")
            return false
        }
        onLiveTrace("claimInterface called: $interfaceId")
        val claimed = try {
            openedConnection.claimInterface(usbInterface, true)
        } catch (throwable: Throwable) {
            onLiveTrace("claimInterface threw ${throwable::class.java.name}: ${throwable.message}")
            closeAfterStartFailure(openedConnection)
            if (throwOnStartFailure) throw HidReaderStartException("CLAIM_INTERFACE_FAILED", "claimInterface($interfaceId) threw", throwable)
            throw throwable
        }
        if (!claimed) {
            closeAfterStartFailure(openedConnection)
            onLiveTrace("claimInterface result=false")
            fail("Could not claim interface $interfaceId")
            if (throwOnStartFailure) throw HidReaderStartException("CLAIM_INTERFACE_FAILED", "claimInterface($interfaceId) returned false")
            return false
        }
        onLiveTrace("claimInterface result=true")

        onLiveTrace("endpoint lookup on interface $interfaceId")
        val endpoint = try {
            (0 until usbInterface.endpointCount)
                .map { usbInterface.getEndpoint(it) }
                .firstOrNull {
                    it.direction == UsbConstants.USB_DIR_IN &&
                        it.type == UsbConstants.USB_ENDPOINT_XFER_INT
                }
                ?.takeIf { requiredEndpointAddress == null || it.address == requiredEndpointAddress }
        } catch (throwable: Throwable) {
            onLiveTrace("endpoint lookup threw ${throwable::class.java.name}: ${throwable.message}")
            releaseAndCloseAfterStartFailure(openedConnection, usbInterface)
            throw throwable
        }
        if (endpoint == null) {
            releaseAndCloseAfterStartFailure(openedConnection, usbInterface)
            fail("Interface $interfaceId has no matching INTERRUPT IN endpoint${requiredEndpointAddress?.let { " 0x${it.toString(16)}" } ?: ""}")
            if (throwOnStartFailure) throw HidReaderStartException("ENDPOINT_NOT_FOUND", "No matching INTERRUPT IN endpoint on interface $interfaceId")
            return false
        }

        if (
            device.vendorId == CTL472_VENDOR_ID &&
            device.productId == CTL472_PRODUCT_ID &&
            interfaceId == 0 &&
            endpoint.address == 0x81
        ) {
            sendCtl472FeatureInitialization(openedConnection, interfaceId)
        }

        connection = openedConnection
        claimedInterface = usbInterface
        reportCount = 0L
        transferCounters = HidTransferCounters()
        emitTransferCounters()
        Log.i(TAG_CONNECTION, "Connection opened")
        Log.i(TAG_INTERFACE, "Claiming interface ${usbInterface.id}, endpoint 0x${endpoint.address.toString(16).uppercase()}")
        emitConnectionChanged(
            HidConnectionState(
                isOpen = true,
                claimedInterfaceId = usbInterface.id,
                activeEndpointAddress = endpoint.address,
                packetSize = endpoint.maxPacketSize,
            ),
        )

        readJob = scope.launch {
            onLiveTrace("reader job started; active=${currentCoroutineContext().isActive}")
            readLoop(deviceName, usbInterface, endpoint, openedConnection)
        }
        return true
    }

    /** Sends the CTL-472 FeatureInitReport (02 02) after claiming its tablet interface. */
    private fun sendCtl472FeatureInitialization(
        openedConnection: UsbDeviceConnection,
        interfaceId: Int,
    ) {
        val report = byteArrayOf(0x02, 0x02)
        val requestType = UsbConstants.USB_DIR_OUT or
            UsbConstants.USB_TYPE_CLASS or
            0x01 // USB recipient: interface
        val request = 0x09 // HID SET_REPORT
        val value = (0x03 shl 8) or CTL472_FEATURE_REPORT_ID // Feature report, ID 0x02

        onLiveTrace("sending CTL-472 FeatureInitReport 02 02: SET_REPORT FEATURE, interface=$interfaceId")
        Log.i("LIVE_USB", "Sending CTL-472 FeatureInitReport 02 02 on interface $interfaceId")
        try {
            val transferred = openedConnection.controlTransfer(
                requestType,
                request,
                value,
                interfaceId,
                report,
                report.size,
                CTL472_FEATURE_INIT_TIMEOUT_MS,
            )
            val outcome = when (transferred) {
                report.size -> "accepted $transferred/${report.size} bytes"
                0 -> "returned 0 (device/API reports no transferred bytes)"
                else -> "returned $transferred/${report.size} bytes"
            }
            onLiveTrace("FeatureInitReport 02 02 result=$transferred ($outcome)")
            Log.i("LIVE_USB", "FeatureInitReport 02 02 result=$transferred ($outcome)")
        } catch (throwable: Throwable) {
            onLiveTrace("FeatureInitReport 02 02 exception=${throwable::class.java.name}: ${throwable.message}")
            Log.e("LIVE_USB", "FeatureInitReport 02 02 failed; continuing with interrupt reads", throwable)
        }
    }

    @Synchronized
    fun stop() {
        readJob?.cancel()
        readJob = null
        connection?.close()
        connection = null
        claimedInterface = null
        emitConnectionChanged(HidConnectionState())
    }

    suspend fun stopAndWait() {
        val activeJob = synchronized(this) { readJob }
        activeJob?.cancelAndJoin()
        synchronized(this) {
            if (readJob == null) {
                connection = null
                claimedInterface = null
            }
        }
        emitConnectionChanged(HidConnectionState())
    }

    fun counters(): HidTransferCounters = transferCounters

    fun readJobDiagnostics(): Pair<Boolean?, Boolean?> = readJob?.let { it.isActive to it.isCancelled } ?: (null to null)

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun readLoop(
        deviceName: String,
        usbInterface: UsbInterface,
        endpoint: UsbEndpoint,
        openedConnection: UsbDeviceConnection,
    ) {
        try {
            while (currentCoroutineContext().isActive) {
                val connectedDevice = usbManager.deviceList[deviceName]
                if (connectedDevice == null) {
                    transferCounters = transferCounters.copy(usbErrors = transferCounters.usbErrors + 1)
                    emitTransferCounters()
                    Log.i(TAG_TRANSFER, "USB device disconnected")
                    onError("USB device disconnected")
                    break
                }

                val buffer = ByteArray(endpoint.maxPacketSize)
                val before = transferCounters
                val firstAttempt = before.attempted == 0L
                transferCounters = before.copy(attempted = before.attempted + 1)
                emitTransferCounters()
                if (firstAttempt) {
                    onLiveTrace("first read attempt")
                    val endpointType = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                        else -> "${endpoint.type}"
                    }
                    Log.i(
                        "LIVE_HID",
                        "first transfer context: connectionMatchesActive=${connection === openedConnection} fd=${openedConnection.fileDescriptor}; " +
                            "interface=${usbInterface.id} claimed=${claimedInterface === usbInterface}; " +
                            "endpoint=0x${endpoint.address.toString(16)} direction=${endpoint.direction} type=$endpointType " +
                            "maxPacket=${endpoint.maxPacketSize} bufferSize=${buffer.size} timeoutMs=$TRANSFER_TIMEOUT_MS",
                    )
                    onLiveTrace("first transfer context: fd=${openedConnection.fileDescriptor}, interface=${usbInterface.id}, claimed=${claimedInterface === usbInterface}, endpoint=0x${endpoint.address.toString(16)}, direction=${endpoint.direction}, type=$endpointType, maxPacket=${endpoint.maxPacketSize}, buffer=${buffer.size}, timeout=$TRANSFER_TIMEOUT_MS")
                    Log.i("LIVE_HID", "calling bulkTransfer...")
                }
                val length = try {
                    openedConnection.bulkTransfer(endpoint, buffer, buffer.size, TRANSFER_TIMEOUT_MS)
                } catch (throwable: Throwable) {
                    if (firstAttempt) Log.e("LIVE_FATAL", "first bulkTransfer threw", throwable)
                    throw throwable
                }
                if (firstAttempt) {
                    Log.i("LIVE_HID", "bulkTransfer returned = $length")
                    onLiveTrace("first bulkTransfer returned=$length")
                }
                when {
                    length > 0 -> {
                        transferCounters = transferCounters.copy(
                            successful = transferCounters.successful + 1,
                            bytesTransferred = transferCounters.bytesTransferred + length,
                        )
                        emitTransferCounters()
                        val report = HidRawReport(
                            timestamp = System.currentTimeMillis(),
                            interfaceId = usbInterface.id,
                            endpointAddress = endpoint.address,
                            bytes = buffer.copyOf(length),
                            vendorId = connectedDevice.vendorId,
                            productId = connectedDevice.productId,
                        )
                        reportCount++
                        if (reportCount == 1L) onLiveTrace("first report received (${report.bytes.size} bytes)")
                        if (reportCount <= 5 || reportCount % LOG_EVERY_REPORTS == 0L) {
                            Log.i(TAG_RAW, "len=$length raw=${report.hex}")
                        }
                        emitReport(report)
                    }
                    length < 0 && !usbManager.deviceList.containsKey(deviceName) -> {
                        transferCounters = transferCounters.copy(usbErrors = transferCounters.usbErrors + 1)
                        emitTransferCounters()
                        Log.i(TAG_TRANSFER, "Transfer failed: device disconnected")
                        onError("USB device disconnected")
                        break
                    }
                    length <= 0 -> {
                        transferCounters = transferCounters.copy(timeouts = transferCounters.timeouts + 1)
                        emitTransferCounters()
                        Log.d(TAG_TRANSFER, "Interrupt read timeout/no packet; continuing")
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (scope.isActive && currentCoroutineContext().isActive) {
                transferCounters = transferCounters.copy(usbErrors = transferCounters.usbErrors + 1)
                emitTransferCounters()
                Log.e("LIVE_FATAL", "Fatal USB read-loop failure", throwable)
                onLiveTrace("fatal USB reader exception: ${throwable::class.java.name}: ${throwable.message}")
                onFatal(throwable)
                onError("USB transfer error: ${throwable.message ?: "unknown error"}")
            }
        } finally {
            try {
                openedConnection.releaseInterface(usbInterface)
            } catch (throwable: Throwable) {
                Log.e("LIVE_FATAL", "releaseInterface failed", throwable)
                onFatal(throwable)
            } finally {
                try {
                    openedConnection.close()
                } catch (throwable: Throwable) {
                    Log.e("LIVE_FATAL", "UsbDeviceConnection.close failed", throwable)
                    onFatal(throwable)
                }
            }
            val shouldNotifyClosed = synchronized(this) {
                if (connection === openedConnection) {
                    connection = null
                    claimedInterface = null
                    readJob = null
                    true
                } else {
                    false
                }
            }
            if (shouldNotifyClosed) {
                emitConnectionChanged(HidConnectionState())
                Log.i(TAG_CONNECTION, "Connection closed")
            }
        }
    }

    private fun fail(message: String) {
        Log.e(TAG_CONNECTION, message)
        onError(message)
        emitConnectionChanged(HidConnectionState())
    }

    private fun emitTransferCounters() {
        try {
            onTransferCounters(transferCounters)
        } catch (throwable: Throwable) {
            reportConsumerFailure("transfer counters", throwable)
        }
    }

    private fun emitConnectionChanged(state: HidConnectionState) {
        try {
            onConnectionChanged(state)
        } catch (throwable: Throwable) {
            reportConsumerFailure("connection state", throwable)
        }
    }

    private fun emitReport(report: HidRawReport) {
        try {
            onReport(report)
        } catch (throwable: Throwable) {
            reportConsumerFailure("raw report delivery", throwable)
        }
    }

    private fun reportConsumerFailure(stage: String, throwable: Throwable) {
        try {
            onConsumerError(stage, throwable)
        } catch (reportingFailure: Throwable) {
            Log.e("LIVE_PIPELINE", "Could not report consumer failure at $stage", reportingFailure)
        }
    }

    private fun closeAfterStartFailure(connection: UsbDeviceConnection) {
        try {
            connection.close()
        } catch (throwable: Throwable) {
            Log.e("LIVE_FATAL", "Connection close after startup failure failed", throwable)
            onLiveTrace("startup cleanup exception: ${throwable::class.java.name}: ${throwable.message}")
        }
    }

    private fun releaseAndCloseAfterStartFailure(connection: UsbDeviceConnection, usbInterface: UsbInterface) {
        try {
            connection.releaseInterface(usbInterface)
        } catch (throwable: Throwable) {
            Log.e("LIVE_FATAL", "Interface release after startup failure failed", throwable)
            onLiveTrace("startup release exception: ${throwable::class.java.name}: ${throwable.message}")
        } finally {
            closeAfterStartFailure(connection)
        }
    }
}
