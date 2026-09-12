package com.example.wacommapper.mapping

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class GeometryPattern(val label: String) {
    HORIZONTAL_SWEEP("HORIZONTAL_SWEEP"),
    VERTICAL_SWEEP("VERTICAL_SWEEP"),
    DIAGONAL("DIAGONAL"),
    RECTANGLE("RECTANGLE"),
    CIRCLE_ELLIPSE("CIRCLE / ELLIPSE TEST"),
}

data class GeometryStageSample(
    val pattern: GeometryPattern,
    val index: Int,
    val rawX: Double,
    val rawY: Double,
    val normalizedX: Double,
    val normalizedY: Double,
    val mappedXBeforeRotation: Double,
    val mappedYBeforeRotation: Double,
    val rotationDegrees: Int,
    val xAfterRotation: Double,
    val yAfterRotation: Double,
    val displayWidth: Int,
    val displayHeight: Int,
    val motionEventX: Float,
    val motionEventY: Float,
    val displayId: Int,
    val matrixDescription: String,
)

object GlobalGeometryDiagnostic {
    fun normalizedPath(pattern: GeometryPattern, samples: Int = 21): List<Pair<Double, Double>> {
        require(samples >= 2)
        return when (pattern) {
            GeometryPattern.HORIZONTAL_SWEEP -> (0..samples).map { (it.toDouble() / samples) to 0.5 }
            GeometryPattern.VERTICAL_SWEEP -> (0..samples).map { 0.5 to (it.toDouble() / samples) }
            GeometryPattern.DIAGONAL -> (0..samples).map { val t = it.toDouble() / samples; t to t }
            GeometryPattern.RECTANGLE -> listOf(
                0.0 to 0.0,
                1.0 to 0.0,
                1.0 to 1.0,
                0.0 to 1.0,
                0.0 to 0.0,
            )
            GeometryPattern.CIRCLE_ELLIPSE -> {
                val steps = ((samples * 2 + 3) / 4) * 4
                (0..steps).map { index ->
                    val angle = 2.0 * PI * index / steps
                    val radiusX = 0.25
                    val radiusY = radiusX * TabletCoordinateConfig.CTL472_BOUNDS.aspectRatio
                    0.5 + radiusX * cos(angle) to 0.5 + radiusY * sin(angle)
                }
            }
        }
    }

    fun sample(
        pattern: GeometryPattern,
        index: Int,
        normalizedX: Double,
        normalizedY: Double,
        displayWidth: Int,
        displayHeight: Int,
        displayId: Int,
        options: MappingOptions,
        config: TabletCoordinateConfig = TabletCoordinateConfig(),
    ): GeometryStageSample {
        require(displayWidth > 0 && displayHeight > 0)
        val xNorm = normalizedX.coerceIn(0.0, 1.0)
        val yNorm = normalizedY.coerceIn(0.0, 1.0)
        val rawX = config.bounds.minX + xNorm * config.bounds.width
        val rawY = config.bounds.minY + yNorm * config.bounds.height
        val mapped = CoordinateMapper.map(rawX, rawY, displayWidth, displayHeight, config, options)
        val beforeX = mapped.activeNormX * displayWidth
        val beforeY = mapped.activeNormY * displayHeight
        val afterX = mapped.screenX
        val afterY = mapped.screenY
        val matrix = rotationMatrix(options.tabletRotation)
        return GeometryStageSample(
            pattern = pattern,
            index = index,
            rawX = rawX,
            rawY = rawY,
            normalizedX = xNorm,
            normalizedY = yNorm,
            mappedXBeforeRotation = beforeX,
            mappedYBeforeRotation = beforeY,
            rotationDegrees = options.tabletRotation.degrees,
            xAfterRotation = afterX,
            yAfterRotation = afterY,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            motionEventX = afterX.toFloat().coerceIn(0f, (displayWidth - 1).toFloat()),
            motionEventY = afterY.toFloat().coerceIn(0f, (displayHeight - 1).toFloat()),
            displayId = displayId,
            matrixDescription = matrix,
        )
    }

    fun scaleSummary(displayWidth: Int, displayHeight: Int, config: TabletCoordinateConfig = TabletCoordinateConfig()): String {
        val scaleX = displayWidth / config.bounds.width
        val scaleY = displayHeight / config.bounds.height
        return "sx=$scaleX sy=$scaleY ratio=${scaleX / scaleY}"
    }

    private fun rotationMatrix(rotation: TabletRotation): String = when (rotation) {
        TabletRotation.ROTATION_0 -> "[1 0; 0 1]"
        TabletRotation.ROTATION_90 -> "[0 -1; 1 0] + [1; 0]"
        TabletRotation.ROTATION_180 -> "[-1 0; 0 -1] + [1; 1]"
        TabletRotation.ROTATION_270 -> "[0 1; -1 0] + [0; 1]"
    }
}
