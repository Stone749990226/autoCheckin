package com.personal.autocheckin

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.UUID

class CheckinService : Service() {
    companion object {
        const val CHANNEL = "checkin_execution"
        const val ACTION_STOP = "com.personal.autocheckin.STOP"
        private const val NOTIFICATION_ID = 1
        // CPU-only leak guard for four rounds (11.5 minutes) plus launch handoff and cleanup.
        // The screen lock is renewed separately at the start of every round.
        private const val CPU_LOCK_TIMEOUT_MS = 800_000L
        // No wake/launch within this window counts as a failed round rather than a hard error.
        private const val WAKE_DEADLINE_MS = 12_000L
        // Give the server acknowledgement a moment before clearing DingTalk's background process.
        private const val DINGTALK_KILL_DELAY_MS = 3_000L
        var current: CheckinService? = null
            private set

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "General", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tracker = AttemptTracker()
    private var cpuLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var finalized = false
    private var wakeRequested = false
    private var started = false
    private var observing = false
    private var roundStartedAt = 0L
    private var launchedAt = 0L
    private lateinit var keywords: List<String>
    private lateinit var clickConfig: ClickConfig
    val runId: String = UUID.randomUUID().toString().take(8)
    var slot: PunchSlot = PunchSlot.CLOCK_IN
        private set
    val needsObservation: Boolean get() = started && !finalized

    override fun onCreate() {
        super.onCreate()
        current = this
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            terminate(RunOutcome.CANCELLED, "用户停止本次运行", lock = false)
            return START_NOT_STICKY
        }
        if (started) {
            log("DUPLICATE", "已有任务运行，忽略重复启动：${PunchSlot.fromKey(intent?.getStringExtra("slot"))?.label ?: "未知任务"}")
            return START_NOT_STICKY
        }
        started = true
        slot = PunchSlot.fromKey(intent?.getStringExtra("slot")) ?: PunchSlot.CLOCK_IN
        try {
            val notification = notification("Preparing…", ongoing = true)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            val settings = SettingsStore(this)
            settings.activeRunLabel = slot.label
            settings.activeRun = runId
            keywords = settings.keywords
            clickConfig = settings.click
            log("START", "来源=${intent?.getStringExtra("source") ?: "unknown"}；共 ${RetryPlan.ROUNDS} 轮，" +
                "每轮 ${RetryPlan.WINDOW_MS / 1000} 秒，每 ${RetryPlan.PERIOD_MS / 60_000} 分钟一轮，" +
                "第 ${RetryPlan.ROUNDS_PER_BATCH} 轮后清理并重启钉钉")
            val missing = Permissions.missing(this, requireExact = intent?.getStringExtra("source") == "alarm")
            if (missing.isNotEmpty()) {
                fail("缺少条件：${missing.joinToString("、")}")
                return START_NOT_STICKY
            }
            acquireLocks()
            beginRound()
        } catch (e: Exception) {
            fail("启动异常：${e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:cpu").apply {
            setReferenceCounted(false)
            acquire(CPU_LOCK_TIMEOUT_MS)
        }
        // WakeActivity remains the foreground-path fallback. On this Honor Android 10 device,
        // a bright screen lock is required for reliable battery-powered wake-up.
        screenLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$packageName:screen",
        ).apply { setReferenceCounted(false) }
        acquireScreenLockForRound()
    }

    private fun acquireScreenLockForRound() {
        screenLock?.let { lock ->
            if (lock.isHeld) lock.release()
            lock.acquire(RetryPlan.PERIOD_MS + 10_000L)
        }
    }

    private fun beginRound() {
        if (finalized) return
        if (tracker.round > 1) acquireScreenLockForRound()
        observing = false
        roundStartedAt = SystemClock.uptimeMillis()
        log("ATTEMPT", "第 ${tracker.round}/${RetryPlan.ROUNDS} 轮：请求唤醒并打开钉钉")
        updateNotification("Round ${tracker.round}/${RetryPlan.ROUNDS}")
        handler.postDelayed({ onRoundWindowElapsed() }, RetryPlan.WINDOW_MS)
        val accessibility = CheckinAccessibilityService.connected
        if (accessibility == null) {
            fail("无障碍服务未连接")
            return
        }
        try {
            accessibility.setObservationEnabled(true)
            wakeRequested = true
            accessibility.wake(runId)
            handler.postDelayed({
                // A stuck wake/launch is retried like a timeout instead of aborting the whole run.
                if (!finalized && !observing) failRound("唤醒或启动超时", "12 秒内未完成唤醒/解锁/启动，请检查系统后台弹出界面限制")
            }, WAKE_DEADLINE_MS)
        } catch (e: Exception) {
            fail("后台唤醒失败：${e.javaClass.simpleName}")
        }
    }

