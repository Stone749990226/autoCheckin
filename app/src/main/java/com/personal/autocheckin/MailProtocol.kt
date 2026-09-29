package com.personal.autocheckin

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.math.roundToLong

class SmtpFailure(val code: Int) : IOException("SMTP $code")

object MailProtocol {
    fun validAddress(address: String): Boolean = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matches(address)
    private fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun message(sender: String, recipient: String, id: String, subject: String, body: String): String {
        require(validAddress(sender) && validAddress(recipient))
        require(Regex("[A-Za-z0-9-]{1,80}").matches(id))
        val subjectWords = subject.take(120).chunked(15).joinToString("\r\n ") { "=?UTF-8?B?${encoded(it)}?=" }
        val data = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
        return "From: <$sender>\r\nTo: <$recipient>\r\n" +
            "Date: ${ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)}\r\n" +
            "Message-ID: <$id@autocheckin.local>\r\nSubject: $subjectWords\r\n" +
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$data\r\n"
    }

    /** Replies are never included in errors: servers can echo account data. */
    fun expect(reader: BufferedReader, vararg accepted: Int) {
        var replyCode: Int? = null
        repeat(60) {
            val line = StringBuilder()
            while (true) {
                val character = reader.read()
                if (character == -1) throw IOException("SMTP connection closed")
                if (character == 10) break
                if (line.length >= 2048) throw IOException("SMTP reply too long")
                if (character != 13) line.append(character.toChar())
            }
            val code = line.take(3).toString().toIntOrNull() ?: throw IOException("Invalid SMTP reply")
            if (replyCode != null && replyCode != code) throw IOException("Invalid SMTP reply sequence")
            replyCode = code
            if (line.length < 4 || line[3] == ' ') {
                if (code !in accepted) throw SmtpFailure(code)
                return
            }
            if (line[3] != '-') throw IOException("Invalid SMTP reply")
        }
        throw IOException("Too many SMTP reply lines")
    }

    fun exchange(reader: BufferedReader, writer: BufferedWriter, sender: String, recipient: String, secret: String, message: String) {
        require(validAddress(sender) && validAddress(recipient))
        fun command(value: String, vararg expected: Int) {
            writer.write(value + "\r\n")
            writer.flush()
            expect(reader, *expected)
        }
        expect(reader, 220)
        command("EHLO autocheckin.local", 250)
        command("AUTH LOGIN", 334)
        command(encoded(sender), 334)
        command(encoded(secret), 235)
        command("MAIL FROM:<$sender>", 250)
        command("RCPT TO:<$recipient>", 250, 251)
        command("DATA", 354)
        // MIME body and subject are Base64; no user input can inject the SMTP terminator.
        writer.write(message + ".\r\n")
        writer.flush()
        expect(reader, 250)
        // A QUIT failure must not retry a message already accepted by the server.
        try { writer.write("QUIT\r\n"); writer.flush() } catch (_: IOException) { }
    }
}

data class BatterySample(val percent: Int, val atMillis: Long, val plugged: Boolean)

data class BatteryEstimate(val text: String)

/** In-memory only: a process restart intentionally drops the estimate history. */
class BatteryDischargeEstimator {
    private var baseline: BatterySample? = null
    private var lastSample: BatterySample? = null

    fun observe(percent: Int, plugged: Boolean, atMillis: Long, zoneId: ZoneId): BatteryEstimate {
        if (percent !in 0..100) return BatteryEstimate("电量：未知\n电量趋势：无法估算（系统未返回有效电量）")
        val current = BatterySample(percent, atMillis, plugged)
        val previous = lastSample
        val reason = when {
            plugged -> "正在充电，已重置放电基线"
            previous == null -> "样本不足"
            previous.plugged -> "充电结束，已建立新的放电基线"
            percent > previous.percent -> "电量上升，检测到充电并重置基线"
            atMillis <= previous.atMillis -> "时间样本异常，已重置放电基线"
            else -> null
        }
        if (reason != null) {
            baseline = current
            lastSample = current
            return BatteryEstimate("电量：$percent%\n电量趋势：无法估算（$reason）")
        }
        lastSample = current
        val start = baseline ?: current.also { baseline = it }
        val baselineText = Instant.ofEpochMilli(start.atMillis).atZone(zoneId).format(ESTIMATE_FORMAT)
        if (percent >= start.percent) {
            return BatteryEstimate("电量：$percent%\n放电基线：$baselineText ${start.percent}%\n预计低于 10%：无法估算（尚未观察到电量下降）")
        }
        val elapsed = atMillis - start.atMillis
        val dropped = start.percent - percent
        if (elapsed < MIN_SAMPLE_INTERVAL_MS) {
            return BatteryEstimate("电量：$percent%\n放电基线：$baselineText ${start.percent}%\n预计低于 10%：无法估算（放电样本间隔不足）")
        }
        val remainingPercent = (percent - 10).coerceAtLeast(0)
        val baselineRate = dropped.toDouble() / elapsed
        val recentElapsed = atMillis - (previous?.atMillis ?: start.atMillis)
        val recentDropped = (previous?.percent ?: start.percent) - percent
        val recentRate = if (recentElapsed >= MIN_SAMPLE_INTERVAL_MS && recentDropped > 0) {
            recentDropped.toDouble() / recentElapsed
        } else {
            baselineRate
        }
        // Recent consumption is more predictive, while retaining a baseline component
        // avoids one noisy mail sample dominating the forecast.
        val weightedRate = recentRate * RECENT_WEIGHT + baselineRate * BASELINE_WEIGHT
        val lowBatteryFactor = when {
            percent <= 10 -> 1.80
            percent <= 20 -> 1.35
            percent <= 30 -> 1.10
            else -> 1.0
        }
        val millisToTen = if (remainingPercent == 0) 0.0 else
            remainingPercent / (weightedRate * lowBatteryFactor)
        if (!millisToTen.isFinite() || millisToTen > MAX_ESTIMATE_MS) {
            return BatteryEstimate("电量：$percent%\n放电基线：$baselineText ${start.percent}%\n预计低于 10%：无法估算（结果超出合理范围）")
        }
        val estimatedAt = atMillis + millisToTen.roundToLong()
        val formatted = Instant.ofEpochMilli(estimatedAt).atZone(zoneId).format(ESTIMATE_FORMAT)
        val factorText = if (lowBatteryFactor == 1.0) "" else "；低电量保守修正 ×$lowBatteryFactor"
        return BatteryEstimate("电量：$percent%\n放电基线：$baselineText ${start.percent}%\n预计低于 10%：约 $formatted（近期样本权重 70%、全段基线权重 30%$factorText；仅供参考）")
    }

    companion object {
        private const val MIN_SAMPLE_INTERVAL_MS = 10 * 60_000L
        private const val RECENT_WEIGHT = 0.70
        private const val BASELINE_WEIGHT = 0.30
        private const val MAX_ESTIMATE_MS = 30L * 24 * 60 * 60 * 1000
        private val ESTIMATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}

object MailPolicy {
    const val MAX_ATTEMPTS = 5
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    fun expired(createdAt: Long, attempts: Int, now: Long): Boolean =
        attempts >= MAX_ATTEMPTS || now - createdAt > MAX_AGE_MS
    fun lowBatteryAlert(percent: Int, plugged: Boolean, alreadyReported: Boolean): Boolean =
        percent in 0..20 && !plugged && !alreadyReported
    fun rearmBattery(percent: Int): Boolean = percent >= 25
}
