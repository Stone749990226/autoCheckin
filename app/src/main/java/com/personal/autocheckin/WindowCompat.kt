package com.personal.autocheckin

import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** Platform-only fallbacks for Android 10; retain modern window APIs on Android 11+. */
object WindowCompat {
    @Suppress("DEPRECATION")
    fun applyInsets(root: View, includeIme: Boolean = false) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                    (if (includeIme) WindowInsets.Type.ime() else 0)
                val bars = insets.getInsets(types)
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                // Legacy system-window insets also include the keyboard with adjustResize.
                val cutout = insets.displayCutout
                view.setPadding(
                    maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                    maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                    maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                    maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
                )
            }
            insets
        }
    }

    @Suppress("DEPRECATION")
    fun maximumBounds(manager: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= 30) return manager.maximumWindowMetrics.bounds
        val size = Point()
        manager.defaultDisplay.getRealSize(size)
        return Rect(0, 0, size.x, size.y)
    }
}
