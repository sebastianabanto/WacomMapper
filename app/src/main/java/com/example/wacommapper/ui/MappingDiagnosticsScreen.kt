package com.example.wacommapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.wacommapper.display.AndroidDisplayInfo
import com.example.wacommapper.mapping.ActiveAreaCalculator
import com.example.wacommapper.mapping.ActiveTabletArea
import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.MappingMode
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.TabletRotation
import com.example.wacommapper.mapping.MappedCoordinates
import com.example.wacommapper.mapping.TabletCoordinateConfig
import com.example.wacommapper.usb.Ctl472ParserEvidence
import com.example.wacommapper.usb.HypothesisStatus
import com.example.wacommapper.usb.ParsedCtl472Report
import kotlin.math.abs
import kotlin.math.hypot

private data class NinePointTarget(val label: String, val x: Double, val y: Double)
private data class NinePointResult(
    val target: NinePointTarget,
    val actualX: Double,
    val actualY: Double,
    val errorX: Double,
    val errorY: Double,
)

private val ninePointTargets = listOf(
    NinePointTarget("top-left", 0.0, 0.0),
    NinePointTarget("top-center", 0.5, 0.0),
    NinePointTarget("top-right", 1.0, 0.0),
    NinePointTarget("center-left", 0.0, 0.5),
    NinePointTarget("center", 0.5, 0.5),
    NinePointTarget("center-right", 1.0, 0.5),
    NinePointTarget("bottom-left", 0.0, 1.0),
    NinePointTarget("bottom-center", 0.5, 1.0),
    NinePointTarget("bottom-right", 1.0, 1.0),
)

