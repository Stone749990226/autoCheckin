package com.personal.autocheckin

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import java.lang.ref.WeakReference

class WakeActivity : Activity() {
    companion object {
        private var active = WeakReference<WakeActivity>(null)
        fun finishRun(runId: String) {
            active.get()?.let { if (it.intent.getStringExtra("runId") == runId) it.finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "正在唤醒并打开钉钉…"
            textSize = 22f
            setPadding(32, 96, 32, 32)
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("runId") ?: return finish()
        val service = CheckinService.current ?: return finish()
        if (service.runId != id) return finish()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardSecure) {
            service.fail("检测到安全锁屏，无法自动解锁")
            finish()
        } else if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (!isFinishing) service.launchDingTalk(this@WakeActivity, id)
                }
                override fun onDismissError() {
                    service.fail("系统拒绝解除无密码锁屏")
                    finish()
                }
                override fun onDismissCancelled() {
                    service.fail("解除锁屏被取消")
                    finish()
                }
            })
        } else {
            service.launchDingTalk(this, id)
        }
    }

    override fun onDestroy() {
        if (active.get() === this) active.clear()
        super.onDestroy()
    }
}
