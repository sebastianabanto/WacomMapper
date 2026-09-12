package com.example.wacommapper.output

/** Converts a hue position on the settings wheel to an opaque RGB/ARGB color. */
object RgbColorWheel {
    /** Accepts RRGGBB or #RRGGBB and returns opaque ARGB, or null for invalid input. */
    fun parseHex(input: String): Int? {
        val value = input.trim().removePrefix("#")
        if (value.length != 6 || value.any { it.digitToIntOrNull(16) == null }) return null
        return (0xFF000000L or value.toLong(16)).toInt()
    }

    fun formatHex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    fun colorAtHue(hueDegrees: Float, saturation: Float = 0.78f, value: Float = 1f): Int {
        val hue = ((hueDegrees % 360f) + 360f) % 360f
        val sat = saturation.coerceIn(0f, 1f)
        val brightness = value.coerceIn(0f, 1f)
        val chroma = brightness * sat
        val secondary = chroma * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val match = brightness - chroma
        val (red, green, blue) = when (hue.toInt() / 60) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        fun channel(component: Float) = ((component + match) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    fun hueOfColor(color: Int): Float {
        val red = ((color shr 16) and 0xFF) / 255f
        val green = ((color shr 8) and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (delta == 0f) return 195f
        val hue = when (max) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }
}
