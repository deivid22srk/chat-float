package com.deivid22srk.chatfloat.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Manages screen capture via the MediaProjection API.
 *
 * Flow:
 *   1. User taps the screenshot button in the floating overlay.
 *   2. If we don't have a MediaProjection yet, we ask the user for permission
 *      (system dialog). The result is delivered to the activity that registered
 *      the [ActivityResultLauncher].
 *   3. Once permission is granted, we capture a single frame and return the
 *      PNG bytes via [capture].
 *
 * Note: MediaProjection requires the host activity to be in the foreground
 * when requesting permission the FIRST time. After that, the same MediaProjection
 * can be reused without asking again (until the app is killed).
 */
class ScreenshotManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var pendingCallback: ((ByteArray?) -> Unit)? = null

    init {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
    }

    /**
     * Captures a single screenshot.
     *
     * If permission hasn't been granted yet, returns null and triggers the
     * permission request (the caller must handle the result via [onPermissionResult]).
     * Once permission is granted, call [capture] again.
     *
     * Returns PNG bytes on success, null on failure or if permission is needed.
     */
    fun capture(callback: (ByteArray?) -> Unit) {
        val mpm = mediaProjectionManager ?: run {
            callback(null)
            return
        }
        if (mediaProjection == null) {
            // Need permission first — store callback and trigger request
            pendingCallback = callback
            // We can't start an Activity from a Service directly for startActivityForResult.
            // The floating service will post a notification or launch the MainActivity
            // which handles the permission request.
            requestPermission()
            return
        }
        doCapture(callback)
    }

    private fun requestPermission() {
        // Launch MainActivity with an extra telling it to request screen capture.
        val intent = Intent(context, com.deivid22srk.chatfloat.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("REQUEST_SCREEN_CAPTURE", true)
        }
        context.startActivity(intent)
    }

    /**
     * Called by MainActivity when the user grants/denies screen capture permission.
     * [data] is the Intent returned by the system; null means denied.
     */
    fun onPermissionResult(data: Intent?) {
        val mpm = mediaProjectionManager ?: run {
            pendingCallback?.invoke(null)
            pendingCallback = null
            return
        }
        if (data == null) {
            pendingCallback?.invoke(null)
            pendingCallback = null
            return
        }
        try {
            mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            // Now perform the pending capture
            val cb = pendingCallback
            pendingCallback = null
            if (cb != null) {
                doCapture(cb)
            }
        } catch (e: SecurityException) {
            pendingCallback?.invoke(null)
            pendingCallback = null
        }
    }

    private fun doCapture(callback: (ByteArray?) -> Unit) {
        val mp = mediaProjection ?: run { callback(null); return }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        val handler = Handler(Looper.getMainLooper())

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mp.createVirtualDisplay(
                "ChatFloatScreenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )

            // Wait for the first frame
            var captured = false
            imageReader.setOnImageAvailableListener({ reader ->
                if (captured) return@setOnImageAvailableListener
                val image: Image? = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                if (image != null) {
                    captured = true
                    val bytes = imageToPng(image, width, height)
                    image.close()
                    callback(bytes)
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
                    try { imageReader?.close() } catch (_: Exception) {}
                    // Stop the projection to avoid keeping it alive
                    try { mp.stop() } catch (_: Exception) {}
                    mediaProjection = null
                }
            }, handler)

            // Timeout: if no frame in 2s, fail
            handler.postDelayed({
                if (!captured) {
                    callback(null)
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader?.close() } catch (_: Exception) {}
                    try { mp.stop() } catch (_: Exception) {}
                    mediaProjection = null
                }
            }, 2000)
        } catch (e: Exception) {
            callback(null)
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            mediaProjection = null
        }
    }

    /** Converts an Image (RGBA_8888) to PNG bytes. */
    private fun imageToPng(image: Image, width: Int, height: Int): ByteArray? {
        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            // rowStride may be > width * pixelStride due to padding
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            // Crop to actual width
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 80, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    fun hasPermission(): Boolean = mediaProjection != null
}
