package com.personal.autocheckin

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.time.ZoneId
import java.util.Base64

class MailProtocolTest {
    @Test fun mimePreservesChineseBodyWithoutAllowingProtocolInjection() {
        val body = "下班卡未确认\n.\r\nRCPT TO:<bad@example.com>\n请核对考勤"
        val message = MailProtocol.message("test@qq.com", "target@example.com", "test-123", "下班卡\r\nBcc: bad@example.com", body)
        assertFalse(message.contains("\r\nBcc:"))
        assertFalse(message.contains("\r\n.\r\n"))
        assertEquals(body, Base64.getMimeDecoder().decode(message.substringAfter("\r\n\r\n").trim()).toString(Charsets.UTF_8))
        assertTrue(message.contains("Message-ID: <test-123@sync.local>"))
    }

    @Test fun addressesRejectNewlinesAndMultipleRecipients() {
        assertTrue(MailProtocol.validAddress("test@qq.com"))
        assertFalse(MailProtocol.validAddress("test@qq.com\r\nRCPT TO:<other@qq.com>"))
        assertFalse(MailProtocol.validAddress("a@qq.com,b@qq.com"))
        assertFalse(MailProtocol.validAddress(""))
    }

    @Test fun sendsOnlyAfterAuthenticationAndRecipientAccepted() {
        val replies = "220 Ready\r\n250-smtp\r\n250 AUTH LOGIN\r\n334 user\r\n334 pass\r\n235 ok\r\n250 sender\r\n250 recipient\r\n354 data\r\n250 queued\r\n"
        val output = StringWriter()
        MailProtocol.exchange(StringReader(replies).buffered(), output.buffered(), "test@qq.com", "target@qq.com", "dummy-credential", "body\r\n")
        val transcript = output.toString()
        assertTrue(transcript.startsWith("EHLO sync.local\r\nAUTH LOGIN\r\n"))
        assertTrue(transcript.contains("RCPT TO:<target@qq.com>\r\nDATA\r\nbody\r\n.\r\nQUIT\r\n"))
    }

    @Test fun authFailureDoesNotSendMailAndDoesNotExposeServerReply() {
        val replies = "220 ok\r\n250 ok\r\n334 user\r\n334 password\r\n535 account-sensitive-data\r\n"
        val output = StringWriter()
        try {
            MailProtocol.exchange(StringReader(replies).buffered(), output.buffered(), "test@qq.com", "target@qq.com", "dummy", "body\r\n")
            fail("Expected authentication failure")
        } catch (e: SmtpFailure) {
            assertEquals(535, e.code)
            assertEquals("SMTP 535", e.message)
        }
        assertFalse(output.toString().contains("MAIL FROM"))
    }

    @Test fun dataMustBeAcceptedBeforeMailIsConsideredSent() {
        try {
            MailProtocol.expect(StringReader("451 Try later\r\n").buffered(), 250)
            fail("Temporary failure must trigger retry")
        } catch (e: SmtpFailure) { assertEquals(451, e.code) }
    }

    @Test fun retriesAreBoundedByAttemptsAndAge() {
        assertFalse(MailPolicy.expired(100, 4, 101))
        assertTrue(MailPolicy.expired(100, 5, 101))
        assertTrue(MailPolicy.expired(100, 0, 101 + MailPolicy.MAX_AGE_MS))
    }

    @Test fun estimatesTenPercentFromInMemoryMailSamplesAndResetsAfterCharging() {
        val estimator = BatteryDischargeEstimator()
        val zone = ZoneId.of("Asia/Shanghai")
        val start = 1_800_000_000_000L

        assertTrue(estimator.observe(80, false, start, zone).text.contains("insufficient samples"))
        val estimate = estimator.observe(70, false, start + 2 * 60 * 60_000L, zone).text
        assertTrue(estimate.contains("Baseline:"))
        assertTrue(estimate.contains("Below 10%: about"))

        assertTrue(estimator.observe(75, false, start + 3 * 60 * 60_000L, zone).text.contains("battery rising"))
        assertTrue(estimator.observe(90, true, start + 4 * 60 * 60_000L, zone).text.contains("charging, baseline reset"))
        assertTrue(estimator.observe(89, false, start + 5 * 60 * 60_000L, zone).text.contains("charging ended, new baseline"))
    }

    @Test fun invalidOrShortBatterySamplesDoNotProduceAnEstimate() {
        val estimator = BatteryDischargeEstimator()
        val zone = ZoneId.of("Asia/Shanghai")
        assertTrue(estimator.observe(-1, false, 1000, zone).text.contains("no valid reading"))
        estimator.observe(60, false, 2000, zone)
        assertTrue(estimator.observe(59, false, 2000 + 5 * 60_000L, zone).text.contains("sample interval too short"))
    }

    @Test fun batteryEstimateWeightsRecentDischargeAndUsesConservativeLowBatteryFactor() {
        val estimator = BatteryDischargeEstimator()
        val zone = ZoneId.of("Asia/Shanghai")
        val start = 1_800_000_000_000L
        estimator.observe(80, false, start, zone)
        estimator.observe(70, false, start + 60 * 60_000L, zone)
        val normal = estimator.observe(60, false, start + 2 * 60 * 60_000L, zone).text
        assertTrue(normal.contains("recent 70% / baseline 30%"))
        estimator.observe(25, false, start + 3 * 60 * 60_000L, zone)
        val low = estimator.observe(20, false, start + 4 * 60 * 60_000L, zone).text
        assertTrue(low.contains("low-battery factor x1.35"))
    }

    @Test fun lowBatteryAlertsOncePerDischargeAndNeverWhileCharging() {
        assertTrue(MailPolicy.lowBatteryAlert(20, false, false))
        assertFalse(MailPolicy.lowBatteryAlert(19, false, true))
        assertFalse(MailPolicy.lowBatteryAlert(10, true, false))
        assertFalse(MailPolicy.lowBatteryAlert(21, false, false))
        assertFalse(MailPolicy.lowBatteryAlert(-1, false, false))
        assertFalse(MailPolicy.rearmBattery(24))
        assertTrue(MailPolicy.rearmBattery(25))
    }
}
