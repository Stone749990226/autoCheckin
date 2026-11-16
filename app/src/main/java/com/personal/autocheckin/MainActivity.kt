package com.personal.autocheckin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private var updating = false
    private var lastRenderState = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    // Personal sideloaded automation requires Doze exemption; no Play distribution is planned.
    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        setContentView(R.layout.activity_main)
        WindowCompat.applyInsets(findViewById<View>(R.id.root))
        findViewById<Switch>(R.id.enabled).setOnCheckedChangeListener { _, enabled ->
            if (!updating) {
                if (enabled) {
                    val missing = Permissions.missing(this)
                    if (settings.schedules.none { it.enabled }) {
                        showMessage("暂时无法启用", "请至少开启上班卡或下班卡。")
                    } else if (ScheduleTime.hasConflict(settings.schedules)) {
                        showMessage("时间冲突", "上下班任务需至少间隔 ${RetryPlan.SLOT_GAP_MINUTES} 分钟，请修改时间。")
                    } else if (missing.isNotEmpty()) {
                        showMessage("暂时无法启用", "请先完成：\n${missing.joinToString("\n")}")
                    } else {
                        settings.enabled = true
                        if (!AlarmScheduler.schedule(this)) {
                            settings.enabled = false
                            AlarmScheduler.cancel(this)
                            showMessage("启用失败", "无法安排精确闹钟，请检查权限后重试。")
                        } else RunLog.write(this, null, "ENABLED", "每日任务已启用")
                    }
                } else {
                    settings.enabled = false
                    AlarmScheduler.cancel(this)
                    CheckinService.current?.cancel()
                    RunLog.write(this, null, "DISABLED", "每日任务已停用")
                }
                render()
            }
        }
        for (slot in PunchSlot.entries) {
            findViewById<Switch>(switchId(slot)).setOnCheckedChangeListener { _, enabled ->
                if (!updating) saveSchedule(settings.schedule(slot).copy(enabled = enabled))
            }
            button(timeId(slot)) {
                val saved = settings.schedule(slot)
                TimePickerDialog(this, { _, hour, minute ->
                    saveSchedule(saved.copy(hour = hour, minute = minute))
                }, saved.hour, saved.minute, true).apply { setTitle("设置${slot.label}时间"); show() }
            }
        }
        button(R.id.test_launch) {
            AlertDialog.Builder(this).setTitle("测试启动钉钉")
                .setMessage("打开钉钉可能触发极速打卡。此测试仅记录启动结果，不识别打卡结果，也不自动锁屏。")
                .setNegativeButton("取消", null).setPositiveButton("打开钉钉") { _, _ -> testLaunch() }.show()
        }
        button(R.id.test_full) {
            AlertDialog.Builder(this).setTitle("选择测试时段")
                .setItems(PunchSlot.entries.map { it.label }.toTypedArray()) { _, which -> testFull(PunchSlot.entries[which]) }
                .setNegativeButton("取消", null).show()
        }
        button(R.id.stop_run) { CheckinService.current?.cancel(); render() }
        button(R.id.logs) { showLogs() }
        button(R.id.mail_settings) { startActivity(Intent(this, MailActivity::class.java)) }
        button(R.id.motion_settings) { startActivity(Intent(this, MotionActivity::class.java)) }
        button(R.id.advanced) { showAdvanced() }
        button(R.id.exact) {
            if (Build.VERSION.SDK_INT >= 31) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
            } else toast("当前系统无需单独授权精确闹钟")
        }
        button(R.id.notification) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !getPreferences(MODE_PRIVATE).getBoolean("notificationRequested", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationRequested", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        button(R.id.battery) {
            openSettings(if (Permissions.battery(this)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
        }
        button(R.id.vendor_power) {
            AlertDialog.Builder(this).setTitle("厂商后台 / 启动管理")
                .setMessage("Android 的“电池优化豁免”只检测标准 Doze 豁免，不能检测华为/荣耀等厂商私有的启动管理。\n\n荣耀/华为：设置 → 电池 → 应用启动管理 → 找到 Helper → 关闭“自动管理”，开启“允许自启动”“允许关联启动”“允许后台活动”。\n\n建议同时在最近任务中锁定本应用。小米、OPPO 等设备的菜单名称可能不同，请在电池/自启动/后台活动中放行本应用。\n\n设置完成后请使用“测试完整流程”：点击开始，5 秒内手动灭屏，观察是否自动亮屏并打开钉钉。")
                .setPositiveButton("打开应用详情") { _, _ ->
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
                }.setNegativeButton("知道了", null).show()
        }
        button(R.id.accessibility) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button(R.id.admin) {
            if (Permissions.deviceAdmin(this)) openSettings(Intent(Settings.ACTION_SECURITY_SETTINGS))
            else openSettings(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Permissions.admin(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "仅用于自动任务结束后锁屏；不申请擦除数据或密码管理能力。"))
        }
        button(R.id.app_settings) { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())) }
    }

    private fun saveSchedule(config: PunchSchedule) {
        val proposed = settings.schedules.map { if (it.slot == config.slot) config else it }
        if (ScheduleTime.hasConflict(proposed)) {
            showMessage("时间冲突", "上下班任务需至少间隔 ${RetryPlan.SLOT_GAP_MINUTES} 分钟，避免上一任务尚未结束。")
            render()
            return
        }
        settings.saveSchedule(config)
        if (!config.enabled && CheckinService.current?.slot == config.slot) CheckinService.current?.cancel()
        val scheduled = AlarmScheduler.schedule(this)
        if (settings.enabled && proposed.any { it.enabled } && !scheduled) {
            showMessage("尚未安排闹钟", "时间已保存，请检查精确闹钟权限和时段配置。")
        }
        RunLog.write(this, null, "SCHEDULE_CONFIG", "${config.slot.label}：${timeLabel(config.slot)}，${if (config.enabled) "启用" else "停用"}")
        render()
    }

    private fun testFull(slot: PunchSlot) {
        val missing = Permissions.missing(this, requireExact = true)
        if (missing.isNotEmpty()) showMessage("请先完成设置", missing.joinToString("\n"))
        else AlertDialog.Builder(this).setTitle("测试${slot.label}")
            .setMessage("点击开始后等待 5 秒，请在倒计时内手动灭屏。5 秒后系统闹钟会触发完整流程：自动亮屏、打开钉钉，最多 ${RetryPlan.ROUNDS} 轮" +
                "（每轮 ${RetryPlan.WINDOW_MS / 1000} 秒、每 ${RetryPlan.PERIOD_MS / 60_000} 分钟一轮，第 ${RetryPlan.ROUNDS_PER_BATCH} 轮后清理并重启钉钉），结束后自动锁屏。" +
                "可能触发真实打卡；时段标签不改变钉钉自身的考勤规则。")
            .setNegativeButton("取消", null).setPositiveButton("开始 5 秒倒计时") { _, _ ->
                if (AlarmScheduler.scheduleFullTest(this, slot)) {
                    toast("5 秒后开始，请现在手动灭屏")
                } else {
                    showMessage("无法安排测试", "请检查精确闹钟权限后重试。")
                }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        MotionService.resumeIfEnabled(this)
        if (settings.enabled) AlarmScheduler.schedule(this)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // This personal application intentionally has a Chinese-only interface.
    @SuppressLint("SetTextI18n")
    private fun render() {
        updating = true
        findViewById<Switch>(R.id.enabled).isChecked = settings.enabled
        PunchSlot.entries.forEach { findViewById<Switch>(switchId(it)).isChecked = settings.schedule(it).enabled }
        updating = false
        val next = PunchSlot.entries.joinToString("\n") { slot ->
            val at = settings.scheduledAt(slot)
            val nextTime = if (!settings.schedule(slot).enabled) "已停用"
                else if (settings.enabled && at > 0 && Permissions.exact(this)) {
                    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                } else "尚未安排"
            "${slot.label}下次：$nextTime"
        }
        val run = CheckinService.current
        val running = run != null
        val missing = Permissions.missing(this)
        val renderState = "${settings.enabled}|${settings.schedules}|$next|${run?.runId}|${missing.joinToString()}"
        if (renderState == lastRenderState) return
        lastRenderState = renderState
        PunchSlot.entries.forEach { slot ->
            findViewById<Button>(timeId(slot)).apply {
                text = timeLabel(slot)
                contentDescription = "设置${slot.label}时间：${timeLabel(slot)}"
            }
        }
        val state = when {
            run != null -> "正在执行${run.slot.label}"
            !settings.enabled -> "总开关已停用"
            settings.schedules.none { it.enabled } -> "未开启任何时段"
            missing.isNotEmpty() -> "已启用，但运行条件不齐"
            else -> "已启用，等待闹钟"
        }
        findViewById<TextView>(R.id.status).text = "$next\n状态：$state"
        findViewById<TextView>(R.id.permissions).text = listOf(
            "精确闹钟" to Permissions.exact(this), "通知" to Permissions.notifications(this),
            "电池优化豁免" to Permissions.battery(this), "无障碍已连接" to (CheckinAccessibilityService.connected != null),
            "设备管理员" to Permissions.deviceAdmin(this), "已安装钉钉" to Permissions.installed(this),
        ).joinToString("\n") { (label, granted) -> "${if (granted) "✓" else "○"} $label" }
        findViewById<Button>(R.id.stop_run).isEnabled = running
        findViewById<Button>(R.id.test_full).isEnabled = !running
        findViewById<Button>(R.id.test_launch).isEnabled = !running
        findViewById<Button>(R.id.advanced).isEnabled = !running
    }

    private fun testLaunch() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(Permissions.DINGTALK)
            if (intent == null) showMessage("无法启动", "请先安装并登录钉钉。")
            else {
                startActivity(intent)
                RunLog.write(this, null, "TEST_LAUNCH", "钉钉启动请求已发出，不代表打卡成功")
            }
        } catch (e: Exception) {
            RunLog.write(this, null, "TEST_ERROR", e.javaClass.simpleName)
            showMessage("无法启动", "启动失败：${e.javaClass.simpleName}")
        }
    }

    private fun showLogs() {
        val text = TextView(this).apply {
            textSize = 13f
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
            text = RunLog.recent(this@MainActivity)
        }
        AlertDialog.Builder(this).setTitle("最近日志 · 最多 500 条")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("关闭", null).show()
    }

    private fun showAdvanced() {
        val saved = settings.click
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
        fun label(text: String) { layout.addView(TextView(this).apply { this.text = text; setPadding(0, 16, 0, 0) }) }
        fun input(title: String, value: String, number: Boolean = false, multiline: Boolean = false): EditText {
            label(title)
            return EditText(this).apply {
                setText(value)
                inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or
                    (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                isSingleLine = !multiline
                layout.addView(this)
            }
        }
        val keywords = input("成功关键词（每行一个；仅填写实际成功提示）", settings.keywords.joinToString("\n"), multiline = true)
        label("每次启动 8 秒后最多点击一次。按文字、描述、resource-id 的顺序精确匹配，均不可点击才使用坐标。请先在手机上确认目标；配置不会自动推测考勤路径。")
        val enabled = CheckBox(this).apply { text = "启用自动点击"; isChecked = saved.enabled; layout.addView(this) }
        val text = input("目标文字", saved.text)
        val description = input("目标 contentDescription", saved.description)
        val resourceId = input("完整 resource-id（例如 包名:id/控件名）", saved.resourceId)
        val coordinate = CheckBox(this).apply { this.text = "允许坐标兜底"; isChecked = saved.coordinateEnabled; layout.addView(this) }
        val bounds = WindowCompat.maximumBounds(windowManager)
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        label("坐标单位为屏幕像素，原点在左上角（包含状态栏）。保存后绑定当前屏幕 ${bounds.width()} × ${bounds.height()}，旋转值 $rotation。可用开发者选项“指针位置”读取坐标。")
        val x = input("X", saved.x.toString(), number = true)
        val y = input("Y", saved.y.toString(), number = true)
        val dialog = AlertDialog.Builder(this).setTitle("高级配置")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val words = keywords.text.lines().map(String::trim).filter(String::isNotEmpty).distinct()
                val px = x.text.toString().toIntOrNull()
                val py = y.text.toString().toIntOrNull()
                if (words.isEmpty() || words.any { it.length > 80 }) {
                    toast("至少填写一个成功关键词，每个不超过 80 字")
                    return@setOnClickListener
                }
                if (coordinate.isChecked && (px == null || py == null || px !in 0 until bounds.width() || py !in 0 until bounds.height())) {
                    toast("坐标必须在当前屏幕范围内")
                    return@setOnClickListener
                }
                if (enabled.isChecked && !coordinate.isChecked && listOf(text, description, resourceId).all { it.text.isBlank() }) {
                    toast("启用点击时需填写目标节点或启用坐标")
                    return@setOnClickListener
                }
                settings.saveAdvanced(words, ClickConfig(enabled.isChecked, text.text.toString().trim(),
                    description.text.toString().trim(), resourceId.text.toString().trim(), coordinate.isChecked,
                    px ?: 0, py ?: 0, bounds.width(), bounds.height(), rotation))
                RunLog.write(this, null, "CONFIG", "识别与点击配置已更新；自动点击=${enabled.isChecked}；坐标兜底=${coordinate.isChecked}")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())) }
            catch (_: Exception) { showMessage("请手动设置", "当前系统没有提供此设置入口，请从系统设置打开应用管理。") }
        }
    }

    private fun timeLabel(slot: PunchSlot): String = settings.schedule(slot).let { String.format(Locale.CHINA, "%02d:%02d", it.hour, it.minute) }
    private fun switchId(slot: PunchSlot): Int = if (slot == PunchSlot.CLOCK_IN) R.id.clock_in_enabled else R.id.clock_out_enabled
    private fun timeId(slot: PunchSlot): Int = if (slot == PunchSlot.CLOCK_IN) R.id.clock_in_time else R.id.clock_out_time
    private fun button(id: Int, action: () -> Unit) { findViewById<Button>(id).setOnClickListener { action() } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show()
    }
}
