package com.personal.autocheckin

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

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

object MailPolicy {
    const val MAX_ATTEMPTS = 5
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    fun expired(createdAt: Long, attempts: Int, now: Long): Boolean =
        attempts >= MAX_ATTEMPTS || now - createdAt > MAX_AGE_MS
    fun lowBatteryAlert(percent: Int, plugged: Boolean, alreadyReported: Boolean): Boolean =
        percent in 0..20 && !plugged && !alreadyReported
    fun rearmBattery(percent: Int): Boolean = percent >= 25
}
