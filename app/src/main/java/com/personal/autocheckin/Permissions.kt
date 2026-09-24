package com.personal.autocheckin

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

object Permissions {
    const val DINGTALK = "com.alibaba.android.rimet"
    fun admin(context: Context) = ComponentName(context, CheckinAdminReceiver::class.java)
    fun exact(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notifications(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CheckinService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun battery(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun deviceAdmin(context: Context) = context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))
    fun installed(context: Context) = context.packageManager.getLaunchIntentForPackage(DINGTALK) != null
    fun missing(context: Context, requireExact: Boolean = true): List<String> = buildList {
        if (requireExact && !exact(context)) add("精确闹钟")
        if (!notifications(context)) add("通知（含运行通知渠道）")
        if (!battery(context)) add("电池优化豁免")
        if (CheckinAccessibilityService.connected == null) add("无障碍服务未连接")
        if (!deviceAdmin(context)) add("设备管理员")
        if (!installed(context)) add("未安装钉钉")
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardSecure) add("请关闭密码/图案等安全锁屏")
    }
}
