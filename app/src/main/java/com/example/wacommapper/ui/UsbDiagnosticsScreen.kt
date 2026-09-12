package com.example.wacommapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wacommapper.usb.UsbDeviceInfo
import com.example.wacommapper.usb.UsbInterfaceInfo
import com.example.wacommapper.usb.UsbPermissionStatus

@Composable
fun UsbDiagnosticsScreen(
    modifier: Modifier = Modifier,
    devices: List<UsbDeviceInfo>,
    onScan: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onUseForHid: (String) -> Unit,
    hidContent: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("WacomMapper")
        Text("USB status: ${if (devices.isEmpty()) "No scan results" else "Scan complete"}")
        Spacer(Modifier.height(12.dp))
        Button(onClick = onScan) { Text("Scan USB") }
        Spacer(Modifier.height(12.dp))
        Text("Devices detected: ${devices.size}")
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.deviceName }) { device ->
                DeviceCard(device, onRequestPermission, onUseForHid)
            }
            item { hidContent() }
        }
    }
}

@Composable
private fun DeviceCard(
    device: UsbDeviceInfo,
    onRequestPermission: (String) -> Unit,
    onUseForHid: (String) -> Unit,
) {
    var expanded by remember(device.deviceName) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Button(onClick = { expanded = !expanded }) { Text(if (expanded) "USB DEVICE ▲" else "USB DEVICE ▼") }
            Text("Product: ${device.productName ?: "Unknown"}")
            Text("Manufacturer: ${device.manufacturerName ?: "Unknown"}")
            Text("Device: ${device.deviceName} (ID ${device.deviceId})")
            Text("VID: ${device.vendorId} / 0x${hex(device.vendorId)}")
            Text("PID: ${device.productId} / 0x${hex(device.productId)}")
            Text("Class: ${device.deviceClass}, Subclass: ${device.deviceSubclass}, Protocol: ${device.deviceProtocol}")
            Text("Version: ${device.version ?: "Unknown"}")
            device.serialNumber?.let { Text("Serial: $it") }
            Text("Configurations: ${device.configurationCount} | Interfaces: ${device.interfaces.size}")
            Text("Permission: ${device.permission}")
            if (device.permission != UsbPermissionStatus.GRANTED) {
                Button(onClick = { onRequestPermission(device.deviceName) }) { Text("Request permission") }
            } else {
                Button(onClick = { onUseForHid(device.deviceName) }) { Text("Use for HID capture") }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                device.interfaces.forEach { InterfaceDetails(it) }
            }
        }
    }
}

@Composable
private fun InterfaceDetails(info: UsbInterfaceInfo) {
    Text("Interface ${info.id}")
    Text("Class: ${info.usbClassName} (${info.usbClass})")
    Text("Subclass: ${info.subclass} | Protocol: ${info.protocol} | Alternate: ${info.alternateSetting}")
    Text("Endpoints: ${info.endpoints.size}")
    info.endpoints.forEachIndexed { index, endpoint ->
        Text("Endpoint $index: address ${endpoint.address}, number ${endpoint.endpointNumber}")
        Text("Direction: ${endpoint.directionName} (${endpoint.direction}) | Type: ${endpoint.typeName} (${endpoint.type})")
        Text("Max packet: ${endpoint.maxPacketSize} | Interval: ${endpoint.interval}")
    }
    Spacer(Modifier.height(8.dp))
}

private fun hex(value: Int): String = value.toString(16).padStart(4, '0').uppercase()
