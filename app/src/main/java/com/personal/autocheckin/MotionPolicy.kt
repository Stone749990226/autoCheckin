package com.personal.autocheckin

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class MotionPhase { DISABLED, ARMING, COOLDOWN, ARMED }

/** 低频加速度兜底判定出的移动类型。 */
enum class AccelerationMotion { ORIENTATION, IMPACT }

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

/**
 * 低频（约 1 Hz）加速度兜底：只判断手机是否发生了大幅移动，不追踪细微晃动。
 * 首次采样建立重力方向基线，之后重力方向相对基线偏转 [ORIENTATION_DEGREES] 且连续
 * [REQUIRED_ORIENTATION_SAMPLES] 次，或单次动态加速度峰值超过 [IMPACT_MS2] 才算移动。
 * 本类不持有任何 Android 对象，可在单元测试中直接驱动。
 */
class AccelerationMotionDetector(
    private val orientationDegrees: Double = ORIENTATION_DEGREES,
    private val impactMs2: Double = IMPACT_MS2,
    private val requiredSamples: Int = REQUIRED_ORIENTATION_SAMPLES,
) {
    companion object {
        /** 相对布防基线的重力方向偏转阈值：静止噪声只有 1～2 度。 */
        const val ORIENTATION_DEGREES = 30.0
        /** 相对 1 g 的动态加速度峰值阈值。 */
        const val IMPACT_MS2 = 3.0
        /** 姿态偏转需要连续命中的采样次数，避免单次毛刺误报。 */
        const val REQUIRED_ORIENTATION_SAMPLES = 2
        private const val GRAVITY_MS2 = 9.80665
        /** 低于该模长的采样接近自由落体或读数无效，不足以判定方向。 */
        private const val MIN_MAGNITUDE_MS2 = 1.0
    }

    private var baselineX = 0.0
    private var baselineY = 0.0
    private var baselineZ = 0.0
    private var hasBaseline = false
    private var orientationStreak = 0

    /** 清空基线。下一次采样只用于重建基线，不会被算作一次姿态变化。 */
    fun reset() {
        hasBaseline = false
        orientationStreak = 0
    }

    fun observe(x: Float, y: Float, z: Float): AccelerationMotion? {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        if (magnitude < MIN_MAGNITUDE_MS2) return null
        val unitX = x / magnitude
        val unitY = y / magnitude
        val unitZ = z / magnitude
        if (!hasBaseline) {
            baselineX = unitX
            baselineY = unitY
            baselineZ = unitZ
            hasBaseline = true
            return null
        }
        if (abs(magnitude - GRAVITY_MS2) >= impactMs2) {
            orientationStreak = 0
            return AccelerationMotion.IMPACT
        }
        val dot = (baselineX * unitX + baselineY * unitY + baselineZ * unitZ).coerceIn(-1.0, 1.0)
        if (Math.toDegrees(acos(dot)) < orientationDegrees) {
            orientationStreak = 0
            return null
        }
        orientationStreak++
        if (orientationStreak < requiredSamples) return null
        orientationStreak = 0
        return AccelerationMotion.ORIENTATION
    }
}
