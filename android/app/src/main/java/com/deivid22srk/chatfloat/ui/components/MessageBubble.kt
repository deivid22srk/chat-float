package com.deivid22srk.chatfloat.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.ui.theme.BubbleIncoming
import com.deivid22srk.chatfloat.ui.theme.BubbleIncomingText
import com.deivid22srk.chatfloat.ui.theme.BubbleOutgoing
import com.deivid22srk.chatfloat.ui.theme.BubbleOutgoingText
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
    val bubbleColor = if (isOutgoing) BubbleOutgoing else BubbleIncoming
    val textColor = if (isOutgoing) BubbleOutgoingText else BubbleIncomingText
    val senderColor = if (isOutgoing) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary
    val timeColor = if (isOutgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
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

        // Bubble
        Column(horizontalAlignment = alignment) {
            if (!isOutgoing) {
                Text(
                    text = message.senderName,
                    fontSize = 12.sp,
                    color = senderColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
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
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = formatTime(message.timestamp),
                        fontSize = 10.sp,
                        color = timeColor,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

private fun formatTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
}

/**
 * Avatar composable. Tries to load from [url] first (Supabase Storage URL),
 * falls back to [base64] if [url] is null, then falls back to initials.
 */
@Composable
fun Avatar(url: String?, base64: String?, initials: String, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))

    var loadedBitmap by remember(url, base64) { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Try base64 first (synchronous, fast)
    if (loadedBitmap == null && base64 != null) {
        loadedBitmap = runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    // Load from URL asynchronously on IO dispatcher (network calls on main
    // thread throw NetworkOnMainThreadException)
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
            contentDescription = "Avatar",
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
            color = MaterialTheme.colorScheme.primary
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
