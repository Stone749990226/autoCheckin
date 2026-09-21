package com.personal.autocheckin

import org.junit.Assert.*
import org.junit.Test

class MotionPolicyTest {
    @Test fun ignoresMovementWhileDisabledAndDuringPlacement() {
        assertEquals(MotionPhase.DISABLED, MotionPolicy.phase(false, 100_000, 60_000, 0))
        assertEquals(MotionPhase.ARMING, MotionPolicy.phase(true, 59_999, 60_000, 0))
        assertFalse(MotionPolicy.acceptsEvent(false, 100_000, 100_000, 60_000, 60_000, 0))
        assertFalse(MotionPolicy.acceptsEvent(true, 59_999, 59_999, 0, 60_000, 0))
        assertTrue(MotionPolicy.acceptsEvent(true, 60_000, 60_000, 60_000, 60_000, 0))
    }

    @Test fun secondSensorAndContinuousMovementCannotSendAgainWithinNormalCooldown() {
        val detectedAt = 100_000L
        val cooldown = detectedAt + MotionPolicy.COOLDOWN_MS
        assertTrue(MotionPolicy.acceptsEvent(true, detectedAt, detectedAt, 60_000, 60_000, 0))
        assertFalse(MotionPolicy.acceptsEvent(true, detectedAt, detectedAt, 60_000, 60_000, cooldown))
        assertFalse(MotionPolicy.acceptsEvent(true, cooldown - 1, cooldown - 1, 60_000, 60_000, cooldown))
        assertEquals(MotionPhase.ARMED, MotionPolicy.phase(true, cooldown, 60_000, cooldown))
        assertTrue(MotionPolicy.acceptsEvent(true, cooldown, cooldown, cooldown, 60_000, cooldown))
    }

    @Test fun testCanRestartDuringAnyCooldownWhileNormalReenablePreservesLimit() {
        assertEquals(10_000L, MotionPolicy.ARM_DELAY_MS)
        assertEquals(60_000L, MotionPolicy.cooldownMs(false))
        assertEquals(15_000L, MotionPolicy.cooldownMs(true))
        assertEquals(0L, MotionPolicy.startCooldown(700_000, true, false, true))
        assertEquals(0L, MotionPolicy.startCooldown(700_000, true, true, true))
        assertEquals(0L, MotionPolicy.startCooldown(700_000, true, true, false))
        assertEquals(700_000L, MotionPolicy.startCooldown(700_000, true, false, false))
        assertEquals(0L, MotionPolicy.startCooldown(700_000, false, false, false))
    }

    @Test fun testCooldownEndsAfterFifteenSecondsAndRejectsOldEvent() {
        val end = 100_000 + MotionPolicy.cooldownMs(true)
        assertFalse(MotionPolicy.acceptsEvent(true, end - 1, end - 1, 90_000, 90_000, end))
        assertTrue(MotionPolicy.acceptsEvent(true, end, end, end, 90_000, end))
        assertFalse(MotionPolicy.acceptsEvent(true, end, 100_000, end, 90_000, end))
    }

    @Test fun staleBufferedEventsCannotAlertAfterArmingOrCooldown() {
        assertFalse(MotionPolicy.acceptsEvent(true, 80_000, 59_999, 60_000, 60_000, 0))
        assertFalse(MotionPolicy.acceptsEvent(true, 800_000, 699_999, 700_000, 60_000, 700_000))
        assertFalse(MotionPolicy.acceptsEvent(true, 80_000, 80_001, 60_000, 60_000, 0))
    }

    @Test fun delayedSystemTimerStillRequiresRegistrationAndCannotReplayOldEvents() {
        assertEquals(MotionPhase.ARMED, MotionPolicy.phase(true, 90_000, 60_000, 0))
        assertFalse(MotionPolicy.acceptsEvent(true, 90_010, 80_000, 90_000, 60_000, 0))
        assertTrue(MotionPolicy.acceptsEvent(true, 90_010, 90_001, 90_000, 60_000, 0))
    }

    @Test fun reenablePlacementDoesNotBypassExistingCooldown() {
        assertEquals(MotionPhase.ARMING, MotionPolicy.phase(true, 120_000, 180_000, 700_000))
        assertEquals(MotionPhase.COOLDOWN, MotionPolicy.phase(true, 180_000, 180_000, 700_000))
        assertEquals(MotionPhase.ARMED, MotionPolicy.phase(true, 700_000, 180_000, 700_000))
    }
}
