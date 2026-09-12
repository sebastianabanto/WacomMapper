package com.example.wacommapper.output

import android.content.Context
import com.example.wacommapper.mapping.MappingMode
import com.example.wacommapper.mapping.MappingOptions
import com.example.wacommapper.mapping.TabletRotation

enum class HoverCursorSize(val diameterDp: Float) { SMALL(3f), MEDIUM(6f), LARGE(10f) }
enum class PressureSensitivity(val down: Int, val up: Int) { SOFT(3, 1), NORMAL(5, 2), FIRM(10, 5) }
const val DEFAULT_HOVER_CURSOR_COLOR: Int = 0xFF77ABBF.toInt()

/** Small scalar preferences; active-session state is deliberately never persisted. */
class WacomPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wacom_mapper_preferences", Context.MODE_PRIVATE)

    var showHoverCursor: Boolean
        get() = prefs.getBoolean(KEY_CURSOR, true)
        set(value) { persist(prefs.edit().putBoolean(KEY_CURSOR, value).commit()) }

    var cursorSize: HoverCursorSize
        get() = enumValue(prefs.getString(KEY_CURSOR_SIZE, null), HoverCursorSize.SMALL)
        set(value) { persist(prefs.edit().putString(KEY_CURSOR_SIZE, value.name).commit()) }

    var pressureSensitivity: PressureSensitivity
        get() = enumValue(prefs.getString(KEY_SENSITIVITY, null), PressureSensitivity.NORMAL)
        set(value) { persist(prefs.edit().putString(KEY_SENSITIVITY, value.name).commit()) }

    var tipDownThreshold: Int
        get() = prefs.getInt(KEY_DOWN, pressureSensitivity.down)
        set(value) { persist(prefs.edit().putInt(KEY_DOWN, value.coerceIn(1, 2047)).commit()) }

    var tipUpThreshold: Int
        get() = prefs.getInt(KEY_UP, pressureSensitivity.up.coerceAtMost(tipDownThreshold - 1))
        set(value) { persist(prefs.edit().putInt(KEY_UP, value.coerceIn(0, (tipDownThreshold - 1).coerceAtLeast(0))).commit()) }

    var tabletRotation: TabletRotation
        get() = enumValue(prefs.getString(KEY_ROTATION, null), TabletRotation.ROTATION_0)
        set(value) { persist(prefs.edit().putString(KEY_ROTATION, value.name).commit()) }

    var mappingMode: MappingMode
        get() = enumValue(prefs.getString(KEY_MAPPING_MODE, null), MappingMode.FULL_TABLET)
        set(value) { persist(prefs.edit().putString(KEY_MAPPING_MODE, value.name).commit()) }

    var cursorColor: Int
        get() = prefs.getInt(KEY_CURSOR_COLOR, DEFAULT_HOVER_CURSOR_COLOR)
        set(value) { persist(prefs.edit().putInt(KEY_CURSOR_COLOR, value).commit()) }

    val savedCursorColors: List<Int?>
        get() = (0 until SAVED_CURSOR_COLOR_COUNT).map { index ->
            val key = savedCursorColorKey(index)
            if (prefs.contains(key)) prefs.getInt(key, DEFAULT_HOVER_CURSOR_COLOR) else null
        }

    fun saveCursorColor(index: Int, color: Int) {
        require(index in 0 until SAVED_CURSOR_COLOR_COUNT) { "Color slot index out of range" }
        persist(prefs.edit().putInt(savedCursorColorKey(index), color).commit())
    }

    val mappingOptions: MappingOptions
        get() = MappingOptions(mode = mappingMode, tabletRotation = tabletRotation)

    private inline fun <reified T : Enum<T>> enumValue(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private fun persist(success: Boolean) {
        if (!success) android.util.Log.e("WACOM_PREFS", "Could not persist preference synchronously")
    }

    private fun savedCursorColorKey(index: Int) = "saved_hover_cursor_color_$index"

    private companion object {
        const val SAVED_CURSOR_COLOR_COUNT = 3
        const val KEY_CURSOR = "show_hover_cursor"
        const val KEY_CURSOR_SIZE = "hover_cursor_size"
        const val KEY_SENSITIVITY = "pressure_sensitivity"
        const val KEY_DOWN = "tip_down_threshold"
        const val KEY_UP = "tip_up_threshold"
        const val KEY_ROTATION = "tablet_rotation"
        const val KEY_MAPPING_MODE = "mapping_mode"
        const val KEY_CURSOR_COLOR = "hover_cursor_color"
    }
}
