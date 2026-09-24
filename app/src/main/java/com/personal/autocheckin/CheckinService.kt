package com.personal.autocheckin

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
        var current: CheckinService? = null
            private set

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "打卡任务", NotificationManager.IMPORTANCE_LOW),
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
    private var attemptStartedAt = 0L
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
            val notification = notification("正在准备自动打卡", ongoing = true)
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
            log("START", "来源=${intent?.getStringExtra("source") ?: "unknown"}；最多两次，每次 60 秒")
            val missing = Permissions.missing(this, requireExact = intent?.getStringExtra("source") == "alarm")
            if (missing.isNotEmpty()) {
                fail("缺少条件：${missing.joinToString("、")}")
                return START_NOT_STICKY
            }
            acquireLocks()
            beginAttempt()
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
            acquire(150_000L)
        }
        // ACQUIRE_CAUSES_WAKEUP turns the display on at acquire time; Activity#setTurnScreenOn is
        // ignored by some ROMs (MIUI/HyperOS) when starting from the background, so this is the
        // reliable wake path. SCREEN_DIM keeps it dim while observing; ON_AFTER_RELEASE avoids an
        // immediate off if the admin lock below is unavailable.
        screenLock = power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "$packageName:screen",
        ).apply {
            setReferenceCounted(false)
            acquire(150_000L)
        }
    }

    private fun beginAttempt() {
        if (finalized) return
        observing = false
        attemptStartedAt = SystemClock.uptimeMillis()
        log("ATTEMPT", "第 ${tracker.attempt} 次：请求唤醒并打开钉钉")
        updateNotification("第 ${tracker.attempt}/2 次，等待打卡结果")
        handler.postDelayed({
            if (!finalized) {
                observing = false
                handler.removeCallbacksAndMessages(null)
                log("TIMEOUT", "第 ${tracker.attempt} 次 60 秒内未识别到成功")
                if (tracker.timeout()) beginAttempt()
                else terminate(RunOutcome.UNCONFIRMED, "两次等待均未识别成功；请人工核对钉钉考勤记录")
            }
        }, 60_000L)
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
                if (!finalized && !observing) fail("12 秒内未完成唤醒/解锁/启动，请检查系统后台弹出界面限制")
            }, 12_000L)
        } catch (e: Exception) {
            fail("后台唤醒失败：${e.javaClass.simpleName}")
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
        eventTime >= launchedAt && eventTime >= attemptStartedAt

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
            updateNotification("本地日志写入失败，请检查可用空间")
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
        WakeActivity.finishRun(runId)
        releaseLocks()
        var lockResult = "本次未主动锁屏"
        if (lock && wakeRequested) {
            try {
                if (Permissions.deviceAdmin(this)) {
                    getSystemService(DevicePolicyManager::class.java).lockNow()
                    lockResult = "已调用系统锁屏"
                    writeRecord("LOCKED", "已调用系统锁屏")
                } else {
                    lockResult = "锁屏失败：设备管理员权限不可用"
                    writeRecord("LOCK_ERROR", "设备管理员权限不可用，无法锁屏")
                }
            } catch (e: Exception) {
                lockResult = "锁屏失败：${e.javaClass.simpleName}"
                writeRecord("LOCK_ERROR", e.javaClass.simpleName)
            }
        }
        val summary = when (outcome) {
            RunOutcome.SUCCESS -> "已识别到打卡成功"
            RunOutcome.UNCONFIRMED -> "打卡结果未确认，请人工核对"
            RunOutcome.ERROR -> "任务异常，请查看日志"
            RunOutcome.CANCELLED -> "本次任务已停止"
            else -> "任务已结束"
        }
        MailQueue.enqueue(this, "${slot.label} · $summary", "运行 ID：$runId\n任务：${slot.label}\n结果：$summary\n详情：$detail\n锁屏：$lockResult", "result-$runId")
        SettingsStore(this).activeRun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            getSystemService(NotificationManager::class.java).notify(2,
                notification(if (logged) summary else "$summary；日志写入失败", ongoing = false))
        } catch (_: SecurityException) { /* Permission may have been revoked mid-run. */ }
        stopSelf()
    }

    private fun notification(message: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("自动打卡助手 · ${slot.label}").setContentText(message).setContentIntent(open)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 1, Intent(this, CheckinService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "停止（不锁屏）", stop).build())
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
            MailQueue.enqueue(this, "${slot.label}运行中断", "运行 ID：$runId\n运行服务被销毁，结果未确认，请核对考勤。", "interrupted-$runId")
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
