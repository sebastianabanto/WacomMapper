package com.example.wacommapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wacommapper.mapping.TabletRotation
import com.example.wacommapper.output.HoverCursorSize
import com.example.wacommapper.output.PressureSensitivity
import com.example.wacommapper.output.ProductSessionState
import com.example.wacommapper.output.ProductStylusStatus
import com.example.wacommapper.output.ProductTabletStatus
import com.example.wacommapper.output.ShizukuBackendStatus
import com.example.wacommapper.output.RgbColorWheel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProductHomeScreen(
    state: ProductSessionState,
    usbPermissionGranted: Boolean,
    onGrantUsb: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WacomMapper", style = MaterialTheme.typography.headlineMedium)
        StatusCard("Wacom CTL-472", when {
            state.tablet == ProductTabletStatus.PERMISSION_REQUIRED -> "Sin permiso USB"
            state.tablet == ProductTabletStatus.DISCONNECTED -> "Desconectada"
            state.tablet == ProductTabletStatus.ERROR -> "Error"
            else -> "Conectada"
        }, state.tablet == ProductTabletStatus.CONNECTED)
        if (state.tablet == ProductTabletStatus.PERMISSION_REQUIRED || (state.tablet == ProductTabletStatus.CONNECTED && !usbPermissionGranted)) {
            OutlinedButton(onClick = onGrantUsb, modifier = Modifier.fillMaxWidth()) { Text("Conceder permiso USB") }
        }
        StatusCard("Shizuku", when (state.shizuku) {
            ShizukuBackendStatus.NOT_INSTALLED -> "No instalado"
            ShizukuBackendStatus.NOT_RUNNING -> "No iniciado"
            ShizukuBackendStatus.PERMISSION_REQUIRED -> "Permiso requerido"
            ShizukuBackendStatus.READY -> "Listo"
            ShizukuBackendStatus.FAILED -> "Error"
        }, state.shizuku == ShizukuBackendStatus.READY)
        StatusCard("Inyección global", when (state.stylus) {
            ProductStylusStatus.INACTIVE -> "Inactiva"
            ProductStylusStatus.STARTING -> "Iniciando"
            ProductStylusStatus.ACTIVE -> "Activa"
            ProductStylusStatus.ERROR -> "Error"
        }, state.stylus == ProductStylusStatus.ACTIVE)
        if (state.message != null) Text(state.message, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = if (state.stylus == ProductStylusStatus.ACTIVE || state.stylus == ProductStylusStatus.STARTING) onStop else onStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.stylus == ProductStylusStatus.ACTIVE || state.stylus == ProductStylusStatus.STARTING) "DETENER STYLUS" else "INICIAR STYLUS")
        }
        if (state.stylus == ProductStylusStatus.ACTIVE) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stylus activo", style = MaterialTheme.typography.titleMedium)
                    Text("Hover: ${if (state.hover) "Sí" else "No"}   ·   Contacto: ${if (state.contact) "Sí" else "No"}")
                    LinearProgressIndicator(progress = { state.pressure.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("Presión ${(state.pressure * 100).toInt()}%")
                }
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Ajustes") }
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Herramientas avanzadas") }
        Text("Optimizado actualmente para uso horizontal.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusCard(title: String, value: String, ready: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ready) "●" else "○", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ProductSettingsScreen(
    showCursor: Boolean,
    cursorSize: HoverCursorSize,
    cursorColor: Int,
    savedCursorColors: List<Int?>,
    sensitivity: PressureSensitivity,
    tabletRotation: TabletRotation,
    advancedDown: Int,
    advancedUp: Int,
    overlayPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onCursor: (Boolean) -> Unit,
    onCursorSize: (HoverCursorSize) -> Unit,
    onCursorColor: (Int) -> Unit,
    onSaveCursorColor: (Int) -> Unit,
    onSensitivity: (PressureSensitivity) -> Unit,
    onRotation: (TabletRotation) -> Unit,
    onAdvancedThresholds: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
        Text("General", style = MaterialTheme.typography.titleMedium)
        Text("Inicio del stylus: manual (no se inicia al reiniciar el dispositivo).", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mostrar cursor en hover", Modifier.weight(1f)); Switch(showCursor, onCursor)
        }
        Text("Permiso de superposición: ${if (overlayPermissionGranted) "Concedido" else "Requerido para mostrar el cursor"}")
        if (!overlayPermissionGranted) OutlinedButton(onClick = onRequestOverlayPermission) { Text("Conceder permiso de cursor") }
        Text("Tamaño del cursor")
        ChoiceRow(HoverCursorSize.entries.toList(), cursorSize, {
            when (it) { HoverCursorSize.SMALL -> "Pequeño"; HoverCursorSize.MEDIUM -> "Mediano"; HoverCursorSize.LARGE -> "Grande" }
        }, onCursorSize)
        Text("Color del cursor")
        RgbHueWheel(cursorColor, onCursorColor)
        HexColorEditor(cursorColor, onCursorColor)
        Text("Colores guardados", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            savedCursorColors.forEachIndexed { index, savedColor ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                    if (savedColor == null) {
                        OutlinedButton(onClick = { onSaveCursorColor(index) }, modifier = Modifier.fillMaxWidth()) { Text("Guardar") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(28.dp).background(Color(savedColor), RoundedCornerShape(8.dp))
                                    .clickable { onCursorColor(savedColor) },
                            )
                            TextButtonCompact("Usar", onClick = { onCursorColor(savedColor) })
                        }
                        Text(com.example.wacommapper.output.RgbColorWheel.formatHex(savedColor), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("Stylus", style = MaterialTheme.typography.titleMedium)
        Text("Sensibilidad de contacto")
        ChoiceRow(PressureSensitivity.entries.toList(), sensitivity, {
            when (it) { PressureSensitivity.SOFT -> "Suave"; PressureSensitivity.NORMAL -> "Normal"; PressureSensitivity.FIRM -> "Firme" }
        }, onSensitivity)
        Text("Umbrales avanzados: DOWN=$advancedDown · UP=$advancedUp")
        Text(
            "DOWN inicia el contacto cuando la presión RAW llega al umbral. UP lo termina al bajar al suyo. " +
                "Entre ambos se conserva el estado anterior para evitar temblores; esto no cambia la presión enviada.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (advancedDown > advancedUp + 1) onAdvancedThresholds(advancedDown - 1, advancedUp) }) { Text("DOWN −") }
            OutlinedButton(onClick = { if (advancedDown < 2047) onAdvancedThresholds(advancedDown + 1, advancedUp) }) { Text("DOWN +") }
            OutlinedButton(onClick = { if (advancedUp > 0) onAdvancedThresholds(advancedDown, advancedUp - 1) }) { Text("UP −") }
            OutlinedButton(onClick = { if (advancedUp < advancedDown - 1) onAdvancedThresholds(advancedDown, advancedUp + 1) }) { Text("UP +") }
        }
        Text("Mapping", style = MaterialTheme.typography.titleMedium)
        Text("Área de tableta: FULL TABLET")
        Text("Orientación de tableta")
        ChoiceRow(TabletRotation.entries.toList(), tabletRotation, { "${it.degrees}°" }, onRotation)
        Text("El uso horizontal es el recomendado para esta versión.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}

@Composable
private fun HexColorEditor(color: Int, onColor: (Int) -> Unit) {
    var hex by remember(color) { mutableStateOf(com.example.wacommapper.output.RgbColorWheel.formatHex(color)) }
    var error by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hex,
            onValueChange = { value ->
                hex = value.take(7)
                error = false
            },
            modifier = Modifier.weight(1f),
            label = { Text("Código hex RGB") },
            singleLine = true,
            isError = error,
            supportingText = { if (error) Text("Usa 6 dígitos hexadecimales, por ejemplo #77ABBF") else Text("Formato: #RRGGBB") },
        )
        OutlinedButton(onClick = {
            val parsed = com.example.wacommapper.output.RgbColorWheel.parseHex(hex)
            if (parsed == null) error = true else {
                onColor(parsed)
                hex = com.example.wacommapper.output.RgbColorWheel.formatHex(parsed)
                error = false
            }
        }) { Text("Aplicar") }
    }
}

@Composable
private fun TextButtonCompact(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun RgbHueWheel(color: Int, onColor: (Int) -> Unit) {
    val wheelColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val hue = RgbColorWheel.hueOfColor(color)
    Canvas(
        Modifier.size(184.dp).pointerInput(Unit) {
            detectTapGestures { point ->
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val dx = point.x - centerX
                val dy = point.y - centerY
                val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                if (radius >= minOf(size.width, size.height) * 0.28f) {
                    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    onColor(RgbColorWheel.colorAtHue(degrees))
                }
            }
        },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.48f
        drawCircle(brush = Brush.sweepGradient(wheelColors, center), center = center, radius = radius)
        drawCircle(color = surfaceColor, center = center, radius = radius * 0.62f)
        val radians = Math.toRadians(hue.toDouble())
        val marker = Offset(
            center.x + cos(radians).toFloat() * radius * 0.80f,
            center.y + sin(radians).toFloat() * radius * 0.80f,
        )
        drawCircle(Color.White, radius = 9.dp.toPx(), center = marker, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color(color), radius = 5.dp.toPx(), center = marker)
    }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        values.forEach { value ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == value, onClick = { onSelect(value) })
                Text(label(value), Modifier.padding(start = 4.dp))
            }
        }
    }
}
