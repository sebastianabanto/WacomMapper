package com.example.wacommapper.mapping

data class TabletBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val aspectRatio: Double get() = width / height

    init {
        require(maxX > minX) { "Tablet X bounds must have positive width" }
        require(maxY > minY) { "Tablet Y bounds must have positive height" }
    }
}

data class PressureBounds(val min: Double, val max: Double) {
    init {
        require(max > min) { "Pressure bounds must have positive range" }
    }
}

data class TabletCoordinateConfig(
    val bounds: TabletBounds = CTL472_BOUNDS,
    val pressureBounds: PressureBounds = CTL472_PRESSURE_BOUNDS,
) {
    companion object {
        val CTL472_BOUNDS = TabletBounds(minX = 0.0, maxX = 15_200.0, minY = 0.0, maxY = 9_500.0)
        val CTL472_PRESSURE_BOUNDS = PressureBounds(min = 0.0, max = 2_047.0)
    }
}

data class ActiveTabletArea(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val aspectRatio: Double get() = width / height
}

enum class MappingMode { FULL_TABLET, FORCE_PROPORTIONS }

/** Explicit tablet-to-display rotation; independent of Android's current display rotation. */
enum class TabletRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
}

data class MappingOptions(
    val mode: MappingMode = MappingMode.FULL_TABLET,
    val tabletRotation: TabletRotation = TabletRotation.ROTATION_0,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
)

data class MappedCoordinates(
    val xRaw: Double,
    val yRaw: Double,
    val tabletNormX: Double,
    val tabletNormY: Double,
    val activeNormX: Double,
    val activeNormY: Double,
    val screenNormX: Double,
    val screenNormY: Double,
    val screenX: Double,
    val screenY: Double,
    val activeArea: ActiveTabletArea,
)

object ActiveAreaCalculator {
    /** Returns the largest centered rectangle in tablet RAW coordinates for [targetAspectRatio]. */
    fun calculate(tabletBounds: TabletBounds, targetAspectRatio: Double): ActiveTabletArea {
        require(targetAspectRatio > 0.0 && targetAspectRatio.isFinite()) { "Target aspect ratio must be positive and finite" }
        val tabletAspect = tabletBounds.aspectRatio
        return when {
            kotlin.math.abs(tabletAspect - targetAspectRatio) < 1e-9 -> ActiveTabletArea(
                tabletBounds.minX,
                tabletBounds.minY,
                tabletBounds.maxX,
                tabletBounds.maxY,
            )
            tabletAspect > targetAspectRatio -> {
                val activeWidth = tabletBounds.height * targetAspectRatio
                val horizontalInset = (tabletBounds.width - activeWidth) / 2.0
                ActiveTabletArea(
                    left = tabletBounds.minX + horizontalInset,
                    top = tabletBounds.minY,
                    right = tabletBounds.maxX - horizontalInset,
                    bottom = tabletBounds.maxY,
                )
            }
            else -> {
                val activeHeight = tabletBounds.width / targetAspectRatio
                val verticalInset = (tabletBounds.height - activeHeight) / 2.0
                ActiveTabletArea(
                    left = tabletBounds.minX,
                    top = tabletBounds.minY + verticalInset,
                    right = tabletBounds.maxX,
                    bottom = tabletBounds.maxY - verticalInset,
                )
            }
        }
    }
}

object CoordinateMapper {
    /** Simple full-canvas mapping used by the internal stylus-event diagnostic. */
    fun mapToCanvas(
        xRaw: Int,
        yRaw: Int,
        targetWidth: Float,
        targetHeight: Float,
        config: TabletCoordinateConfig = TabletCoordinateConfig(),
    ): Pair<Float, Float> {
        require(targetWidth >= 0f && targetHeight >= 0f)
        val xNorm = ((xRaw - config.bounds.minX) / config.bounds.width).toFloat().coerceIn(0f, 1f)
        val yNorm = ((yRaw - config.bounds.minY) / config.bounds.height).toFloat().coerceIn(0f, 1f)
        return xNorm * targetWidth to yNorm * targetHeight
    }

    fun map(
        xRaw: Double,
        yRaw: Double,
        usableWidth: Int,
        usableHeight: Int,
        config: TabletCoordinateConfig = TabletCoordinateConfig(),
        options: MappingOptions = MappingOptions(),
    ): MappedCoordinates {
        require(usableWidth > 0 && usableHeight > 0) { "Usable display dimensions must be positive" }
        val bounds = config.bounds
        val xNorm = ((xRaw - bounds.minX) / bounds.width).coerceIn(0.0, 1.0)
        val yNorm = ((yRaw - bounds.minY) / bounds.height).coerceIn(0.0, 1.0)
        val screenAspect = usableWidth.toDouble() / usableHeight
        val targetTabletAspect = if (options.tabletRotation.degrees % 180 == 90) 1.0 / screenAspect else screenAspect
        val activeArea = if (options.mode == MappingMode.FORCE_PROPORTIONS) {
            ActiveAreaCalculator.calculate(bounds, targetTabletAspect)
        } else {
            ActiveAreaCalculator.calculate(bounds, bounds.aspectRatio)
        }
        val activeX = ((xRaw - activeArea.left) / activeArea.width).coerceIn(0.0, 1.0)
        val activeY = ((yRaw - activeArea.top) / activeArea.height).coerceIn(0.0, 1.0)
        val (rotatedX, rotatedY) = when (options.tabletRotation) {
            TabletRotation.ROTATION_0 -> activeX to activeY
            TabletRotation.ROTATION_90 -> (1.0 - activeY) to activeX
            TabletRotation.ROTATION_180 -> (1.0 - activeX) to (1.0 - activeY)
            TabletRotation.ROTATION_270 -> activeY to (1.0 - activeX)
        }
        val mappedX = (if (options.invertX) 1.0 - rotatedX else rotatedX).coerceIn(0.0, 1.0)
        val mappedY = (if (options.invertY) 1.0 - rotatedY else rotatedY).coerceIn(0.0, 1.0)
        return MappedCoordinates(
            xRaw = xRaw,
            yRaw = yRaw,
            tabletNormX = xNorm,
            tabletNormY = yNorm,
            activeNormX = activeX,
            activeNormY = activeY,
            screenNormX = mappedX,
            screenNormY = mappedY,
            screenX = mappedX * usableWidth,
            screenY = mappedY * usableHeight,
            activeArea = activeArea,
        )
    }

    fun normalizePressure(rawPressure: Int, bounds: PressureBounds = TabletCoordinateConfig.CTL472_PRESSURE_BOUNDS): Double =
        ((rawPressure - bounds.min) / (bounds.max - bounds.min)).coerceIn(0.0, 1.0)
}
