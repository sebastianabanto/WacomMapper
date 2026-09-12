package com.example.wacommapper.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Collections

class UsbDeviceDetector(
    context: Context,
    private val onPermissionChanged: () -> Unit,
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.wacommapper.USB_PERMISSION"
        private const val TAG_DEVICE = "USB_DEVICE"
        private const val TAG_PERMISSION = "USB_PERMISSION"
        private const val TAG_INTERFACE = "USB_INTERFACE"
        private const val TAG_ENDPOINT = "USB_ENDPOINT"
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val deniedDeviceNames = Collections.synchronizedSet(mutableSetOf<String>())
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            device?.deviceName?.let { deviceName ->
                if (granted) deniedDeviceNames.remove(deviceName) else deniedDeviceNames.add(deviceName)
            }
            Log.i(TAG_PERMISSION, "${device?.deviceName ?: "Unknown device"}: ${if (granted) "Permission granted" else "Permission denied"}")
            onPermissionChanged()
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun scan(): List<UsbDeviceInfo> {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        Log.i(TAG_DEVICE, "Scan found ${devices.size} USB device(s)")
        return devices.map { device ->
            Log.i(TAG_DEVICE, "Device detected: ${device.deviceName}")
            Log.i(TAG_DEVICE, "VID = ${device.vendorId} (0x${device.vendorId.toString(16).padStart(4, '0').uppercase()})")
            Log.i(TAG_DEVICE, "PID = ${device.productId} (0x${device.productId.toString(16).padStart(4, '0').uppercase()})")
            toInfo(device)
        }
    }

    fun requestPermission(deviceName: String) {
        val device = usbManager.deviceList[deviceName] ?: return
        if (usbManager.hasPermission(device)) return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        Log.i(TAG_PERMISSION, "Permission required: ${device.deviceName}")
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags),
        )
    }

    fun close() {
        appContext.unregisterReceiver(permissionReceiver)
    }

    private fun toInfo(device: UsbDevice): UsbDeviceInfo {
        return UsbDeviceInfo(
            deviceName = device.deviceName,
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            version = device.version,
            serialNumber = runCatching { device.serialNumber }.getOrNull(),
            configurationCount = device.configurationCount,
            interfaces = (0 until device.interfaceCount).map { index -> toInfo(device.getInterface(index)) },
            permission = when {
                usbManager.hasPermission(device) -> UsbPermissionStatus.GRANTED
                deniedDeviceNames.contains(device.deviceName) -> UsbPermissionStatus.DENIED
                else -> UsbPermissionStatus.REQUIRED
            },
        )
    }

    private fun toInfo(usbInterface: UsbInterface): UsbInterfaceInfo {
        Log.i(TAG_INTERFACE, "Interface ${usbInterface.id}: Class = ${usbInterface.interfaceClass}")
        return UsbInterfaceInfo(
            id = usbInterface.id,
            usbClass = usbInterface.interfaceClass,
            usbClassName = className(usbInterface.interfaceClass),
            subclass = usbInterface.interfaceSubclass,
            protocol = usbInterface.interfaceProtocol,
            alternateSetting = usbInterface.alternateSetting,
            endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it).toInfo() },
        )
    }

    private fun UsbEndpoint.toInfo(): UsbEndpointInfo {
        val directionName = if (direction == UsbConstants.USB_DIR_IN) "IN" else if (direction == UsbConstants.USB_DIR_OUT) "OUT" else "UNKNOWN"
        val typeName = when (type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            else -> "UNKNOWN"
        }
        Log.i(TAG_ENDPOINT, "Endpoint $endpointNumber: Direction = $directionName, Type = $typeName")
        return UsbEndpointInfo(address, endpointNumber, direction, directionName, type, typeName, maxPacketSize, interval)
    }

    private fun className(value: Int): String = when (value) {
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
        UsbConstants.USB_CLASS_COMM -> "COMMUNICATIONS"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS STORAGE"
        UsbConstants.USB_CLASS_HUB -> "HUB"
        else -> "UNKNOWN"
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
