package com.personal.autocheckin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Intent
import android.graphics.Path
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CheckinAccessibilityService : AccessibilityService() {
    companion object {
        var connected: CheckinAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        connected = this
        setObservationEnabled(CheckinService.current?.needsObservation == true)
        RunLog.write(this, null, "ACCESSIBILITY", "无障碍服务已连接")
    }

    // Keep the system binding for background wake-up, but request no DingTalk events while idle.
    fun setObservationEnabled(enabled: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (enabled) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED else 0
        serviceInfo = info
    }

    fun wake(runId: String) {
        startActivity(Intent(this, WakeActivity::class.java).putExtra("runId", runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val run = CheckinService.current ?: return
        if (event.packageName?.toString() != Permissions.DINGTALK || !run.acceptsEvent(event.eventTime)) return
        try {
            event.text.forEach { run.observe(it.toString(), "事件文字") }
            event.contentDescription?.let { run.observe(it.toString(), "事件描述") }
            (event.parcelableData as? Notification)?.extras?.let { extras ->
                for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)) {
                    extras.getCharSequence(key)?.let { run.observe(it.toString(), "钉钉通知") }
                }
            }
            if (!run.acceptsEvent(event.eventTime)) return
            // A notification alone is not permission to inspect an unrelated foreground window.
            if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                visit(rootInActiveWindow) { node ->
                    if (node.isVisibleToUser && node.packageName?.toString() == Permissions.DINGTALK) {
                        node.text?.let { run.observe(it.toString(), "可见节点文字") }
                        node.contentDescription?.let { run.observe(it.toString(), "可见节点描述") }
                    }
                    !run.acceptsEvent(event.eventTime)
                }
            }
        } catch (e: Exception) {
            run.log("OBSERVE_ERROR", "读取节点失败，继续等待：${e.javaClass.simpleName}")
        }
    }

    fun performConfiguredClick(config: ClickConfig) {
        val run = CheckinService.current ?: return
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != Permissions.DINGTALK) {
            recycle(root)
            run.log("CLICK_SKIPPED", "钉钉不在前台，不执行任何点击")
            return
        }
        recycle(root)
        var clicked = false
        // Priority is intentional: visible text, contentDescription, full resource ID.
        val selectors: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> = listOf(
            "文字" to { node -> config.text.isNotBlank() && node.text?.toString()?.trim() == config.text },
            "描述" to { node -> config.description.isNotBlank() && node.contentDescription?.toString()?.trim() == config.description },
            "resource-id" to { node -> config.resourceId.isNotBlank() && node.viewIdResourceName == config.resourceId },
        )
        try {
            for ((label, matches) in selectors) {
                visit(rootInActiveWindow) { node ->
                    if (node.packageName?.toString() == Permissions.DINGTALK && node.isVisibleToUser && matches(node)) {
                        clicked = clickNodeOrParent(node)
                        if (clicked) run.log("CLICK_NODE", "已按${label}执行一次点击")
                    }
                    clicked
                }
                if (clicked) return
            }
            if (!config.coordinateEnabled) {
                run.log("CLICK_SKIPPED", "未找到可点击目标；坐标兜底未启用")
                return
            }
            // Recheck foreground immediately before a coordinate gesture.
            val currentRoot = rootInActiveWindow
            val foreground = currentRoot?.packageName?.toString() == Permissions.DINGTALK
            recycle(currentRoot)
            val manager = getSystemService(WindowManager::class.java)
            val bounds = WindowCompat.maximumBounds(manager)
            @Suppress("DEPRECATION")
            val rotation = manager.defaultDisplay.rotation
            if (!foreground || !config.coordinatesValidFor(bounds.width(), bounds.height(), rotation)) {
                run.log("CLICK_SKIPPED", "前台应用、屏幕尺寸或方向与坐标配置不符")
                return
            }
            val path = Path().apply { moveTo(config.x.toFloat(), config.y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { run.log("CLICK_COORDINATE", "已执行一次坐标点击") }
                override fun onCancelled(gestureDescription: GestureDescription) { run.log("CLICK_CANCELLED", "系统取消坐标手势") }
            }, null)
            if (!accepted) run.log("CLICK_ERROR", "系统未接受坐标手势")
        } catch (e: Exception) {
            run.log("CLICK_ERROR", "点击失败，继续等待：${e.javaClass.simpleName}")
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var levels = 0
        while (parent != null && levels++ < 6) {
            val current = parent
            try {
                if (current.packageName?.toString() != Permissions.DINGTALK) return false
                if (current.isVisibleToUser && current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                parent = current.parent
            } finally { recycle(current) }
        }
        recycle(parent)
        return false
    }

    /** Bounded traversal; each framework node is released even when the visitor stops early. */
    private fun visit(root: AccessibilityNodeInfo?, visitor: (AccessibilityNodeInfo) -> Boolean) {
        if (root == null) return
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var count = 0
        try {
            while (nodes.isNotEmpty() && count++ < 600) {
                val node = nodes.removeFirst()
                try {
                    if (visitor(node)) return
                    for (index in 0 until minOf(node.childCount, 100)) {
                        node.getChild(index)?.let(nodes::addLast)
                    }
                } finally { recycle(node) }
            }
        } finally { nodes.forEach(::recycle) }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) { node?.recycle() }

    override fun onInterrupt() { CheckinService.current?.fail("无障碍服务被系统中断") }

    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        CheckinService.current?.fail("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        CheckinService.current?.fail("无障碍服务已销毁")
        super.onDestroy()
    }
}
