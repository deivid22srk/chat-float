package com.deivid22srk.chatfloat.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

    // Responsive max width: 72% of screen
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = (screenWidth * 0.72f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .animateContentSize(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        // Avatar (only for incoming)
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
                    Text(
                        text = message.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(3.dp))
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
}

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

    // Try base64 first (synchronous, fast)
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

    // Load from URL asynchronously on IO dispatcher
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
            contentDescription = null, // decorative — sender name is already visible
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
        return
    }

    // Fallback: show initials
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

/** Downloads and decodes a bitmap from a URL on the calling thread. */
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
