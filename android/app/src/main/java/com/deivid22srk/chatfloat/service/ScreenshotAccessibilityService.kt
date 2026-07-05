package com.deivid22srk.chatfloat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AccessibilityService that handles screenshot capture via
 * GLOBAL_ACTION_TAKE_SCREENSHOT (API 30+).
 *
 * This is the PREFERRED way to take screenshots on Android 11+ because:
 * - No MediaProjection permission dialog needed (unlike MediaProjection API)
 * - No system-signature permission required
 * - The user just needs to enable the accessibility service once in Settings
 *
 * Flow:
 *   1. User taps 📸 in the floating overlay
 *   2. FloatingChatService calls ScreenshotHelper.requestScreenshot()
 *   3. This service calls performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
 *   4. System captures the screen and calls onScreenshot() with the bitmap
 *   5. The bitmap is converted to PNG and sent as a chat message via GoBridge
 *
 * NOTE: The user must enable "ChatFloat" in Settings > Accessibility > Installed
 * apps for this to work. The first time the user taps 📸, we prompt them to
 * open the Accessibility settings.
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenshotA11y"
        private var instance: ScreenshotAccessibilityService? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** True if the accessibility service is running and ready. */
        fun isEnabled(): Boolean = instance != null

        /**
         * Requests a screenshot. Returns true if the request was sent,
         * false if the service isn't running (user needs to enable it).
         */
        fun requestScreenshot(): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            Log.d(TAG, "Requesting screenshot via GLOBAL_ACTION_TAKE_SCREENSHOT")
            return svc.takeScreenshot()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ScreenshotAccessibilityService connected")

        // Configure the service to be able to take screenshots
        configureService()
    }

    private fun configureService() {
        val info = AccessibilityServiceInfo().apply {
            // Minimal flags — we only need screenshot capability
            flags = AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR or
                AccessibilityServiceInfo.DEFAULT
            // No event types needed — we only use performGlobalAction
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        serviceInfo = info
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshot(): Boolean {
        return try {
            takeScreenshot(
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        Log.d(TAG, "Screenshot captured! Hardware bitmap: ${screenshotResult.hardwareBitmap}")
                        scope.launch {
                            processScreenshot(screenshotResult)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception", e)
            false
        }
    }

    private suspend fun processScreenshot(result: ScreenshotResult) {
        try {
            val hardwareBitmap = result.hardwareBitmap
            // Convert hardware bitmap to software bitmap (needed for PNG compression)
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()

            // Compress to PNG
            val outputStream = java.io.ByteArrayOutputStream()
            softwareBitmap.compress(Bitmap.CompressFormat.PNG, 70, outputStream)
            softwareBitmap.recycle()
            val pngBytes = outputStream.toByteArray()

            Log.d(TAG, "Screenshot processed: ${pngBytes.size} bytes")

            // Upload to Supabase Storage and send as a chat message
            // For now, send a text marker. The actual image upload would
            // use GoBridge.updateAvatarBytes or a new GoBridge.uploadImage function.
            // TODO: upload PNG to Supabase Storage and send the URL as a message.
            GoBridge.sendMessage("📸 Screenshot (${pngBytes.size / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "processScreenshot error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process accessibility events — only use this service
        // for its screenshot capability.
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ScreenshotAccessibilityService destroyed")
    }
}
