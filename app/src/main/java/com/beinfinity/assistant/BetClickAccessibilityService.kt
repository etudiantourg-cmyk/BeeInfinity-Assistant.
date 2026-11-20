package com.beinfinity.assistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

class BetClickAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_CLICK_BET = "com.beinfinity.assistant.ACTION_CLICK_BET"
    }

    private var receiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CLICK_BET) clickLabeledBetButton()
            }
        }
        receiver = r
        val filter = IntentFilter(ACTION_CLICK_BET)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(r, filter)
        }
    }

    private fun clickLabeledBetButton() {
        val root = rootInActiveWindow ?: return
        val candidates = root.findAccessibilityNodeInfosByText("Bet")
        for (node in candidates) {
            val label = node.text?.toString()?.trim()?.lowercase() ?: ""
            if (label != "bet" && label != "place bet") continue
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target?.isEnabled == true && target.isClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        super.onDestroy()
    }
}