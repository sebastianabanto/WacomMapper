package com.example.wacommapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wacommapper.usb.Ctl472RawReportParser
import com.example.wacommapper.usb.Ctl472ReportType
import com.example.wacommapper.usb.HidRawReport

@Composable
fun ParsedStylusDiagnosticsScreen(
    latestReport: HidRawReport?,
    selectedInterface: Int,
    connectionOpen: Boolean,
    reportsPerSecond: Int,
) {
    val parser = remember { Ctl472RawReportParser() }
    val parsed = latestReport?.let { parser.parse(it.bytes) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Parsed Stylus Diagnostics — CTL-472")
        Text("Selected interface: $selectedInterface; USB connection: ${if (connectionOpen) "OPEN" else "CLOSED"}")
        Text("Parser route: Interface 0 / EP 0x81 / 10-byte CTL-472 reports")
        Text("Reports/s: $reportsPerSecond")
        Text("RAW: ${parsed?.rawHex ?: "Waiting for a report…"}")
        Text("Report type: ${parsed?.type ?: "WAITING"}")
        Text("X: ${parsed?.x ?: "—"}")
        Text("Y: ${parsed?.y ?: "—"}")
        Text("Pressure: ${parsed?.pressure ?: "—"} (reference 0..2047; raw value is not masked)")
        Text("Side button 1: ${parsed?.sideButton1 ?: "—"}")
        Text("Side button 2: ${parsed?.sideButton2 ?: "—"}")
        Text("Eraser: ${parsed?.eraser ?: "—"}")
        Text("Near proximity (status bit 7): ${parsed?.nearProximity ?: "—"}")
        Text("Hover distance (byte 8, unsigned): ${parsed?.hoverDistance ?: "—"}")
        Text("Status byte: ${parsed?.statusByte?.let(::hexByte) ?: "—"} / ${parsed?.statusByte?.let(::binaryByte) ?: "--------"}")
        Text("Status bit 0 (raw only): ${parsed?.statusBit0 ?: "—"}")
        Text("Byte 9 (unknown/raw): ${parsed?.unknownByte9?.let(::hexByte) ?: "—"}")
        Text("Reference-range checks (diagnostic only): X=${parsed?.xInReferenceRange ?: "—"}, Y=${parsed?.yInReferenceRange ?: "—"}, pressure=${parsed?.pressureInReferenceRange ?: "—"}")

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CONTACT INTERPRETATION: UNKNOWN")
                Text("The physical HID parser does not label tip/contact. Status bit 0 is retained as raw only; pressure is not used to infer contact here.")
                if (parsed?.type == Ctl472ReportType.OUT_OF_RANGE) {
                    Text("OUT_OF_RANGE: no active position, pressure is treated as zero, and buttons are released by StylusStateMapper.")
                }
            }
        }
        if (latestReport != null && (latestReport.interfaceId != 0 || latestReport.endpointAddress != 0x81)) {
            Text("Latest raw packet came from Interface ${latestReport.interfaceId} / EP 0x${latestReport.endpointAddress.toString(16)}; it is displayed but is not the validated parser route.")
        }
    }
}

private fun hexByte(value: Int) = "0x%02X".format(value)
private fun binaryByte(value: Int) = value.toString(2).padStart(8, '0')
