package com.example.wacommapper.output

import org.junit.Assert.assertTrue
import org.junit.Test

class PressureSensitivityTest {
    @Test
    fun everyDailyPresetUsesValidHysteresisAndIncreasingFirmness() {
        PressureSensitivity.entries.forEach { preset ->
            assertTrue("${preset.name} must keep UP below DOWN", preset.up in 0 until preset.down)
        }
        assertTrue(PressureSensitivity.SOFT.down < PressureSensitivity.NORMAL.down)
        assertTrue(PressureSensitivity.NORMAL.down < PressureSensitivity.FIRM.down)
    }
}
