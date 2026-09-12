package com.example.wacommapper.usb

enum class UsbPermissionStatus {
    GRANTED,
    REQUIRED,
    DENIED,
}

data class UsbEndpointInfo(
    val address: Int,
    val endpointNumber: Int,
    val direction: Int,
    val directionName: String,
    val type: Int,
    val typeName: String,
    val maxPacketSize: Int,
    val interval: Int,
)

data class UsbInterfaceInfo(
    val id: Int,
    val usbClass: Int,
    val usbClassName: String,
    val subclass: Int,
    val protocol: Int,
    val alternateSetting: Int,
    val endpoints: List<UsbEndpointInfo>,
)

data class UsbDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val manufacturerName: String?,
    val productName: String?,
    val version: String?,
    val serialNumber: String?,
    val configurationCount: Int,
    val interfaces: List<UsbInterfaceInfo>,
    val permission: UsbPermissionStatus,
)
