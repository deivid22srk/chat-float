package com.deivid22srk.chatfloat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentUris
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures screenshots via GLOBAL_ACTION_TAKE_SCREENSHOT (API 30+).
 *
 * Flow:
 *   1. User taps 📸 in floating overlay
 *   2. Overlay is hidden (INVISIBLE)
 *   3. After 500ms, performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) is called
 *   4. System captures the screen and saves to gallery (MediaStore)
 *   5. After 2s, we query MediaStore for the latest screenshot file
 *   6. Read the file bytes, upload via GoBridge.sendMediaMessage as image
 *   7. Overlay is shown again
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
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

    private fun captureAndSend() {
        try {
            // Record timestamp before screenshot
            val beforeTime = System.currentTimeMillis()

            // Trigger system screenshot
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.d(TAG, "GLOBAL_ACTION_TAKE_SCREENSHOT result: $ok")

            if (!ok) {
                Log.e(TAG, "Failed to trigger screenshot")
                onScreenshotDone?.invoke()
                return
            }

            // Wait 2.5s for system to save the screenshot file, then read it
            mainHandler.postDelayed({
                scope.launch {
                    readAndUploadScreenshot(beforeTime)
                }
            }, 2500)
        } catch (e: Exception) {
            Log.e(TAG, "captureAndSend exception", e)
            onScreenshotDone?.invoke()
        }
    }

    private suspend fun readAndUploadScreenshot(beforeTime: Long) {
        try {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Query MediaStore for screenshots taken after beforeTime
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
                val selectionArgs = arrayOf((beforeTime / 1000).toString())
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

                if (cursor == null || !cursor.moveToFirst()) {
                    Log.e(TAG, "No screenshot found in MediaStore")
                    cursor?.close()
                    return@withContext null
                }

                val idIdx = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx)
                cursor.close()

                Log.d(TAG, "Found screenshot: $name (id=$id)")

                // Read the file content
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                }
            }

            if (bytes != null && bytes.isNotEmpty()) {
                Log.d(TAG, "Screenshot read: ${bytes.size} bytes (${bytes.size / 1024}KB)")
                GoBridge.sendMediaMessage(bytes, "image", "image/jpeg", "📸 Screenshot")
            } else {
                Log.e(TAG, "Failed to read screenshot bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "readAndUploadScreenshot error", e)
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
