package com.deivid22srk.chatfloat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AccessibilityService that triggers screenshot capture via
 * GLOBAL_ACTION_TAKE_SCREENSHOT (API 30+, Android 11+).
 *
 * No MediaProjection permission dialog needed. The user just needs to
 * enable the accessibility service once in Settings > Accessibility.
 *
 * Note: GLOBAL_ACTION_TAKE_SCREENSHOT triggers the system's built-in
 * screenshot, which saves to the gallery. The app then sends a text
 * marker in the chat. For in-app bitmap capture, takeScreenshot()
 * with a callback could be used (API 31+), but the API is fragile
 * across OEMs. GLOBAL_ACTION_TAKE_SCREENSHOT is the most reliable.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenshotA11y"
        private var instance: ScreenshotAccessibilityService? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun isEnabled(): Boolean = instance != null

        fun requestScreenshot(): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            Log.d(TAG, "Requesting screenshot via GLOBAL_ACTION_TAKE_SCREENSHOT")
            val ok = svc.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (ok) {
                scope.launch {
                    GoBridge.sendMessage("📸 Screenshot tirado — salvo na galeria")
                }
            }
            return ok
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ScreenshotAccessibilityService connected")
        val info = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.DEFAULT
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ScreenshotAccessibilityService destroyed")
    }
}
