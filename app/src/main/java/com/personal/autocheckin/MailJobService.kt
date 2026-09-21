package com.personal.autocheckin

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MailJobService : JobService() {
    private class Sending {
        val cancelled = AtomicBoolean(false)
        @Volatile var socket: SSLSocket? = null
        fun cancel() {
            cancelled.set(true)
            try { socket?.close() } catch (_: Exception) { }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var sending: Sending? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val token = Sending()
        sending = token
        // No foreground service or custom WakeLock: JobScheduler holds CPU while the job runs.
        val deadline = Runnable { token.cancel() }
        main.postDelayed(deadline, 45_000L)
        Thread({
            var retry = false
            try {
                val settings = MailSettings(this)
                if (settings.enabled && !settings.blocked) {
                    for (mail in MailQueue.read(this).take(3)) {
                        if (token.cancelled.get() || !settings.enabled) break
                        if (MailPolicy.expired(mail.createdAt, mail.attempts, System.currentTimeMillis())) {
                            MailQueue.remove(this, mail.id)
                            report("MAIL_EXPIRED", "发送次数或 24 小时时限已到：${mail.subject}")
                            continue
                        }
                        val secret = try { settings.secret() } catch (_: Exception) {
                            settings.blocked = true
                            report("MAIL_BLOCKED", "无法读取授权码，请在邮件设置中重新填写并保存")
                            break
                        }
                        MailQueue.countAttempt(this, mail.id)
                        try {
                            send(mail, settings.sender, settings.recipient, secret, token)
                            MailQueue.remove(this, mail.id)
                            report("MAIL_SENT", "QQ SMTP 已接受：${mail.subject}（不等同于收件箱已送达）")
                        } catch (e: SmtpFailure) {
                            if (e.code in listOf(530, 534, 535, 538)) {
                                settings.blocked = true
                                report("MAIL_BLOCKED", "SMTP 认证失败（${e.code}），请检查邮箱 SMTP 开关和授权码，保存后重试")
                            } else if (e.code >= 500) {
                                MailQueue.remove(this, mail.id)
                                report("MAIL_REJECTED", "SMTP 拒收（${e.code}）：${mail.subject}")
                            } else report("MAIL_RETRY", "SMTP 暂不可用（${e.code}），等待系统重试")
                            break
                        } catch (_: SSLException) {
                            settings.blocked = true
                            report("MAIL_BLOCKED", "TLS 证书或加密连接校验失败，请检查网络和系统时间；未降级为明文")
                            break
                        } catch (_: Exception) {
                            report("MAIL_RETRY", "连接中断或超时，等待系统重试；服务器若已接收，重试可能产生重复邮件")
                            break
                        } finally { token.socket = null }
                    }
                    retry = settings.enabled && !settings.blocked && MailQueue.read(this).isNotEmpty()
                }
            } catch (_: Exception) {
                report("MAIL_ERROR", "邮件队列处理异常；请查看设置或清空待发队列")
                // Avoid an endless crash loop for a corrupt queue.
            } finally {
                main.post {
                    main.removeCallbacks(deadline)
                    if (sending === token) {
                        sending = null
                        // Recheck on the main thread so an event queued at job completion is not stranded.
                        val pending = try {
                            val settings = MailSettings(this)
                            settings.enabled && !settings.blocked && MailQueue.read(this).isNotEmpty()
                        } catch (_: Exception) { false }
                        jobFinished(params, retry || pending)
                    }
                }
            }
        }, "mail-send").start()
        return true
    }

    private fun send(mail: QueuedMail, sender: String, recipient: String, secret: String, token: Sending) {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
            token.socket = socket
            check(!token.cancelled.get())
            socket.soTimeout = 8_000
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.connect(InetSocketAddress("smtp.qq.com", 465), 8_000)
            socket.startHandshake()
            MailProtocol.exchange(socket.inputStream.bufferedReader(Charsets.US_ASCII),
                socket.outputStream.bufferedWriter(Charsets.US_ASCII), sender, recipient, secret,
                MailProtocol.message(sender, recipient, mail.id, mail.subject, mail.body))
        }
    }

    private fun report(event: String, detail: String) {
        MailSettings(this).lastStatus = "${ZonedDateTime.now()} $detail"
        RunLog.write(this, null, event, detail)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        sending?.cancel()
        sending = null
        return MailSettings(this).enabled && !MailSettings(this).blocked
    }

    override fun onDestroy() {
        sending?.cancel()
        sending = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
