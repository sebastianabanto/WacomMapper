package com.example.wacommapper

import com.example.wacommapper.mapping.GeometryPattern
import com.example.wacommapper.mapping.GlobalGeometryDiagnostic
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.TabletRotation
import com.example.wacommapper.mapping.TabletCoordinateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalGeometryDiagnosticTest {
    @Test
    fun globalDefaultScalesAreEqualForCtl472AndLandscapeTabS7() {
        val config = TabletCoordinateConfig()
        val sx = 2_560.0 / config.bounds.width
        val sy = 1_600.0 / config.bounds.height
        assertEquals(0.1684210526, sx, 1e-9)
        assertEquals(sx, sy, 1e-9)
    }

    @Test
    fun manualTabletRotation90TransformsOnlyWhenExplicitlySelected() {
        val samples = listOf(0.0, 1.0).mapIndexed { index, x ->
            GlobalGeometryDiagnostic.sample(
                GeometryPattern.HORIZONTAL_SWEEP, index, x, 0.5,
                2_560, 1_600, 0,
                MappingOptions(tabletRotation = TabletRotation.ROTATION_90),
            )
        }
        assertEquals(samples[0].xAfterRotation, samples[1].xAfterRotation, 1e-6)
        assertTrue(samples[1].yAfterRotation > samples[0].yAfterRotation)
        assertEquals(1_280.0, samples[0].xAfterRotation, 1e-6)
        assertEquals(0.0, samples[0].yAfterRotation, 1e-6)
        assertEquals(1_600.0, samples[1].yAfterRotation, 1e-6)
    }

    @Test
    fun defaultRotationZeroMapsHorizontalAndVerticalToLogicalAxes() {
        val start = GlobalGeometryDiagnostic.sample(
            GeometryPattern.HORIZONTAL_SWEEP, 0, 0.0, 0.5,
            2_560, 1_600, 0, MappingOptions(),
        )
        val end = GlobalGeometryDiagnostic.sample(
            GeometryPattern.HORIZONTAL_SWEEP, 1, 1.0, 0.5,
            2_560, 1_600, 0, MappingOptions(),
        )
        val top = GlobalGeometryDiagnostic.sample(
            GeometryPattern.VERTICAL_SWEEP, 0, 0.5, 0.0,
            2_560, 1_600, 0, MappingOptions(),
        )
        val bottom = GlobalGeometryDiagnostic.sample(
            GeometryPattern.VERTICAL_SWEEP, 1, 0.5, 1.0,
            2_560, 1_600, 0, MappingOptions(),
        )
        assertEquals(0.0, start.xAfterRotation, 1e-6)
        assertEquals(2_560.0, end.xAfterRotation, 1e-6)
        assertEquals(800.0, start.yAfterRotation, 1e-6)
        assertEquals(1_280.0, top.xAfterRotation, 1e-6)
        assertEquals(0.0, top.yAfterRotation, 1e-6)
        assertEquals(1_600.0, bottom.yAfterRotation, 1e-6)
    }

    @Test
    fun rectangleMapsToFourLogicalDisplayCorners() {
        val path = GlobalGeometryDiagnostic.normalizedPath(GeometryPattern.RECTANGLE)
        val actual = path.take(4).mapIndexed { index, point ->
            val sample = GlobalGeometryDiagnostic.sample(
                GeometryPattern.RECTANGLE, index, point.first, point.second,
                2_560, 1_600, 0, MappingOptions(),
            )
            sample.xAfterRotation to sample.yAfterRotation
        }
        assertEquals(listOf(0.0 to 0.0, 2_560.0 to 0.0, 2_560.0 to 1_600.0, 0.0 to 1_600.0), actual)
    }

    @Test
    fun physicalCirclePathCompensatesForTabletAspectRatio() {
        val path = GlobalGeometryDiagnostic.normalizedPath(GeometryPattern.CIRCLE_ELLIPSE)
        val right = path.first()
        val top = path[path.size / 4]
        val rightRadius = (right.first - 0.5) * 2_560
        val topRadius = (top.second - 0.5) * 1_600
        assertEquals(kotlin.math.abs(rightRadius), kotlin.math.abs(topRadius), 1e-6)
        assertEquals(1.6, TabletCoordinateConfig().bounds.aspectRatio, 1e-9)
    }
}
