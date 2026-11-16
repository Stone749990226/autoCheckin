package com.personal.autocheckin

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CoreTest {
    private val shifts = listOf(PunchSchedule(PunchSlot.CLOCK_IN, true, 8, 30), PunchSchedule(PunchSlot.CLOCK_OUT, true, 18, 0))

    @Test fun morningAndEveningRemainIndependentAfterMorningExecution() {
        val before = ZonedDateTime.parse("2026-09-21T08:29:00+08:00[Asia/Shanghai]")
        val initial = ScheduleTime.nextOccurrences(before, shifts)
        val afterMorning = ScheduleTime.nextOccurrences(before.plusMinutes(3), shifts)
        assertEquals(initial.getValue(PunchSlot.CLOCK_IN).plusDays(1), afterMorning[PunchSlot.CLOCK_IN])
        assertEquals(initial[PunchSlot.CLOCK_OUT], afterMorning[PunchSlot.CLOCK_OUT])
        val afterEvening = ScheduleTime.nextOccurrences(before.withHour(18).withMinute(3), shifts)
        assertEquals(initial.getValue(PunchSlot.CLOCK_OUT).plusDays(1), afterEvening[PunchSlot.CLOCK_OUT])
    }

    @Test fun changingOrDisablingOneSlotDoesNotChangeTheOtherOccurrence() {
        val now = ZonedDateTime.parse("2026-09-21T09:00:00+08:00[Asia/Shanghai]")
        val original = ScheduleTime.nextOccurrences(now, shifts)
        val changed = ScheduleTime.nextOccurrences(now, listOf(shifts[0].copy(hour = 7), shifts[1]))
        assertEquals(original[PunchSlot.CLOCK_OUT], changed[PunchSlot.CLOCK_OUT])
        val disabled = ScheduleTime.nextOccurrences(now, listOf(shifts[0].copy(enabled = false), shifts[1]))
        assertFalse(disabled.containsKey(PunchSlot.CLOCK_IN))
        assertEquals(original[PunchSlot.CLOCK_OUT], disabled[PunchSlot.CLOCK_OUT])
    }

    @Test fun enforcesFifteenMinuteGapForFourRoundsIncludingAcrossMidnight() {
        assertFalse(ScheduleTime.hasConflict(shifts))
        assertTrue(ScheduleTime.hasConflict(listOf(shifts[0], shifts[1].copy(hour = 8, minute = 30))))
        assertTrue(ScheduleTime.hasConflict(listOf(shifts[0], shifts[1].copy(hour = 8, minute = 44))))
        assertFalse(ScheduleTime.hasConflict(listOf(shifts[0], shifts[1].copy(hour = 8, minute = 45))))
        assertTrue(ScheduleTime.hasConflict(listOf(shifts[0].copy(hour = 23, minute = 50), shifts[1].copy(hour = 0, minute = 3))))
        assertFalse(ScheduleTime.hasConflict(listOf(shifts[0].copy(hour = 23, minute = 50), shifts[1].copy(hour = 0, minute = 5))))
        assertFalse(ScheduleTime.hasConflict(listOf(shifts[0], shifts[1].copy(enabled = false, hour = 8, minute = 30))))
    }

    @Test fun retryPlanFitsInsideTheSlotGap() {
        assertEquals(4, RetryPlan.ROUNDS)
        assertEquals(2, RetryPlan.ROUNDS_PER_BATCH)
        assertTrue(RetryPlan.WINDOW_MS < RetryPlan.PERIOD_MS)
        val spanMs = RetryPlan.PERIOD_MS * (RetryPlan.ROUNDS - 1) + RetryPlan.WINDOW_MS
        assertEquals(690_000L, spanMs)
        assertTrue(spanMs < RetryPlan.SLOT_GAP_MINUTES * 60_000L)
    }

    @Test fun morningSuccessDoesNotCompleteOrConsumeEveningRounds() {
        val morning = AttemptTracker()
        morning.finish(RunOutcome.SUCCESS)
        val evening = AttemptTracker()
        assertEquals(RunOutcome.RUNNING, evening.outcome)
        assertEquals(1, evening.round)
        assertTrue(evening.roundFailed())
        assertTrue(evening.finish(RunOutcome.SUCCESS))
    }

    @Test fun rejectsConfirmationExplicitlyBelongingToTheOppositeSlot() {
        val keywords = listOf("打卡成功")
        assertNull(SuccessMatcher.match("上班打卡成功", keywords, PunchSlot.CLOCK_OUT))
        assertNull(SuccessMatcher.match("下班打卡成功", keywords, PunchSlot.CLOCK_IN))
        assertEquals("下班打卡成功", SuccessMatcher.match("下班打卡成功", keywords, PunchSlot.CLOCK_OUT))
        assertEquals("上班打卡成功", SuccessMatcher.match("上班打卡成功", keywords, PunchSlot.CLOCK_IN))
        assertEquals("打卡成功", SuccessMatcher.match("打卡成功", keywords, PunchSlot.CLOCK_OUT))
    }

    @Test fun schedulesTodayBeforeTargetAndTomorrowAtOrAfterTarget() {
        val before = ZonedDateTime.parse("2026-09-21T08:29:59+08:00[Asia/Shanghai]")
        val target = ScheduleTime.next(before, 8, 30)
        assertEquals("2026-09-21T08:30+08:00[Asia/Shanghai]", target.toString())
        assertEquals(target.plusDays(1), ScheduleTime.next(target, 8, 30))
        assertEquals(target.plusDays(1), ScheduleTime.next(target.plusMinutes(2), 8, 30))
    }

    @Test fun rollsOverMonthAndUsesLocalWallTimeAcrossTimezoneChanges() {
        val now = ZonedDateTime.parse("2026-12-31T23:59:00+08:00[Asia/Shanghai]")
        assertEquals("2027-01-01T08:30+08:00[Asia/Shanghai]", ScheduleTime.next(now, 8, 30).toString())
        val dst = ZonedDateTime.parse("2026-03-07T09:00:00-05:00[America/New_York]")
        assertEquals("2026-03-08T08:30-04:00[America/New_York]", ScheduleTime.next(dst, 8, 30).toString())
    }

    @Test fun fourFailedRoundsEndUnconfirmedAndNoFifthRoundRuns() {
        val run = AttemptTracker()
        assertEquals(1, run.round)
        assertTrue(run.roundFailed()); assertEquals(2, run.round)
        assertTrue(run.roundFailed()); assertEquals(3, run.round)
        assertTrue(run.roundFailed()); assertEquals(4, run.round)
        assertFalse(run.roundFailed())
        assertEquals(RunOutcome.UNCONFIRMED, run.outcome)
        assertFalse(run.roundFailed())
        assertFalse(run.finish(RunOutcome.SUCCESS))
        assertEquals(4, run.round)
    }

    @Test fun successOrCancellationMakesDuplicateCallbacksHarmless() {
        for (result in listOf(RunOutcome.SUCCESS, RunOutcome.CANCELLED, RunOutcome.ERROR)) {
            val run = AttemptTracker()
            assertTrue(run.finish(result))
            assertFalse(run.finish(RunOutcome.SUCCESS))
            assertFalse(run.roundFailed())
            assertEquals(result, run.outcome)
            assertEquals(1, run.round)
        }
    }

    @Test fun recognizesConfirmationButRejectsFailureAndHelpText() {
        val keywords = listOf("打卡成功")
        assertEquals("上班打卡成功", SuccessMatcher.match("上班打卡成功", keywords))
        assertEquals("打卡成功 08:30", SuccessMatcher.match("打卡成功 08:30", keywords))
        for (text in listOf("已打开钉钉", "打卡失败", "未打卡成功", "打卡成功率 90%", "打卡成功后自动关闭", "显示打卡成功提示")) {
            assertNull(text, SuccessMatcher.match(text, keywords))
        }
        assertEquals("极速打卡完成", SuccessMatcher.match("极速打卡完成", listOf("极速打卡完成")))
        assertNull(SuccessMatcher.match("任何文字", listOf("")))
    }

    @Test fun mailTextIsEnglishWhileLogsKeepTheirChineseReasons() {
        assertEquals("Helper", MailText.APP)
        assertEquals("Morning task", MailText.task(PunchSlot.CLOCK_IN))
        assertEquals("Evening task", MailText.task(PunchSlot.CLOCK_OUT))
        assertEquals("Morning task", MailText.task("上班卡"))
        assertEquals("completed", MailText.result(RunOutcome.SUCCESS))
        assertEquals("needs review", MailText.result(RunOutcome.UNCONFIRMED))
        assertEquals("Error", MailText.headline(RunOutcome.ERROR))
        assertEquals("Missing requirement: accessibility service, device admin",
            MailText.reason("缺少条件：无障碍服务未连接、设备管理员"))
        assertEquals("Wake-up failed: SecurityException", MailText.reason("后台唤醒失败：SecurityException"))
        assertEquals("DingTalk launch entry not found", MailText.reason("找不到钉钉启动入口"))
        assertEquals("See the in-app log for details", MailText.reason("某个未知原因"))
    }

    @Test fun coordinateClickRequiresMatchingScreenAndInBoundsPoint() {
        val click = ClickConfig(coordinateEnabled = true, x = 500, y = 900, width = 1080, height = 2400, rotation = 0)
        assertTrue(click.coordinatesValidFor(1080, 2400, 0))
        assertFalse(click.coordinatesValidFor(2400, 1080, 1))
        assertFalse(click.coordinatesValidFor(1080, 2400, 2))
        assertFalse(click.copy(x = 1080).coordinatesValidFor(1080, 2400, 0))
        assertFalse(click.copy(y = -1).coordinatesValidFor(1080, 2400, 0))
        assertFalse(click.copy(coordinateEnabled = false).coordinatesValidFor(1080, 2400, 0))
    }
}
