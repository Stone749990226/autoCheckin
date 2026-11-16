package com.personal.autocheckin

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.RadioGroup

class MotionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: MotionSettings
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_motion)
        WindowCompat.applyInsets(findViewById<android.view.View>(R.id.motion_root))
        settings = MotionSettings(this)
        MotionService.createChannel(this)
        findViewById<RadioGroup>(R.id.motion_sensitivity).apply {
            check(if (settings.sensitive) R.id.motion_sensitive else R.id.motion_robust)
            setOnCheckedChangeListener { _, checked ->
                val sensitive = checked == R.id.motion_sensitive
                val manager = getSystemService(SensorManager::class.java)
                if (!sensitive && MotionService.significant(manager) == null) {
                    check(R.id.motion_sensitive)
                    AlertDialog.Builder(this@MotionActivity).setMessage("本机无显著运动传感器，不能使用稳健档").setPositiveButton("知道了", null).show()
                } else {
                    settings.sensitive = sensitive
                    if (settings.enabled) {
                        try { startForegroundService(Intent(this@MotionActivity, MotionService::class.java).setAction(MotionService.RECONFIGURE)) }
                        catch (e: Exception) { settings.problem = "调整传感器失败：${e.javaClass.simpleName}" }
                    }
                    RunLog.write(this@MotionActivity, null, "MOTION_SENSITIVITY", if (sensitive) "灵敏：两种硬件事件任一触发" else "稳健：仅显著运动")
                    render()
                }
            }
        }
        findViewById<CheckBox>(R.id.motion_acceleration).apply {
            isChecked = settings.acceleration
            setOnCheckedChangeListener { button, checked ->
                if (checked && MotionService.accelerometer(getSystemService(SensorManager::class.java)) == null) {
                    button.isChecked = false
                    AlertDialog.Builder(this@MotionActivity).setMessage("本机没有可用的加速度计，不能开启低频加速度检测")
                        .setPositiveButton("知道了", null).show()
                    return@setOnCheckedChangeListener
                }
                settings.acceleration = checked
                if (settings.enabled) {
                    try { startForegroundService(Intent(this@MotionActivity, MotionService::class.java).setAction(MotionService.RECONFIGURE)) }
                    catch (e: Exception) { settings.problem = "切换加速度检测失败：${e.javaClass.simpleName}" }
                }
                RunLog.write(this@MotionActivity, null, "MOTION_ACCELERATION", if (checked) "低频加速度检测：已开启" else "低频加速度检测：已关闭")
                render()
            }
        }
        findViewById<Button>(R.id.motion_arm).setOnClickListener { startMode(false) }
        findViewById<Button>(R.id.motion_test).setOnClickListener { startMode(true) }
        findViewById<Button>(R.id.motion_pause).setOnClickListener {
            settings.disable()
            MotionTimer.cancel(this)
            stopService(Intent(this, MotionService::class.java))
            RunLog.write(this, null, "MOTION_PAUSED", "用户暂停防移动监测")
            render()
        }
        findViewById<Button>(R.id.motion_mail).setOnClickListener { startActivity(Intent(this, MailActivity::class.java)) }
    }

    private fun startMode(test: Boolean) {
            val missing = prerequisites()
            if (missing.isNotEmpty()) {
                AlertDialog.Builder(this).setTitle("请先完成设置").setMessage(missing.joinToString("\n"))
                    .setPositiveButton("知道了", null).show()
            } else {
                settings.enable(test)
                try {
                    startForegroundService(Intent(this, MotionService::class.java))
                    RunLog.write(this, null, "MOTION_ENABLED", "${settings.modeLabel}：10 秒放置倒计时；限频 ${settings.cooldownMs / 1000} 秒")
                } catch (e: Exception) {
                    settings.disable()
                    settings.problem = "启动失败：${e.javaClass.simpleName}"
                }
                render()
            }
    }

    private fun prerequisites(): List<String> = buildList {
        val manager = getSystemService(SensorManager::class.java)
        if (MotionService.significant(manager) == null && MotionService.tilt(manager) == null) add("没有低功耗唤醒运动传感器")
        if (!Permissions.exact(this@MotionActivity)) add("请在主界面授权精确闹钟（用于灭屏后布防和恢复）")
        if (!Permissions.notifications(this@MotionActivity)) add("请在主界面开启通知权限")
        if (getSystemService(NotificationManager::class.java).getNotificationChannel(MotionService.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) add("请开启系统中的“Background”通知渠道")
        if (!Permissions.battery(this@MotionActivity)) add("请在主界面开启电池优化豁免")
        val mail = MailSettings(this@MotionActivity)
        if (!mail.enabled || !mail.hasSecret || mail.blocked) add("请先配置并启用邮件通知，确保测试邮件能够收到")
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val service = MotionService.current
        val state = when {
            !settings.enabled -> "已暂停，不检测移动"
            service == null -> "服务尚未运行，正在请求恢复；此时没有监测保障"
            now < settings.armAt -> "放置倒计时：${(settings.armAt - now + 999) / 1000} 秒"
            now < settings.cooldownUntil -> "移动已记录，限频暂停中：${(settings.cooldownUntil - now + 999) / 1000} 秒后恢复"
            service.isMonitoring -> "已布防，等待拿起、翻转或明显移动"
            else -> "正在等待系统恢复传感器，请以“已布防”为准"
        }
        val manager = getSystemService(SensorManager::class.java)
        val hardware = "显著运动：${MotionService.significant(manager)?.name ?: "不支持"}\n倾斜：${MotionService.tilt(manager)?.name ?: "不支持"}\n加速度计：${MotionService.accelerometer(manager)?.name ?: "不支持"}"
        val accelerationSwitch = if (settings.acceleration) "低频加速度检测：已开启（可选兜底）" else "低频加速度检测：已关闭（默认，不注册加速度计）"
        val mail = MailSettings(this)
        val mailState = if (mail.enabled && mail.hasSecret && !mail.blocked) "邮件通知可用（发送状态见邮件设置）" else "邮件不可用或暂停，请检查邮件设置"
        val text = "模式：${settings.modeLabel}（限频 ${settings.cooldownMs / 1000} 秒）\n$state\n\n$accelerationSwitch\n$hardware\n${service?.sensorStatus ?: "未注册传感器"}\n$mailState\n${settings.problem}\n\n最近移动：\n${settings.lastEvent}"
        findViewById<TextView>(R.id.motion_status).let { if (it.text.toString() != text) it.text = text }
        findViewById<Button>(R.id.motion_arm).apply {
            isEnabled = !settings.enabled || settings.testMode
            val label = getString(if (settings.testMode) R.string.motion_normal else R.string.motion_arm)
            if (this.text.toString() != label) this.text = label
        }
        findViewById<Button>(R.id.motion_test).apply {
            val label = getString(if (settings.enabled && settings.testMode) R.string.motion_test_restart else R.string.motion_test)
            if (this.text.toString() != label) this.text = label
        }
        findViewById<Button>(R.id.motion_pause).isEnabled = settings.enabled
    }

    override fun onResume() {
        super.onResume()
        MotionService.resumeIfEnabled(this)
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}

class MotionRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MotionService.resumeIfEnabled(context)
        }
    }
}
