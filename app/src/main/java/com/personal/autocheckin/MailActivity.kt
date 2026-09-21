package com.personal.autocheckin

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobScheduler
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MailActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: MailSettings
    private val refresh = object : Runnable {
        override fun run() {
            val count = try { MailQueue.read(this@MailActivity).size.toString() } catch (_: Exception) { "无法读取" }
            val state = "待发：$count\n${settings.lastStatus}"
            val status = findViewById<TextView>(R.id.mail_status)
            if (status.text.toString() != state) status.text = state
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_mail)
        findViewById<android.view.View>(R.id.mail_root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        settings = MailSettings(this)
        findViewById<EditText>(R.id.mail_sender).setText(settings.sender)
        findViewById<EditText>(R.id.mail_recipient).setText(settings.recipient)
        findViewById<Switch>(R.id.mail_enabled).isChecked = settings.enabled
        findViewById<Switch>(R.id.mail_battery).isChecked = settings.lowBattery
        updateSecretHint()
        findViewById<Button>(R.id.mail_save).setOnClickListener { if (save()) message("邮件设置已保存") }
        findViewById<Button>(R.id.mail_test).setOnClickListener {
            if (save()) {
                if (!settings.enabled) message("请先开启邮件通知并保存")
                else if (MailQueue.enqueue(this, "邮件配置测试", "这是一封从手机发出的测试邮件，不代表执行了考勤任务。")) message("测试邮件已排队，请查看发送状态与收件箱")
            }
        }
        findViewById<Button>(R.id.mail_retry).setOnClickListener {
            if (save()) { MailQueue.schedule(this); message("已请求系统发送待发邮件") }
        }
        findViewById<Button>(R.id.mail_clear).setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空待发邮件？").setMessage("未发送的结果将不再通过邮件通知，本地打卡日志保留。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    getSystemService(JobScheduler::class.java).cancel(MailQueue.JOB_ID)
                    MailQueue.clear(this)
                }.show()
        }
    }

    private fun save(): Boolean {
        val sender = findViewById<EditText>(R.id.mail_sender).text.toString().trim()
        val recipient = findViewById<EditText>(R.id.mail_recipient).text.toString().trim()
        val secretField = findViewById<EditText>(R.id.mail_secret)
        val secret = secretField.text.toString().trim()
        val enabled = findViewById<Switch>(R.id.mail_enabled).isChecked
        if (!MailProtocol.validAddress(sender) || !sender.endsWith("@qq.com", true) || !MailProtocol.validAddress(recipient)) {
            message("请填写有效的 QQ 发信地址与收件地址")
            return false
        }
        if ((enabled && !settings.hasSecret && secret.isBlank()) || (sender != settings.sender && settings.hasSecret && secret.isBlank())) {
            message("请填写 SMTP 授权码；更换发信账号时也需重新填写")
            return false
        }
        return try {
            getSystemService(JobScheduler::class.java).cancel(MailQueue.JOB_ID)
            settings.save(sender, recipient, secret, enabled, findViewById<Switch>(R.id.mail_battery).isChecked)
            secretField.text.clear()
            updateSecretHint()
            if (enabled) {
                if (MailQueue.read(this).isNotEmpty()) MailQueue.schedule(this)
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { BatteryMail().onReceive(this, it) }
            }
            true
        } catch (_: Exception) { message("无法保存加密凭据或读取队列，请重新填写授权码后重试"); false }
    }

    private fun updateSecretHint() {
        findViewById<EditText>(R.id.mail_secret).setHint(if (settings.hasSecret) R.string.mail_secret_saved else R.string.mail_secret_hint)
    }
    private fun message(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show() }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
}
