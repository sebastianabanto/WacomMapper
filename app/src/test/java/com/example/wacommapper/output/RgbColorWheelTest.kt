package com.example.wacommapper.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbColorWheelTest {
    @Test
    fun hueWheelReturnsOpaqueDistinctRgbColors() {
        val red = RgbColorWheel.colorAtHue(0f)
        val green = RgbColorWheel.colorAtHue(120f)
        val blue = RgbColorWheel.colorAtHue(240f)

        assertEquals(0xFF, red ushr 24)
        assertNotEquals(red, green)
        assertNotEquals(green, blue)
        assertNotEquals(blue, red)
    }

    @Test
    fun hueWheelWrapsAtFullTurnAndHandlesNegativeAngles() {
        assertEquals(RgbColorWheel.colorAtHue(0f), RgbColorWheel.colorAtHue(360f))
        assertEquals(RgbColorWheel.colorAtHue(300f), RgbColorWheel.colorAtHue(-60f))
        assertTrue(RgbColorWheel.hueOfColor(0xFF77ABBF.toInt()) in 180f..220f)
    }

    @Test
    fun parsesSixDigitHexWithOptionalHashAndOpaqueAlpha() {
        assertEquals(0xFF77ABBF.toInt(), RgbColorWheel.parseHex("#77ABBF"))
        assertEquals(0xFF12aBcD.toInt(), RgbColorWheel.parseHex("12aBcD"))
    }

    @Test
    fun rejectsInvalidHexAndFormatsWithoutAlpha() {
        assertEquals(null, RgbColorWheel.parseHex("#12AB"))
        assertEquals(null, RgbColorWheel.parseHex("#12ABCG"))
        assertEquals("#77ABBF", RgbColorWheel.formatHex(0xFF77ABBF.toInt()))
    }
}
