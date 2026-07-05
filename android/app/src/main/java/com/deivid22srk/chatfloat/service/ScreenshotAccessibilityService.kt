package com.deivid22srk.chatfloat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
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
 * AccessibilityService.takeScreenshot() (API 30+, Android 11+).
 *
 * No MediaProjection permission dialog needed. The user just needs to
 * enable the accessibility service once in Settings > Accessibility.
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
            return svc.doTakeScreenshot()
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doTakeScreenshot(): Boolean {
        return try {
            this.takeScreenshot(mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    Log.d(TAG, "Screenshot captured!")
                    scope.launch { processScreenshot(screenshotResult) }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception", e)
            false
        }
    }

    private suspend fun processScreenshot(result: ScreenshotResult) {
        try {
            val hardwareBitmap = result.hardwareBitmap
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()

            val outputStream = java.io.ByteArrayOutputStream()
            softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            softwareBitmap.recycle()
            val bytes = outputStream.toByteArray()

            Log.d(TAG, "Screenshot processed: ${bytes.size} bytes")
            GoBridge.sendMessage("📸 Screenshot (${bytes.size / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "processScreenshot error", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "ScreenshotAccessibilityService destroyed")
    }
}
