package com.deivid22srk.chatfloat.ui.components

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.deivid22srk.chatfloat.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(message: ChatMessage) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val senderColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary
    val timeColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = (screenWidth * 0.72f)

    // Full-screen image viewer state
    var fullScreenImage by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .animateContentSize(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        if (!isOutgoing) {
            Avatar(
                url = message.senderAvatar,
                base64 = null,
                initials = message.senderName.take(2).uppercase(),
                size = 32
            )
            Spacer(Modifier.size(8.dp))
        }

        Column(horizontalAlignment = alignment) {
            if (!isOutgoing) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = senderColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .wrapContentWidth(alignment)
                    .clip(
                        RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (isOutgoing) 18.dp else 4.dp,
                            bottomEnd = if (isOutgoing) 4.dp else 18.dp
                        )
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // === MEDIA CONTENT ===
                    when (message.mediaType) {
                        "image" -> {
                            ImageContent(
                                url = message.mediaUrl,
                                isOutgoing = isOutgoing,
                                onImageClick = { fullScreenImage = message.mediaUrl }
                            )
                        }
                        "audio" -> {
                            AudioContent(
                                url = message.mediaUrl,
                                isOutgoing = isOutgoing
                            )
                        }
                    }

                    // === TEXT (caption or regular message) ===
                    if (message.text.isNotEmpty() &&
                        message.text != "📸" &&
                        message.text != "📸 Screenshot" &&
                        message.text != "🎤 Áudio" &&
                        message.text != "📸 Screenshot tirado — salvo na galeria") {
                        Text(
                            text = message.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(3.dp))
                    } else if (message.mediaType.isEmpty()) {
                        // Regular text message with no media
                        Text(
                            text = message.text,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(3.dp))
                    }

                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }

    // Full-screen image viewer overlay
    if (fullScreenImage != null) {
        FullScreenImageViewer(
            url = fullScreenImage!!,
            onDismiss = { fullScreenImage = null }
        )
    }
}

// ============================================================
// Image content — shows image thumbnail, clickable for zoom
// ============================================================

@Composable
private fun ImageContent(url: String?, isOutgoing: Boolean, onImageClick: () -> Unit) {
    if (url == null || url.isEmpty()) return

    val tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onImageClick() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Imagem",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
    Spacer(Modifier.height(6.dp))
}

// ============================================================
// Audio content — play/pause button + duration
// ============================================================

@Composable
private fun AudioContent(url: String?, isOutgoing: Boolean) {
    if (url == null || url.isEmpty()) return

    val tintColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var durationText by remember { mutableStateOf("0:00") }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Clean up MediaPlayer when composable leaves
    DisposableEffect(url) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
            isPlaying = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Play/Pause button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tintColor.copy(alpha = 0.2f))
                .clickable {
                    if (isLoading) return@clickable
                    val mp = mediaPlayer
                    if (mp != null) {
                        if (isPlaying) {
                            mp.pause()
                            isPlaying = false
                        } else {
                            mp.start()
                            isPlaying = true
                        }
                    } else {
                        // Create and prepare MediaPlayer
                        isLoading = true
                        Thread {
                            try {
                                val newMp = MediaPlayer().apply {
                                    setDataSource(url)
                                    setOnPreparedListener { player ->
                                        isLoading = false
                                        val dur = player.duration
                                        durationText = formatDuration(dur)
                                        player.start()
                                        isPlaying = true
                                    }
                                    setOnCompletionListener {
                                        isPlaying = false
                                        it.seekTo(0)
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isLoading = false
                                        false
                                    }
                                    prepareAsync()
                                }
                                mediaPlayer = newMp
                            } catch (e: Exception) {
                                Log.e("AudioContent", "Failed to play audio", e)
                                isLoading = false
                            }
                        }.start()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = tintColor
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                    tint = tintColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.size(10.dp))

        // Mic icon + duration
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = durationText,
            color = textColor,
            style = MaterialTheme.typography.labelMedium
        )
    }
    Spacer(Modifier.height(4.dp))
}

private fun formatDuration(ms: Int): String {
    val seconds = ms / 1000
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

// ============================================================
// Full-screen image viewer with pinch-to-zoom
// ============================================================

@Composable
private fun FullScreenImageViewer(url: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Imagem ampliada",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        // Close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Fechar",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// ============================================================
// Helpers
// ============================================================

private val timeFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

private fun formatTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    return timeFormatter.get()?.format(Date(timestampMs)) ?: ""
}

/**
 * Avatar composable using Coil for URL loading (cached) with base64 fallback.
 */
@Composable
fun Avatar(url: String?, base64: String?, initials: String, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer)

    var loadedBitmap by remember(url, base64) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(base64) {
        if (loadedBitmap == null && base64 != null) {
            loadedBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Base64.decode(base64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(url) {
        if (loadedBitmap == null && url != null && url.isNotEmpty()) {
            loadedBitmap = withContext(Dispatchers.IO) {
                runCatching { loadBitmapFromUrl(url) }.getOrNull()
            }
        }
    }

    if (loadedBitmap != null) {
        Image(
            bitmap = loadedBitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
        return
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = (size / 3).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun loadBitmapFromUrl(urlString: String): android.graphics.Bitmap? {
    return runCatching {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.inputStream.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }.getOrNull()
}
