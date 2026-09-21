package com.personal.autocheckin

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings

@SuppressLint("ApplySharedPref")
class MotionSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("motion_settings", Context.MODE_PRIVATE)
    init {
        if (prefs.getInt("timingVersion", 0) < 2) {
            val now = SystemClock.elapsedRealtime()
            prefs.edit().putInt("timingVersion", 2)
                .putLong("armAt", minOf(armAt, now + MotionPolicy.ARM_DELAY_MS))
                .putLong("cooldownUntil", minOf(cooldownUntil, now + MotionPolicy.COOLDOWN_MS)).commit()
        }
    }
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val testMode: Boolean get() = prefs.getBoolean("testMode", false)
    var sensitive: Boolean
        get() = prefs.getBoolean("sensitive", true)
        set(value) { prefs.edit().putBoolean("sensitive", value).commit() }
    val modeLabel: String get() = if (testMode) "自测" else "正式监测"
    val cooldownMs: Long get() = MotionPolicy.cooldownMs(testMode)
    val armAt: Long get() = prefs.getLong("armAt", 0)
    val cooldownUntil: Long get() = prefs.getLong("cooldownUntil", 0)
    val lastEvent: String get() = prefs.getString("lastEvent", "尚未检测到移动")!!
    var problem: String
        get() = prefs.getString("problem", "")!!
        set(value) { prefs.edit().putString("problem", value).commit() }
    private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun enable(test: Boolean = false) {
        // Explicit self-tests can restart immediately; normal re-enabling preserves normal cooldown.
        val sameBoot = prefs.getInt("boot", -2) == bootCount()
        val nextCooldown = MotionPolicy.startCooldown(cooldownUntil, sameBoot, testMode, test)
        prefs.edit().putBoolean("enabled", true).putLong("armAt", SystemClock.elapsedRealtime() + MotionPolicy.ARM_DELAY_MS)
            .putBoolean("testMode", test).putLong("cooldownUntil", nextCooldown)
            .putInt("boot", bootCount()).putString("problem", "").commit()
    }

    fun disable() {
        val edit = prefs.edit().putBoolean("enabled", false).putBoolean("testMode", false).putString("problem", "")
        if (testMode) edit.putLong("cooldownUntil", 0)
        edit.commit()
    }

    fun normalizeBoot() {
        if (enabled && prefs.getInt("boot", -2) != bootCount()) {
            prefs.edit().putInt("boot", bootCount()).putLong("armAt", SystemClock.elapsedRealtime() + MotionPolicy.ARM_DELAY_MS)
                .putLong("cooldownUntil", 0).putBoolean("testMode", false).commit()
        }
    }

    fun recordMovement(summary: String, now: Long) {
        prefs.edit().putString("lastEvent", summary).putLong("cooldownUntil", now + cooldownMs).commit()
    }
}