@Composable
fun MappingDiagnosticsScreen(
    displayInfo: AndroidDisplayInfo,
    parsed: ParsedCtl472Report?,
    parserEvidence: Ctl472ParserEvidence,
    mappingOptions: MappingOptions,
    onMappingOptionsChanged: (MappingOptions) -> Unit,
) {
    val tabletConfig = remember { TabletCoordinateConfig() }
    var targetIndex by remember { mutableIntStateOf(0) }
    val pointResults = remember { mutableStateListOf<NinePointResult>() }
    var tracingCircle by remember { mutableStateOf(false) }
    val circlePoints = remember { mutableStateListOf<Offset>() }

    val options = mappingOptions
    val mapped = parsed?.let {
        CoordinateMapper.map(
            xRaw = it.candidateX.toDouble(),
            yRaw = it.candidateY.toDouble(),
            usableWidth = displayInfo.usableWidth,
            usableHeight = displayInfo.usableHeight,
            config = tabletConfig,
            options = options,
        )
    }
    val pressureNorm = parsed?.let { CoordinateMapper.normalizePressure(it.candidatePressure, tabletConfig.pressureBounds) }
    val activeArea = mapped?.activeArea ?: activeAreaFor(displayInfo, tabletConfig, options)
    val target = ninePointTargets.getOrNull(targetIndex)

    LaunchedEffect(tracingCircle, mapped?.screenNormX, mapped?.screenNormY) {
        if (tracingCircle && mapped != null) {
            val point = Offset(mapped.screenNormX.toFloat(), mapped.screenNormY.toFloat())
            val last = circlePoints.lastOrNull()
            if (last == null || hypot((point.x - last.x).toDouble(), (point.y - last.y).toDouble()) > 0.002) {
                circlePoints.add(point)
                if (circlePoints.size > MAX_CIRCLE_POINTS) circlePoints.removeAt(0)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mapping Diagnostics — app-local only")
        Text("No Android cursor/event injection is performed.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MappingMode.entries.forEach { candidate ->
                OutlinedButton(
                    onClick = { onMappingOptionsChanged(options.copy(mode = candidate)) },
                    enabled = options.mode != candidate,
                ) { Text(candidate.name) }
            }
        }
        Text("Manual tablet rotation: ${options.tabletRotation.name} (${options.tabletRotation.degrees}°). Independent from Android display rotation.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TabletRotation.entries.forEach { candidate ->
                OutlinedButton(
                    onClick = { onMappingOptionsChanged(options.copy(tabletRotation = candidate)) },
                    enabled = options.tabletRotation != candidate,
                ) {
                    Text(candidate.name)
                }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = options.invertX, onCheckedChange = { onMappingOptionsChanged(options.copy(invertX = it)) })
            Text("invertX")
            Checkbox(checked = options.invertY, onCheckedChange = { onMappingOptionsChanged(options.copy(invertY = it)) })
            Text("invertY")
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Android display diagnostics")
                Text("Android orientation: ${displayInfo.configurationOrientation}; display rotation: ${displayInfo.rotationDegrees}°; manual tablet rotation: ${options.tabletRotation.name}")
                Text("WindowMetrics: ${displayInfo.windowWidth} × ${displayInfo.windowHeight} px (API ${displayInfo.apiLevel})")
                Text("Usable area: ${displayInfo.usableWidth} × ${displayInfo.usableHeight} px")
                Text("System bars insets L/T/R/B: ${displayInfo.systemBarsInsets.asText()}")
                Text("Status bar insets L/T/R/B: ${displayInfo.statusBarInsets.asText()}")
                Text("Navigation bar insets L/T/R/B: ${displayInfo.navigationBarInsets.asText()}")
                Text("Display cutout insets L/T/R/B: ${displayInfo.displayCutoutInsets.asText()}")
                Text("Cutout bounds: ${displayInfo.cutoutBoundingRects.joinToString().ifEmpty { "none reported" }}")
            }
        }
        Text("TABLET SPACE")
        Text("Tablet RAW: X=${parsed?.candidateX ?: "—"}; Y=${parsed?.candidateY ?: "—"}; pressure=${parsed?.candidatePressure ?: "—"}")
        Text("Tablet normalized: X=${mapped?.tabletNormX?.format3() ?: "—"}; Y=${mapped?.tabletNormY?.format3() ?: "—"}")
        Text("Active tablet area RAW: left=${activeArea.left.format1()}, top=${activeArea.top.format1()}, right=${activeArea.right.format1()}, bottom=${activeArea.bottom.format1()}")
        Text("Tablet aspect: ${tabletConfig.bounds.aspectRatio.format3()}; screen aspect: ${displayInfo.usableAspectRatio.format3()}")
        Text("Mapping mode: ${options.mode.name}; active area is centered and aspect-matched${if (options.tabletRotation.degrees % 180 == 90) " after explicit tablet quarter-turn" else ""}.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoordinatePane(
                label = "TABLET SPACE",
                aspectRatio = tabletConfig.bounds.aspectRatio,
                modifier = Modifier.weight(1f).height(150.dp),
                activeArea = activeArea,
                bounds = tabletConfig.bounds,
                pointX = mapped?.tabletNormX,
                pointY = mapped?.tabletNormY,
            )
            CoordinatePane(
                label = "SCREEN SPACE",
                aspectRatio = displayInfo.usableAspectRatio,
                modifier = Modifier.weight(1f).height(150.dp),
                pointX = mapped?.screenNormX,
                pointY = mapped?.screenNormY,
                targetX = target?.x,
                targetY = target?.y,
            )
        }
        Text("Screen usable: width=${displayInfo.usableWidth} px; height=${displayInfo.usableHeight} px")
        Text("Mapped: X=${mapped?.screenX?.format1() ?: "—"} px; Y=${mapped?.screenY?.format1() ?: "—"} px")
        Text("Screen normalized: X=${mapped?.screenNormX?.format3() ?: "—"}; Y=${mapped?.screenNormY?.format3() ?: "—"}")
        Text("Parser hypotheses: X ${parserEvidence.x.status}; Y ${parserEvidence.y.status}; pressure ${parserEvidence.pressure.status}; tip ${parserEvidence.tip.status}; side button ${parserEvidence.sideButton.status}")
        Text("Tip: ${parsedBitValue(parsed, parserEvidence.tip.candidate) ?: "unavailable"} (${parserEvidence.tip.status}); side button: ${parsedBitValue(parsed, parserEvidence.sideButton.candidate) ?: "unavailable"} (${parserEvidence.sideButton.status}); inRange: ${proximityBitValue(parsed, parserEvidence)}")
        Text("Pressure norm: ${pressureNorm?.format3() ?: "—"} using configured observed reference 0..2047")
        LinearProgressIndicator(progress = { (pressureNorm ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("9-point validation")
                if (target != null) {
                    Text("Target ${targetIndex + 1} of ${ninePointTargets.size}: ${target.label}")
                    Text("Expected normalized: X=${target.x.format3()}, Y=${target.y.format3()}")
                    Button(
                        onClick = {
                            val current = mapped ?: return@Button
                            val errorX = abs(current.screenNormX - target.x)
                            val errorY = abs(current.screenNormY - target.y)
                            pointResults.add(NinePointResult(target, current.screenNormX, current.screenNormY, errorX, errorY))
                            targetIndex++
                        },
                        enabled = mapped != null,
                    ) { Text("Record current mapped point") }
                } else {
                    Text("All nine points recorded")
                }
                Button(onClick = { targetIndex = 0; pointResults.clear() }) { Text("Reset 9-point test") }
                pointResults.forEach { result ->
                    Text("${result.target.label}: actual (${result.actualX.format3()}, ${result.actualY.format3()}); errorX=${result.errorX.format3()}, errorY=${result.errorY.format3()}")
                }
                if (pointResults.isNotEmpty()) {
                    val errors = pointResults.map { hypot(it.errorX, it.errorY) }
                    Text("Mean error: ${errors.average().format3()} normalized; max error: ${errors.maxOrNull()?.format3()} normalized")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Aspect-ratio circle test")
                Text("Trace a circle on the tablet while the stylus is in contact; the app plots its mapped path in SCREEN SPACE.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { tracingCircle = !tracingCircle }) { Text(if (tracingCircle) "Stop path" else "Start circle path") }
                    OutlinedButton(onClick = { circlePoints.clear() }) { Text("Clear path") }
                }
                CirclePathPane(
                    aspectRatio = displayInfo.usableAspectRatio,
                    points = circlePoints,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
                Text("FULL_TABLET can distort the path when tablet/screen aspects differ; FORCE_PROPORTIONS crops tablet input to preserve proportions.")
            }
        }
    }
}

@Composable
private fun CoordinatePane(
    label: String,
    aspectRatio: Double,
    modifier: Modifier,
    activeArea: ActiveTabletArea? = null,
    bounds: com.example.wacommapper.mapping.TabletBounds? = null,
    pointX: Double?,
    pointY: Double?,
    targetX: Double? = null,
    targetY: Double? = null,
) {
    Canvas(modifier.padding(4.dp)) {
        val rect = fitRect(size, aspectRatio)
        drawRect(Color(0xFF263238), rect.topLeft, rect.size, style = Stroke(3.dp.toPx()))
        if (activeArea != null && bounds != null) {
            val left = (activeArea.left - bounds.minX) / bounds.width
            val top = (activeArea.top - bounds.minY) / bounds.height
            val right = (activeArea.right - bounds.minX) / bounds.width
            val bottom = (activeArea.bottom - bounds.minY) / bounds.height
            drawRect(
                Color(0xFF43A047),
                Offset(rect.left + left.toFloat() * rect.width, rect.top + top.toFloat() * rect.height),
                Size((right - left).toFloat() * rect.width, (bottom - top).toFloat() * rect.height),
                style = Stroke(3.dp.toPx()),
            )
        }
        if (targetX != null && targetY != null) {
            drawCircle(Color(0xFF1976D2), 9.dp.toPx(), Offset(rect.left + targetX.toFloat() * rect.width, rect.top + targetY.toFloat() * rect.height), style = Stroke(2.dp.toPx()))
        }
        if (pointX != null && pointY != null) {
            drawCircle(Color(0xFFE53935), 6.dp.toPx(), Offset(rect.left + pointX.toFloat() * rect.width, rect.top + pointY.toFloat() * rect.height))
        }
    }
}

@Composable
private fun CirclePathPane(aspectRatio: Double, points: List<Offset>, modifier: Modifier) {
    Canvas(modifier.padding(4.dp)) {
        val rect = fitRect(size, aspectRatio)
        drawRect(Color(0xFF263238), rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(rect.left + points.first().x * rect.width, rect.top + points.first().y * rect.height)
                points.drop(1).forEach { point -> lineTo(rect.left + point.x * rect.width, rect.top + point.y * rect.height) }
            }
            drawPath(path, Color(0xFFE53935), style = Stroke(3.dp.toPx()))
        }
    }
}

