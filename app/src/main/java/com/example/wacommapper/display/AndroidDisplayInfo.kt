package com.example.wacommapper.display

import android.app.Activity
import android.graphics.Point
import android.os.Build
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

data class DisplayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class AndroidDisplayInfo(
    val displayId: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val configurationOrientation: String,
    val rotationDegrees: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    val usableWidth: Int,
    val usableHeight: Int,
    val systemBarsInsets: DisplayInsets,
    val statusBarInsets: DisplayInsets,
    val navigationBarInsets: DisplayInsets,
    val displayCutoutInsets: DisplayInsets,
    val cutoutBoundingRects: List<String>,
    val apiLevel: Int,
) {
    /** Current logical display dimensions, already expressed in the active Android orientation. */
    val logicalWidth: Int get() = displayWidth
    val logicalHeight: Int get() = displayHeight
    val usableAspectRatio: Double get() = usableWidth.toDouble() / usableHeight

    companion object {
        fun read(activity: Activity): AndroidDisplayInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readWindowMetrics(activity)
        } else {
            readLegacyDisplay(activity)
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun readWindowMetrics(activity: Activity): AndroidDisplayInfo {
            val display = activity.display ?: activity.windowManager.defaultDisplay
            val realDisplaySize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(realDisplaySize)
            val metrics = activity.getSystemService(WindowManager::class.java).currentWindowMetrics
            val bounds = metrics.bounds
            val windowInsets = metrics.windowInsets
            val system = windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val status = windowInsets.getInsets(WindowInsets.Type.statusBars())
            val navigation = windowInsets.getInsets(WindowInsets.Type.navigationBars())
            val cutout = windowInsets.getInsets(WindowInsets.Type.displayCutout())
            val rotation = activity.display?.rotation ?: Surface.ROTATION_0
            val configOrientation = activity.resources.configuration.orientation
            val cutoutRects = windowInsets.displayCutout?.boundingRects.orEmpty().map { rect ->
                "${rect.left},${rect.top}–${rect.right},${rect.bottom}"
            }
            return AndroidDisplayInfo(
                displayId = display.displayId,
                displayWidth = realDisplaySize.x,
                displayHeight = realDisplaySize.y,
                configurationOrientation = configurationOrientationName(configOrientation),
                rotationDegrees = rotation * 90,
                windowWidth = bounds.width(),
                windowHeight = bounds.height(),
                usableWidth = (bounds.width() - system.left - system.right).coerceAtLeast(1),
                usableHeight = (bounds.height() - system.top - system.bottom).coerceAtLeast(1),
                systemBarsInsets = DisplayInsets(system.left, system.top, system.right, system.bottom),
                statusBarInsets = DisplayInsets(status.left, status.top, status.right, status.bottom),
                navigationBarInsets = DisplayInsets(navigation.left, navigation.top, navigation.right, navigation.bottom),
                displayCutoutInsets = DisplayInsets(cutout.left, cutout.top, cutout.right, cutout.bottom),
                cutoutBoundingRects = cutoutRects,
                apiLevel = Build.VERSION.SDK_INT,
            )
        }

        @Suppress("DEPRECATION")
        private fun readLegacyDisplay(activity: Activity): AndroidDisplayInfo {
            val display = activity.windowManager.defaultDisplay
            val realSize = Point()
            display.getRealSize(realSize)
            val insets = activity.window.decorView.rootWindowInsets
            val status = DisplayInsets(top = insets?.systemWindowInsetTop ?: 0)
            val navigation = DisplayInsets(
                left = insets?.systemWindowInsetLeft ?: 0,
                right = insets?.systemWindowInsetRight ?: 0,
                bottom = insets?.systemWindowInsetBottom ?: 0,
            )
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets?.displayCutout?.let {
                    DisplayInsets(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom)
                } ?: DisplayInsets()
            } else DisplayInsets()
            val combined = DisplayInsets(
                left = maxOf(navigation.left, cutout.left),
                top = maxOf(status.top, cutout.top),
                right = maxOf(navigation.right, cutout.right),
                bottom = maxOf(navigation.bottom, cutout.bottom),
            )
            val visibleFrame = android.graphics.Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            val rotation = activity.windowManager.defaultDisplay.rotation
            return AndroidDisplayInfo(
                displayId = display.displayId,
                displayWidth = realSize.x,
                displayHeight = realSize.y,
                configurationOrientation = configurationOrientationName(activity.resources.configuration.orientation),
                rotationDegrees = rotation * 90,
                windowWidth = realSize.x,
                windowHeight = realSize.y,
                usableWidth = (realSize.x - combined.left - combined.right).coerceAtLeast(1),
                usableHeight = (realSize.y - combined.top - combined.bottom).coerceAtLeast(1),
                systemBarsInsets = combined,
                statusBarInsets = status,
                navigationBarInsets = navigation,
                displayCutoutInsets = cutout,
                cutoutBoundingRects = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    insets?.displayCutout?.boundingRects.orEmpty().map { "${it.left},${it.top}–${it.right},${it.bottom}" }
                } else emptyList(),
                apiLevel = Build.VERSION.SDK_INT,
            )
        }

        private fun configurationOrientationName(orientation: Int): String = when (orientation) {
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
            else -> "UNDEFINED"
        }
    }
}
