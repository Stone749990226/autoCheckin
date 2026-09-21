package com.personal.autocheckin

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID

data class QueuedMail(val id: String, val subject: String, val body: String, val createdAt: Long, val attempts: Int = 0) {
    fun json() = JSONObject().put("id", id).put("subject", subject).put("body", body).put("createdAt", createdAt).put("attempts", attempts)
}

object MailQueue {
    const val JOB_ID = 210
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "mail_queue.json"))
    @Synchronized fun read(context: Context): List<QueuedMail> {
        if (!file(context).baseFile.exists()) return emptyList()
        val items = JSONArray(file(context).readFully().toString(Charsets.UTF_8))
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            QueuedMail(item.getString("id"), item.getString("subject"), item.getString("body"), item.getLong("createdAt"), item.getInt("attempts"))
        }
    }
    private fun write(context: Context, items: List<QueuedMail>) {
        val target = file(context)
        val output = target.startWrite()
        try {
            output.write(JSONArray().apply { items.forEach { put(it.json()) } }.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (e: Exception) { target.failWrite(output); throw e }
    }
    @Synchronized fun remove(context: Context, id: String) { write(context, read(context).filterNot { it.id == id }) }
    @Synchronized fun countAttempt(context: Context, id: String) { write(context, read(context).map { if (it.id == id) it.copy(attempts = it.attempts + 1) else it }) }
    @Synchronized fun clear(context: Context) { write(context, emptyList()) }

    @Synchronized fun enqueue(context: Context, subject: String, detail: String, eventId: String = UUID.randomUUID().toString()): Boolean {
        val settings = MailSettings(context)
        if (!settings.enabled || !settings.hasSecret) return false
        return try {
            val existing = read(context)
            if (existing.any { it.id == eventId }) return true
            if (existing.size >= 100) {
                RunLog.write(context, null, "MAIL_DROPPED", "待发邮件已达 100 封，请检查邮件设置")
                return false
            }
            val timestamp = ZonedDateTime.now().toString()
            val body = "自动打卡助手\n时间：$timestamp\n$detail\n\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                "提示：识别成功仅表示读取到成功提示；未确认不等于实际考勤失败。请以钉钉考勤记录为准。"
            write(context, existing + QueuedMail(eventId, "[自动打卡] $subject", body, System.currentTimeMillis()))
            settings.lastStatus = "$timestamp 已加入待发队列：$subject"
            RunLog.write(context, null, "MAIL_QUEUED", subject)
            schedule(context)
            true
        } catch (_: Exception) {
            RunLog.write(context, null, "MAIL_ERROR", "邮件入队失败；未记录凭据或邮件协议内容")
            false
        }
    }

    fun schedule(context: Context) {
        val settings = MailSettings(context)
        if (!settings.enabled || !settings.hasSecret || settings.blocked) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Leave an existing retry/backoff intact; this is event driven, never a periodic job.
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, MailJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        if (result == JobScheduler.RESULT_FAILURE) RunLog.write(context, null, "MAIL_ERROR", "系统未接受邮件发送任务，下次打开设置或产生通知时重试")
    }
}