private fun activeAreaFor(
    displayInfo: AndroidDisplayInfo,
    config: TabletCoordinateConfig,
    options: MappingOptions,
): ActiveTabletArea {
    if (options.mode == MappingMode.FULL_TABLET) {
        return ActiveAreaCalculator.calculate(config.bounds, config.bounds.aspectRatio)
    }
    val screenAspect = displayInfo.usableAspectRatio
    val activeTabletAspect = if (options.tabletRotation.degrees % 180 == 90) 1.0 / screenAspect else screenAspect
    return ActiveAreaCalculator.calculate(config.bounds, activeTabletAspect)
}

private fun parsedBitValue(parsed: ParsedCtl472Report?, candidate: String?): String? {
    val match = Regex("byte (\\d+), bit (\\d+)").find(candidate.orEmpty()) ?: return null
    val byte = match.groupValues[1].toIntOrNull() ?: return null
    val bit = match.groupValues[2].toIntOrNull() ?: return null
    val value = parsed?.flagBytes?.get(byte) ?: return null
    return ((value shr bit) and 1).toString()
}

private fun proximityBitValue(parsed: ParsedCtl472Report?, evidence: Ctl472ParserEvidence): String {
    val bit = evidence.possibleProximityBits.firstOrNull() ?: return "unavailable (UNCERTAIN)"
    val value = parsed?.flagBytes?.get(bit.first) ?: return "unavailable (UNCERTAIN)"
    return "${(value shr bit.second) and 1} (possible byte ${bit.first}, bit ${bit.second})"
}

private fun fitRect(size: Size, aspect: Double): androidx.compose.ui.geometry.Rect {
    val safeAspect = aspect.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val viewAspect = size.width / size.height.coerceAtLeast(1f)
    val width: Float
    val height: Float
    if (viewAspect > safeAspect) {
        height = size.height
        width = height * safeAspect.toFloat()
    } else {
        width = size.width
        height = width / safeAspect.toFloat()
    }
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f
    return androidx.compose.ui.geometry.Rect(left, top, left + width, top + height)
}

private fun com.example.wacommapper.display.DisplayInsets.asText() = "$left/$top/$right/$bottom"
private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
private fun Double.format3(): String = String.format(java.util.Locale.US, "%.3f", this)

private const val MAX_CIRCLE_POINTS = 1_000
