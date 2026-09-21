package com.personal.autocheckin

import android.annotation.SuppressLint
import android.content.Context

// Tiny state commits must survive immediate process death after scheduling or locking.
@SuppressLint("ApplySharedPref")
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        if (prefs.getInt("scheduleVersion", 0) < 2) {
            // Preserve the old master switch/time and add the requested 18:00 evening slot.
            prefs.edit().putInt("scheduleVersion", 2)
                .putBoolean("clock_in.enabled", true)
                .putInt("clock_in.hour", prefs.getInt("hour", 8))
                .putInt("clock_in.minute", prefs.getInt("minute", 30))
                .putBoolean("clock_out.enabled", true)
                .putInt("clock_out.hour", 18).putInt("clock_out.minute", 0)
                .remove("hour").remove("minute").remove("scheduledAt").commit()
        }
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    fun schedule(slot: PunchSlot) = PunchSchedule(slot, prefs.getBoolean("${slot.key}.enabled", true),
        prefs.getInt("${slot.key}.hour", slot.defaultHour), prefs.getInt("${slot.key}.minute", slot.defaultMinute))
    val schedules: List<PunchSchedule> get() = PunchSlot.entries.map(::schedule)
    fun saveSchedule(schedule: PunchSchedule) {
        val prefix = schedule.slot.key
        prefs.edit().putBoolean("$prefix.enabled", schedule.enabled)
            .putInt("$prefix.hour", schedule.hour).putInt("$prefix.minute", schedule.minute).commit()
    }
    fun scheduledAt(slot: PunchSlot): Long = prefs.getLong("${slot.key}.scheduledAt", 0)
    fun setScheduledAt(slot: PunchSlot, value: Long) {
        prefs.edit().putLong("${slot.key}.scheduledAt", value).commit()
    }
    var activeRunLabel: String
        get() = prefs.getString("activeRunLabel", "任务")!!
        set(value) { prefs.edit().putString("activeRunLabel", value).commit() }
    var activeRun: String?
        get() = prefs.getString("activeRun", null)
        set(value) { prefs.edit().putString("activeRun", value).commit() }
    val keywords: List<String>
        get() = prefs.getString("keywords", "打卡成功")!!.lines().map(String::trim).filter(String::isNotEmpty)
    val click: ClickConfig
        get() = ClickConfig(
            prefs.getBoolean("clickEnabled", false), prefs.getString("text", "")!!,
            prefs.getString("description", "")!!, prefs.getString("resourceId", "")!!,
            prefs.getBoolean("coordinateEnabled", false), prefs.getInt("x", 0), prefs.getInt("y", 0),
            prefs.getInt("width", 0), prefs.getInt("height", 0), prefs.getInt("rotation", 0),
        )
    fun saveAdvanced(keywords: List<String>, click: ClickConfig) {
        require(keywords.isNotEmpty() && keywords.all { it.isNotBlank() })
        prefs.edit().putString("keywords", keywords.joinToString("\n"))
            .putBoolean("clickEnabled", click.enabled).putString("text", click.text)
            .putString("description", click.description).putString("resourceId", click.resourceId)
            .putBoolean("coordinateEnabled", click.coordinateEnabled)
            .putInt("x", click.x).putInt("y", click.y).putInt("width", click.width)
            .putInt("height", click.height).putInt("rotation", click.rotation).commit()
    }
}
