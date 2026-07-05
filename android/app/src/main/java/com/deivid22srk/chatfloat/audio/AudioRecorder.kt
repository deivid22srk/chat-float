package com.deivid22srk.chatfloat.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTime: Long = 0

    fun start(): Boolean {
        return try {
            val file = File(context.cacheDir, "audio_${System.currentTimeMillis()}.aac")
            outputFile = file

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(context) else
                @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startTime = SystemClock.elapsedRealtime()
            Log.d(TAG, "Recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            false
        }
    }

    fun stop(): ByteArray? {
        return try {
            val rec = recorder ?: return null
            val duration = SystemClock.elapsedRealtime() - startTime
            if (duration < 300) {
                cleanup()
                return null
            }
            rec.stop()
            rec.release()
            recorder = null
            val file = outputFile ?: return null
            val bytes = file.readBytes()
            Log.d(TAG, "Recording: ${bytes.size} bytes, ${duration}ms")
            file.delete()
            outputFile = null
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop", e)
            cleanup()
            null
        }
    }

    fun cancel() { cleanup() }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    companion object { private const val TAG = "AudioRecorder" }
}
