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
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Manages screen capture via the MediaProjection API.
 *
 * The screenshot is captured from the Activity context (not a foreground
 * service), because Android 14+ requires a system-level permission
 * (project_media) for FGS mediaProjection type, which third-party apps
 * cannot obtain.
 *
 * Flow:
 *   1. User taps 📸 in the floating overlay.
 *   2. FloatingChatService launches MainActivity with REQUEST_SCREEN_CAPTURE.
 *   3. MainActivity calls startScreenCapture() → system permission dialog.
 *   4. On approval, [onPermissionResult] captures one frame and uploads
 *      the PNG to Supabase Storage as an image message (via GoBridge).
 */
class ScreenshotManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
    }

    /**
     * Called by MainActivity when the user grants/denies screen capture permission.
     * If granted, captures one frame, uploads to Supabase, and sends a chat message.
     */
    fun onPermissionResult(data: Intent?) {
        val mpm = mediaProjectionManager ?: return
        if (data == null) return
        try {
            mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            doCapture { pngBytes ->
                if (pngBytes != null) {
                    // Upload to Supabase Storage as a "screenshot" image
                    // and send a message linking to it.
                    scope.launch {
                        runCatching {
                            // For now, just send a text marker message.
                            // TODO: upload PNG to Supabase Storage and send URL.
                            GoBridge.sendMessage("📸 Screenshot capturado")
                        }
                    }
                }
                // Stop the projection to free resources
                try { mediaProjection?.stop() } catch (_: Exception) {}
                mediaProjection = null
            }
        } catch (e: SecurityException) {
            // ignored
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
                }
            }, handler)

            handler.postDelayed({
                if (!captured) {
                    callback(null)
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader?.close() } catch (_: Exception) {}
                }
            }, 2000)
        } catch (e: Exception) {
            callback(null)
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
        }
    }

    private fun imageToPng(image: Image, width: Int, height: Int): ByteArray? {
        return try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 80, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
