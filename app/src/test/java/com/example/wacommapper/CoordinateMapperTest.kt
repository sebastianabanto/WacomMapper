package com.example.wacommapper

import com.example.wacommapper.mapping.ActiveAreaCalculator
import com.example.wacommapper.mapping.CoordinateMapper
import com.example.wacommapper.mapping.MappingMode
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.TabletRotation
import com.example.wacommapper.mapping.TabletBounds
import com.example.wacommapper.mapping.TabletCoordinateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {
    private val bounds = TabletCoordinateConfig.CTL472_BOUNDS
    private val config = TabletCoordinateConfig()

    @Test
    fun normalizesRawCoordinatesAndMapsToUsableDisplay() {
        val mapped = CoordinateMapper.map(7_600.0, 4_750.0, 1_600, 1_000, config)

        assertEquals(0.5, mapped.tabletNormX, EPSILON)
        assertEquals(0.5, mapped.tabletNormY, EPSILON)
        assertEquals(800.0, mapped.screenX, EPSILON)
        assertEquals(500.0, mapped.screenY, EPSILON)
    }

    @Test
    fun clampsCoordinatesOutsideTabletBounds() {
        val mapped = CoordinateMapper.map(-100.0, 10_000.0, 1_600, 1_000, config)

        assertEquals(0.0, mapped.tabletNormX, EPSILON)
        assertEquals(1.0, mapped.tabletNormY, EPSILON)
        assertEquals(0.0, mapped.screenNormX, EPSILON)
        assertEquals(1.0, mapped.screenNormY, EPSILON)
    }

    @Test
    fun sameAspectRatioUsesPracticallyEntireTablet() {
        val active = ActiveAreaCalculator.calculate(bounds, 16.0 / 10.0)

        assertEquals(bounds.minX, active.left, EPSILON)
        assertEquals(bounds.maxX, active.right, EPSILON)
        assertEquals(bounds.minY, active.top, EPSILON)
        assertEquals(bounds.maxY, active.bottom, EPSILON)
    }

    @Test
    fun widerTabletCropsCenteredHorizontalArea() {
        val active = ActiveAreaCalculator.calculate(bounds, 1.2)

        assertEquals(1_900.0, active.left, EPSILON)
        assertEquals(13_300.0, active.right, EPSILON)
        assertEquals(0.0, active.top, EPSILON)
        assertEquals(9_500.0, active.bottom, EPSILON)
        assertEquals(1.2, active.aspectRatio, EPSILON)
    }

    @Test
    fun tallerTabletCropsCenteredVerticalArea() {
        val active = ActiveAreaCalculator.calculate(bounds, 2.0)

        assertEquals(0.0, active.left, EPSILON)
        assertEquals(15_200.0, active.right, EPSILON)
        assertEquals(950.0, active.top, EPSILON)
        assertEquals(8_550.0, active.bottom, EPSILON)
        assertEquals(2.0, active.aspectRatio, EPSILON)
    }

    @Test
    fun forceProportionsOnSixteenByTenUsesWholeCtl472() {
        val mapped = CoordinateMapper.map(
            xRaw = 15_200.0,
            yRaw = 9_500.0,
            usableWidth = 1_600,
            usableHeight = 1_000,
            options = MappingOptions(mode = MappingMode.FORCE_PROPORTIONS),
        )

        assertEquals(bounds.minX, mapped.activeArea.left, EPSILON)
        assertEquals(bounds.maxX, mapped.activeArea.right, EPSILON)
        assertEquals(bounds.minY, mapped.activeArea.top, EPSILON)
        assertEquals(bounds.maxY, mapped.activeArea.bottom, EPSILON)
    }

    @Test
    fun mapsAllFourOrientations() {
        val corners = listOf(
            TabletRotation.ROTATION_0 to (0.0 to 0.0),
            TabletRotation.ROTATION_90 to (1.0 to 0.0),
            TabletRotation.ROTATION_180 to (1.0 to 1.0),
            TabletRotation.ROTATION_270 to (0.0 to 1.0),
        )

        corners.forEach { (orientation, expected) ->
            val mapped = CoordinateMapper.map(
                xRaw = bounds.minX,
                yRaw = bounds.minY,
                usableWidth = 1_000,
                usableHeight = 1_000,
                options = MappingOptions(tabletRotation = orientation),
            )
            assertEquals(expected.first, mapped.screenNormX, EPSILON)
            assertEquals(expected.second, mapped.screenNormY, EPSILON)
        }
    }

    @Test
    fun manualQuarterTurnAndHalfTurnUseConfiguredTabletRotation() {
        val cases = listOf(
            TabletRotation.ROTATION_0 to (0.25 to 0.75),
            TabletRotation.ROTATION_90 to (0.25 to 0.25),
            TabletRotation.ROTATION_180 to (0.75 to 0.25),
            TabletRotation.ROTATION_270 to (0.75 to 0.75),
        )
        cases.forEach { (rotation, expected) ->
            val mapped = CoordinateMapper.map(
                xRaw = 3_800.0,
                yRaw = 7_125.0,
                usableWidth = 2_560,
                usableHeight = 1_600,
                options = MappingOptions(tabletRotation = rotation),
            )
            assertEquals(expected.first, mapped.screenNormX, EPSILON)
            assertEquals(expected.second, mapped.screenNormY, EPSILON)
        }
    }

    @Test
    fun rotationZeroMapsCtl472CornersAndCenterToLandscapeLogicalDisplay() {
        val corners = listOf(
            Triple(0.0, 0.0, 0.0 to 0.0),
            Triple(15_200.0, 0.0, 2_560.0 to 0.0),
            Triple(0.0, 9_500.0, 0.0 to 1_600.0),
            Triple(15_200.0, 9_500.0, 2_560.0 to 1_600.0),
            Triple(7_600.0, 4_750.0, 1_280.0 to 800.0),
        )
        corners.forEach { (x, y, expected) ->
            val actual = CoordinateMapper.map(x, y, 2_560, 1_600, options = MappingOptions())
            assertEquals(expected.first, actual.screenX, EPSILON)
            assertEquals(expected.second, actual.screenY, EPSILON)
        }
    }

    @Test
    fun defaultTabletRotationDoesNotDependOnAndroidDisplayRotation() {
        val options = MappingOptions()
        assertEquals(TabletRotation.ROTATION_0, options.tabletRotation)
        val landscape = CoordinateMapper.map(3_800.0, 7_125.0, 2_560, 1_600, options = options)
        val portrait = CoordinateMapper.map(3_800.0, 7_125.0, 1_600, 2_560, options = options)
        assertEquals(0.25, landscape.screenNormX, EPSILON)
        assertEquals(0.75, landscape.screenNormY, EPSILON)
        assertEquals(landscape.screenNormX, portrait.screenNormX, EPSILON)
        assertEquals(landscape.screenNormY, portrait.screenNormY, EPSILON)
        assertEquals(400.0, portrait.screenX, EPSILON)
        assertEquals(1_920.0, portrait.screenY, EPSILON)
    }

    @Test
    fun changingLogicalDimensionsOnlyChangesPixelScale() {
        val first = CoordinateMapper.map(3_800.0, 7_125.0, 2_560, 1_600, options = MappingOptions())
        val second = CoordinateMapper.map(3_800.0, 7_125.0, 1_600, 2_560, options = MappingOptions())
        assertEquals(first.tabletNormX, second.tabletNormX, EPSILON)
        assertEquals(first.tabletNormY, second.tabletNormY, EPSILON)
        assertEquals(first.screenNormX, second.screenNormX, EPSILON)
        assertEquals(first.screenNormY, second.screenNormY, EPSILON)
        assertEquals(first.screenX * (1_600.0 / 2_560.0), second.screenX, EPSILON)
        assertEquals(first.screenY * 1.6, second.screenY, EPSILON)
    }

    @Test
    fun landscapeTabletAndDisplayScalesAreEqual() {
        assertEquals(2_560.0 / 15_200.0, 1_600.0 / 9_500.0, EPSILON)
    }

    @Test
    fun pressureNormalizationUsesConfiguredRangeAndClamps() {
        assertEquals(0.0, CoordinateMapper.normalizePressure(-1), EPSILON)
        assertEquals(1_023.0 / 2_047.0, CoordinateMapper.normalizePressure(1_023), EPSILON)
        assertEquals(1.0, CoordinateMapper.normalizePressure(2_100), EPSILON)
    }

    @Test
    fun activeAreaRejectsInvalidAspectRatio() {
        val result = runCatching { ActiveAreaCalculator.calculate(TabletBounds(0.0, 1.0, 0.0, 1.0), 0.0) }

        assertTrue(result.isFailure)
    }

    private companion object {
        const val EPSILON = 1e-6
    }
}
