package com.personal.autocheckin

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.time.ZonedDateTime

/** A bound hardware event listener, not a sampler. No application WakeLock is held. */
class MotionService : Service(), SensorEventListener {
    companion object {
        const val CHANNEL = "motion_guard"
        const val STOP = "com.personal.autocheckin.MOTION_STOP"
        const val RECONFIGURE = "com.personal.autocheckin.MOTION_RECONFIGURE"
        private const val NOTIFICATION_ID = 30
        var current: MotionService? = null
            private set

        fun significant(manager: SensorManager): Sensor? = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)
        // ColorOS exposes this sensor through enumeration; its numeric constant is not public SDK.
        fun tilt(manager: SensorManager): Sensor? = manager.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.isWakeUpSensor && it.stringType == "android.sensor.tilt_detector" }
        // Deliberately the non-wakeup accelerometer: a wakeup sensor would hold a CPU WakeLock for the whole arm window.
        fun accelerometer(manager: SensorManager): Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        /** 约 1 Hz，仅用于兜底判断大幅移动，不做连续采样。 */
        const val ACCELERATION_PERIOD_US = 1_000_000

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "防移动监测", NotificationManager.IMPORTANCE_LOW))
        }

        fun resumeIfEnabled(context: Context) {
            if (!MotionSettings(context).enabled || current != null) return
            try { context.startForegroundService(Intent(context, MotionService::class.java)) }
            catch (e: Exception) {
                MotionSettings(context).problem = "系统未允许恢复监测，请打开防移动设置：${e.javaClass.simpleName}"
                RunLog.write(context, null, "MOTION_ERROR", "无法恢复监测：${e.javaClass.simpleName}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: MotionSettings
    private lateinit var sensors: SensorManager
    private var significantRegistered = false
    private var tiltRegistered = false
    private var accelerationRegistered = false
    private val accelerationDetector = AccelerationMotionDetector()
    private var registeredAt = 0L
    private var started = false
    var sensorStatus = "尚未注册传感器"
        private set
    val isMonitoring: Boolean get() = significantRegistered || tiltRegistered
    private val transition = Runnable { refresh() }
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            significantRegistered = false // TYPE_SIGNIFICANT_MOTION is one-shot.
            movement("显著运动（可能被搬动或携带）", event.timestamp)
            if (settings.enabled && phase() == MotionPhase.ARMED) registerSensors()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = MotionSettings(this)
        sensors = getSystemService(SensorManager::class.java)
        current = this
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Meet the FGS deadline even if a delayed alarm races with the user's pause action.
        val notification = notification("正在检查防移动监测状态")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        started = true
        if (intent?.action == STOP) settings.disable()
        if (!settings.enabled) { stopGuard(); return START_NOT_STICKY }
        settings.normalizeBoot()
        if (intent?.action == RECONFIGURE) unregisterSensors()
        refresh()
        return START_STICKY
    }

    private fun phase(): MotionPhase = MotionPolicy.phase(settings.enabled, SystemClock.elapsedRealtime(), settings.armAt, settings.cooldownUntil)

    private fun refresh() {
        handler.removeCallbacks(transition)
        if (!settings.enabled) { stopGuard(); return }
        when (phase()) {
            MotionPhase.ARMING -> waitUntil(settings.armAt, "${settings.modeLabel}：准备布防，请放好手机；约 10 秒后开始监测")
            MotionPhase.COOLDOWN -> waitUntil(settings.cooldownUntil, "${settings.modeLabel}：已记录移动，${settings.cooldownMs / 1000} 秒限频期内暂停检测，到期自动恢复")
            MotionPhase.ARMED -> {
                MotionTimer.cancel(this)
                registerSensors()
            }
            MotionPhase.DISABLED -> stopGuard()
        }
    }

    private fun waitUntil(at: Long, message: String) {
        unregisterSensors()
        updateNotification(message)
        // Handler works while awake; a single alarm covers screen-off/idle, with no keep-awake loop.
        handler.postDelayed(transition, (at - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        if (!MotionTimer.schedule(this, at)) {
            fail("无法安排布防/恢复提醒，请检查精确闹钟权限")
        }
    }

    private fun registerSensors() {
        val wasRegistered = isMonitoring
        if (!wasRegistered) registeredAt = SystemClock.elapsedRealtime()
        try {
            if (!significantRegistered) significant(sensors)?.let { significantRegistered = sensors.requestTriggerSensor(trigger, it) }
            if (settings.sensitive && !tiltRegistered) tilt(sensors)?.let {
                tiltRegistered = sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
            }
            if (settings.acceleration && !accelerationRegistered) accelerometer(sensors)?.let { sensor ->
                // 基线必须来自本次布防后的第一次采样，所以每次（重新）注册都清空检测器状态。
                accelerationDetector.reset()
                accelerationRegistered = sensors.registerListener(this, sensor, ACCELERATION_PERIOD_US, 0, handler)
            }
        } catch (e: Exception) {
            fail("传感器注册失败：${e.javaClass.simpleName}")
            return
        }
        if (!isMonitoring) { fail("没有可注册的低功耗唤醒传感器"); return }
        settings.problem = ""
        sensorStatus = statusText()
        if (!wasRegistered) RunLog.write(this, null, "MOTION_ARMED", "${settings.modeLabel}：$sensorStatus")
        updateNotification("已布防 · $sensorStatus\n${hardwareText()}")
    }

    /** 本机实际存在的传感器名称；加速度计为可选兜底，与开关状态分开显示。 */
    private fun hardwareText(): String =
        "硬件 显著运动：${significant(sensors)?.name ?: "无"}；倾斜：${tilt(sensors)?.name ?: "无"}；" +
            "加速度计：${accelerometer(sensors)?.name ?: "无"}"

    /** 开关状态与本次实际注册状态。 */
    private fun statusText(): String {
        val tiltState = when {
            tiltRegistered -> "已注册"
            !settings.sensitive -> "稳健档已关闭"
            else -> "不可用"
        }
        val accelerationState = when {
            !settings.acceleration -> "开关关闭"
            accelerationRegistered -> "已注册"
            accelerometer(sensors) == null -> "本机不支持"
            else -> "未注册"
        }
        return "显著运动：${if (significantRegistered) "已注册" else "不可用"}；倾斜：$tiltState；" +
            "加速度计（兜底）：$accelerationState"
    }

    private fun unregisterSensors() {
        sensors.cancelTriggerSensor(trigger, null)
        sensors.unregisterListener(this)
        significantRegistered = false
        tiltRegistered = false
        accelerationRegistered = false
        accelerationDetector.reset()
        sensorStatus = "传感器已暂停"
    }

    private fun movement(source: String, timestampNanos: Long) {
        val now = SystemClock.elapsedRealtime()
        if (!MotionPolicy.acceptsEvent(settings.enabled, now, timestampNanos / 1_000_000L, registeredAt, settings.armAt, settings.cooldownUntil)) return
        val time = ZonedDateTime.now().toString()
        // Persist throttling before enqueueing; two sensors firing together must create just one alert.
        settings.recordMovement("$time\n${settings.modeLabel}：$source", now)
        unregisterSensors()
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryText = if (battery in 0..100) "$battery%" else "未知"
        RunLog.write(this, null, "MOTION_DETECTED", "${settings.modeLabel}：$source；电量 $batteryText")
        val mail = MailSettings(this)
        val subject = if (settings.testMode) "防移动自测：检测到移动" else "手机疑似被移动"
        val queued = MailQueue.enqueue(this, subject, "模式：${settings.modeLabel}\n检测时间：$time\n触发类型：$source\n剩余电量：$batteryText\n手机可能被拿起、翻转或随盒子搬动。此事件不能证明手机被盗。\n${settings.cooldownMs / 1000} 秒内不重复提醒，到期自动恢复检测。")
        if (!queued || mail.blocked) {
            settings.problem = if (!queued) "移动已记录，但邮件未入队，请检查邮件设置" else "移动邮件已排队，但发信暂停，请检查邮件授权码或 TLS 状态"
            RunLog.write(this, null, "MOTION_MAIL_ERROR", settings.problem)
        }
        refresh()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.stringType == "android.sensor.tilt_detector") {
            movement("倾斜/拿起（可能被翻转）", event.timestamp)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!settings.acceleration) return
            // 检测器只在开关开启时被注册，未注册的残留事件在布防/限频期同样会被 movement() 丢弃。
            val detected = accelerationDetector.observe(event.values[0], event.values[1], event.values[2]) ?: return
            val source = if (detected == AccelerationMotion.ORIENTATION) "加速度计姿态变化" else "加速度计明显加速"
            movement(source, event.timestamp)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun fail(message: String) {
        settings.disable()
        settings.problem = message
        RunLog.write(this, null, "MOTION_ERROR", message)
        MailQueue.enqueue(this, "防移动监测异常", message)
        stopGuard()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 30, Intent(this, MotionActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 31, Intent(this, MotionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("防移动监测 · ${settings.modeLabel}").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "暂停监测", pause).build()).build()
    }
    private fun updateNotification(message: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message)) }
    fun stopGuard() {
        handler.removeCallbacksAndMessages(null)
        unregisterSensors()
        MotionTimer.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterSensors()
        if (!settings.enabled) MotionTimer.cancel(this)
        if (current === this) current = null
        if (started && settings.enabled) RunLog.write(this, null, "MOTION_INTERRUPTED", "监测服务已销毁，等待系统恢复；恢复前存在监测空窗")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

object MotionTimer {
    private fun pending(context: Context) = PendingIntent.getBroadcast(context, 310,
        Intent(context, MotionTimerReceiver::class.java).setAction("com.personal.autocheckin.MOTION_TIMER"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun schedule(context: Context, at: Long): Boolean = try {
        context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        true
    } catch (_: SecurityException) { false }
    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
}

class MotionTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MotionSettings(context).enabled) {
            try { context.startForegroundService(Intent(context, MotionService::class.java)) }
            catch (e: Exception) {
                MotionSettings(context).problem = "监测恢复失败，请打开防移动设置"
                RunLog.write(context, null, "MOTION_ERROR", "定时恢复失败：${e.javaClass.simpleName}")
            }
        }
    }
}
