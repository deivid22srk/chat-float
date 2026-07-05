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
import java.io.ByteArrayOutputStream

/**
 * Captures screenshots via AccessibilityService.takeScreenshot() (API 31+).
 *
 * Flow:
 *   1. User taps 📸 in floating overlay
 *   2. Overlay is hidden (INVISIBLE)
 *   3. After 500ms, takeScreenshot() captures the screen bitmap
 *   4. Bitmap → JPEG → GoBridge.sendMediaMessage → uploaded to Supabase
 *   5. Overlay is shown again
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenshotA11y"
        private var instance: ScreenshotAccessibilityService? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val mainHandler = Handler(Looper.getMainLooper())

        var onScreenshotStart: (() -> Unit)? = null
        var onScreenshotDone: (() -> Unit)? = null

        fun isEnabled(): Boolean = instance != null

        fun requestScreenshot(): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Log.w(TAG, "takeScreenshot requires API 31+")
                return false
            }
            Log.d(TAG, "Requesting screenshot")
            onScreenshotStart?.invoke()
            mainHandler.postDelayed({ svc.captureAndSend() }, 500)
            return true
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun captureAndSend() {
        try {
            this.takeScreenshot(mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    Log.d(TAG, "Screenshot captured successfully")
                    val bitmap = screenshotResult.hardwareBitmap
                    scope.launch {
                        processAndSend(bitmap)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: errorCode=$errorCode")
                    onScreenshotDone?.invoke()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "captureAndSend exception", e)
            onScreenshotDone?.invoke()
        }
    }

    private suspend fun processAndSend(bitmap: Bitmap) {
        try {
            // Copy hardware bitmap to software (needed for JPEG compression)
            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()

            val outputStream = ByteArrayOutputStream()
            softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            softwareBitmap.recycle()
            val jpegBytes = outputStream.toByteArray()

            Log.d(TAG, "Screenshot processed: ${jpegBytes.size} bytes (${jpegBytes.size / 1024}KB)")

            // Upload as image message via Go
            GoBridge.sendMediaMessage(jpegBytes, "image", "image/jpeg", "📸 Screenshot")
        } catch (e: Exception) {
            Log.e(TAG, "processAndSend error", e)
        } finally {
            onScreenshotDone?.invoke()
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
