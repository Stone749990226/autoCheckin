package com.personal.autocheckin

enum class MotionPhase { DISABLED, ARMING, COOLDOWN, ARMED }

object MotionPolicy {
    const val ARM_DELAY_MS = 10_000L
    const val COOLDOWN_MS = 60_000L
    const val TEST_COOLDOWN_MS = 15_000L
    fun cooldownMs(testMode: Boolean): Long = if (testMode) TEST_COOLDOWN_MS else COOLDOWN_MS

    fun startCooldown(previous: Long, sameBoot: Boolean, wasTest: Boolean, testMode: Boolean): Long =
        if (sameBoot && !wasTest && !testMode) previous else 0

    fun phase(enabled: Boolean, now: Long, armAt: Long, cooldownUntil: Long): MotionPhase = when {
        !enabled -> MotionPhase.DISABLED
        now < armAt -> MotionPhase.ARMING
        now < cooldownUntil -> MotionPhase.COOLDOWN
        else -> MotionPhase.ARMED
    }

    fun acceptsEvent(enabled: Boolean, now: Long, eventAt: Long, registeredAt: Long, armAt: Long, cooldownUntil: Long): Boolean =
        phase(enabled, now, armAt, cooldownUntil) == MotionPhase.ARMED &&
            eventAt >= registeredAt && eventAt >= armAt && eventAt >= cooldownUntil && eventAt <= now
}
