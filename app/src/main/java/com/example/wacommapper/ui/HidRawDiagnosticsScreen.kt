package com.example.wacommapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wacommapper.usb.HidRawReport
import com.example.wacommapper.usb.UsbDeviceInfo

@Composable
fun HidRawDiagnosticsScreen(
    device: UsbDeviceInfo?,
    selectedInterface: Int,
    isOpen: Boolean,
    interfaceClaimed: Boolean,
    totalReports: Long,
    reportsPerSecond: Int,
    lastPacketSize: Int,
    lastReport: HidRawReport?,
    recentReports: List<HidRawReport>,
    errorMessage: String?,
    onSelectInterface: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("FASE 2 — CAPTURA HID RAW")
            Text("Device: ${device?.productName ?: device?.deviceName ?: "No USB device with permission"}")
            Text("Connection: ${if (isOpen) "OPEN" else "CLOSED"}")
            Text("Interface claimed: ${if (interfaceClaimed) "YES" else "NO"}")
            Text("Select interface:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InterfaceButton(0, "Interface 0 — EP 0x81", selectedInterface, onSelectInterface)
                InterfaceButton(1, "Interface 1 — EP 0x82", selectedInterface, onSelectInterface)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = device != null && !isOpen) { Text("Start capture") }
                Button(onClick = onStop, enabled = isOpen) { Text("Stop capture") }
                Button(onClick = onClear) { Text("Clear") }
            }
            Button(onClick = onExport, enabled = recentReports.isNotEmpty()) { Text("Export capture") }
            Text("Reports received: $totalReports")
            Text("Reports/sec: $reportsPerSecond")
            Text("Last packet size: $lastPacketSize")
            errorMessage?.let { Text("Status: $it") }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("LAST RAW REPORT:")
            Text(lastReport?.hex ?: "—")
            Text("Recent reports (${recentReports.size}/50)")
            recentReports.asReversed().forEach { report ->
                Text("${report.timestamp} | interface ${report.interfaceId} | endpoint 0x${hex(report.endpointAddress)} | ${report.bytes.size} bytes")
                Text(report.hex)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun InterfaceButton(
    interfaceId: Int,
    label: String,
    selectedInterface: Int,
    onSelectInterface: (Int) -> Unit,
) {
    Button(onClick = { onSelectInterface(interfaceId) }, enabled = selectedInterface != interfaceId) {
        Text(label)
    }
}

private fun hex(value: Int): String = value.toString(16).padStart(2, '0').uppercase()