    private fun onRoundWindowElapsed() {
        if (finalized) return
        failRound("超时", "${RetryPlan.WINDOW_MS / 1000} 秒内未识别到成功")
    }

    private fun failRound(kind: String, detail: String) {
        if (finalized) return
        observing = false
        handler.removeCallbacksAndMessages(null)
        log("TIMEOUT", "第 ${tracker.round}/${RetryPlan.ROUNDS} 轮（$kind）：$detail")
        if (!tracker.roundFailed()) {
            terminate(RunOutcome.UNCONFIRMED, "${RetryPlan.ROUNDS} 轮等待均未识别成功；请人工核对钉钉考勤记录")
            return
        }
        updateNotification("Retrying…")
        val nextStart = roundStartedAt + RetryPlan.PERIOD_MS
        if (tracker.round == RetryPlan.ROUNDS_PER_BATCH + 1) {
            // Batch boundary: DingTalk is sent home first so it can be cleared, then relaunched.
            goHome()
            handler.postDelayed({ restartDingTalk() }, DINGTALK_KILL_DELAY_MS)
        } else {
            handler.postDelayed({ beginRound() }, (nextStart - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        }
    }

    private fun restartDingTalk() {
        if (finalized) return
        clearDingTalkBackground("第 ${RetryPlan.ROUNDS_PER_BATCH} 轮未确认，清理钉钉后台进程后重新启动")
        updateNotification("Round ${tracker.round}/${RetryPlan.ROUNDS}")
        val nextStart = roundStartedAt + RetryPlan.PERIOD_MS
        handler.postDelayed({ beginRound() }, (nextStart - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun goHome() {
        try {
            CheckinAccessibilityService.connected?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
            // Best effort; the batch cleanup below still runs.
        }
    }

    fun launchDingTalk(activity: WakeActivity, id: String) {
        if (finalized || id != runId || observing) {
            activity.finish()
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(Permissions.DINGTALK)
                ?: return fail("找不到钉钉启动入口").also { activity.finish() }
            launchedAt = SystemClock.uptimeMillis()
            observing = true
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log("LAUNCHED", "钉钉启动请求已发出；开始观察本次无障碍事件")
            handler.postDelayed({
                if (!finalized && observing && clickConfig.enabled) {
                    CheckinAccessibilityService.connected?.performConfiguredClick(clickConfig)
                        ?: fail("执行点击时无障碍服务已断开")
                }
            }, 8_000L)
        } catch (e: Exception) {
            fail("启动钉钉失败：${e.javaClass.simpleName}")
        } finally {
            activity.finish()
        }
    }

    fun acceptsEvent(eventTime: Long): Boolean = !finalized && observing &&
        eventTime >= launchedAt && eventTime >= roundStartedAt

    fun observe(text: String, source: String) {
        if (finalized || !observing) return
        SuccessMatcher.match(text, keywords, slot)?.let {
            terminate(RunOutcome.SUCCESS, "识别到打卡成功提示（$source）：${it.take(120)}")
        }
    }

    fun fail(message: String) = terminate(RunOutcome.ERROR, message)

    fun cancel() = terminate(RunOutcome.CANCELLED, "用户停止本次运行", lock = false)

    private fun writeRecord(event: String, detail: String): Boolean =
        RunLog.write(this, runId, event, "${slot.label}：$detail")

    fun log(event: String, detail: String) {
        if (!writeRecord(event, detail)) {
            updateNotification("Log write failed, check free space")
        }
    }

    private fun terminate(outcome: RunOutcome, detail: String, lock: Boolean = true) {
        if (finalized) return
        finalized = true
        observing = false
        tracker.finish(outcome)
        handler.removeCallbacksAndMessages(null)
        CheckinAccessibilityService.connected?.setObservationEnabled(false)
        val logged = writeRecord(outcome.name, detail)
        val success = outcome == RunOutcome.SUCCESS
        // Both a success and a fully failed run leave DingTalk open; clear it to save standby power.
        val cleanupDingTalk = success || outcome == RunOutcome.UNCONFIRMED
        WakeActivity.finishRun(runId)
        releaseLocks()
        // killBackgroundProcesses ignores a foreground app, so send DingTalk home first.
        if (cleanupDingTalk) goHome()
        var lockText = "not locked"
        if (lock && wakeRequested) {
            try {
                if (Permissions.deviceAdmin(this)) {
                    getSystemService(DevicePolicyManager::class.java).lockNow()
                    lockText = "screen locked"
                    writeRecord("LOCKED", "已调用系统锁屏")
                } else {
                    lockText = "lock failed: device admin unavailable"
                    writeRecord("LOCK_ERROR", "设备管理员权限不可用，无法锁屏")
                }
            } catch (e: Exception) {
                lockText = "lock failed: ${e.javaClass.simpleName}"
                writeRecord("LOCK_ERROR", e.javaClass.simpleName)
            }
        }
        val mailBody = buildString {
            appendLine("Run: $runId")
            appendLine("Task: ${MailText.task(slot)}")
            appendLine("Result: ${MailText.result(outcome)}")
            appendLine("Rounds: ${tracker.round} of ${RetryPlan.ROUNDS}")
            appendLine("Lock: $lockText")
            if (outcome == RunOutcome.ERROR) appendLine("Reason: ${MailText.reason(detail)}")
            append("DingTalk background process: " +
                if (cleanupDingTalk) "cleanup requested, see DINGTALK_KILL in the local log"
                else "left running")
        }
        MailQueue.enqueue(this, "${MailText.task(slot)}: ${MailText.result(outcome)}", mailBody, "result-$runId")
        SettingsStore(this).activeRun = null
        try {
            getSystemService(NotificationManager::class.java).notify(2,
                notification(if (logged) MailText.headline(outcome) else "${MailText.headline(outcome)} (log write failed)", ongoing = false))
        } catch (_: SecurityException) { /* Permission may have been revoked mid-run. */ }
        if (cleanupDingTalk) {
            // Keep the service alive briefly so the delayed cleanup survives process teardown.
            handler.postDelayed({
                clearDingTalkBackground(if (success) "打卡成功后清理" else "全部轮次未确认后清理")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, DINGTALK_KILL_DELAY_MS)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Best effort only: without root a normal app can clear another package's background
     * processes but cannot force-stop it. DingTalk foreground services may survive.
     */
    private fun clearDingTalkBackground(reason: String) {
        try {
            getSystemService(ActivityManager::class.java).killBackgroundProcesses(Permissions.DINGTALK)
            writeRecord("DINGTALK_KILL", "$reason；已请求清理钉钉后台进程，系统可能保留其前台服务或推送进程")
        } catch (e: Exception) {
            writeRecord("DINGTALK_KILL_ERROR", "$reason；清理钉钉后台进程失败：${e.javaClass.simpleName}")
        }
    }

    private fun notification(message: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(MailText.APP).setContentText(message).setContentIntent(open)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
            // Hidden on the lock screen: only "one notification" is shown there.
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1, Intent(this, CheckinService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return builder.build()
    }

    private fun updateNotification(message: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, true))
        } catch (_: SecurityException) { /* The service will record revoked permissions. */ }
    }

    private fun releaseLocks() {
        screenLock?.let { if (it.isHeld) it.release() }
        cpuLock?.let { if (it.isHeld) it.release() }
        screenLock = null
        cpuLock = null
    }

    override fun onDestroy() {
        if (!finalized && started) {
            writeRecord("INTERRUPTED", "运行服务被销毁，结果未确认")
            MailQueue.enqueue(this, "${MailText.task(slot)}: interrupted",
                "Run: $runId\nTask: ${MailText.task(slot)}\nResult: interrupted\n" +
                    "Note: the background service was destroyed before a result was recorded.", "interrupted-$runId")
            SettingsStore(this).activeRun = null
            releaseLocks()
            if (wakeRequested && Permissions.deviceAdmin(this)) {
                try { getSystemService(DevicePolicyManager::class.java).lockNow() }
                catch (_: Exception) { writeRecord("LOCK_ERROR", "服务销毁时锁屏失败") }
            }
        }
        finalized = true
        observing = false
        handler.removeCallbacksAndMessages(null)
        CheckinAccessibilityService.connected?.setObservationEnabled(false)
        releaseLocks()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
