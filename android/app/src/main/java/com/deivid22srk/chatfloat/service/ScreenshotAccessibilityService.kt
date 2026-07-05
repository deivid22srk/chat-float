package com.deivid22srk.chatfloat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.deivid22srk.chatfloat.R
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * AccessibilityService that captures screenshots and sends them as images.
 *
 * Flow:
 *   1. User taps 📸 in the floating overlay
 *   2. FloatingChatService hides the overlay (visibility = INVISIBLE)
 *   3. ScreenshotAccessibilityService.requestScreenshot() is called
 *   4. After 500ms (to let the overlay fully disappear), takeScreenshot()
 *      captures the screen
 *   5. The bitmap is converted to JPEG and uploaded via GoBridge.sendMediaMessage()
 *   6. FloatingChatService shows the overlay again
 */
class ScreenshotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenshotA11y"
        private var instance: ScreenshotAccessibilityService? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Set by FloatingChatService before calling requestScreenshot(). */
        var onScreenshotStart: (() -> Unit)? = null     // hide overlay
        var onScreenshotDone: (() -> Unit)? = null      // show overlay

        fun isEnabled(): Boolean = instance != null

        fun requestScreenshot(): Boolean {
            val svc = instance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            Log.d(TAG, "Requesting screenshot")

            // Step 1: hide the overlay
            onScreenshotStart?.invoke()

            // Step 2: wait 500ms for overlay to fully disappear, then capture
            mainHandler.postDelayed({
                svc.captureAndSend()
            }, 500)
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndSend() {
        try {
            this.takeScreenshot(mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    Log.d(TAG, "Screenshot captured!")
                    scope.launch {
                        processAndSend(screenshotResult)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: $errorCode")
                    onScreenshotDone?.invoke()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception", e)
            onScreenshotDone?.invoke()
        }
    }

    private suspend fun processAndSend(result: ScreenshotResult) {
        try {
            val hardwareBitmap = result.hardwareBitmap
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()

            // Compress to JPEG (much smaller than PNG for screenshots)
            val outputStream = ByteArrayOutputStream()
            softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            softwareBitmap.recycle()
            val jpegBytes = outputStream.toByteArray()

            Log.d(TAG, "Screenshot processed: ${jpegBytes.size} bytes")

            // Send as image message via Go
            GoBridge.sendMediaMessage(jpegBytes, "image", "image/jpeg", "📸 Screenshot")
        } catch (e: Exception) {
            Log.e(TAG, "processAndSend error", e)
        } finally {
            // Show the overlay again
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
