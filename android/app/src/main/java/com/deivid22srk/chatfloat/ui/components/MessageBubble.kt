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
import com.deivid22srk.chatfloat.ui.theme.BrandPrimary
import com.deivid22srk.chatfloat.ui.theme.BubbleIncoming
import com.deivid22srk.chatfloat.ui.theme.BubbleOutgoing
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun MessageBubble(message: ChatMessage) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (isOutgoing) BubbleOutgoing else BubbleIncoming
    // Use explicit colors so they don't depend on the system dark/light theme
    // (the dark theme's onSurface is light gray, which is invisible on the
    // light BubbleIncoming background).
    val textColor = if (isOutgoing) Color.White else Color(0xFF1F1F2E)
    val senderColor = if (isOutgoing) Color.White else BrandPrimary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                    fontSize = 12.sp,
                    color = senderColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .wrapContentWidth(alignment)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isOutgoing) 16.dp else 4.dp,
                            bottomEnd = if (isOutgoing) 4.dp else 16.dp
                        )
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * Avatar composable. Tries to load from [url] first (Supabase Storage URL),
 * falls back to [base64] if [url] is null, then falls back to initials.
 *
 * The URL is fetched on a background thread and decoded off the main thread.
 */
@Composable
fun Avatar(url: String?, base64: String?, initials: String, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))

    // State holding the loaded bitmap (from URL or base64)
    var loadedBitmap by remember(url, base64) { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Try base64 first (synchronous, fast)
    if (loadedBitmap == null && base64 != null) {
        loadedBitmap = runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    // Load from URL asynchronously
    LaunchedEffect(url) {
        if (loadedBitmap == null && url != null && url.isNotEmpty()) {
            loadedBitmap = runCatching {
                loadBitmapFromUrl(url)
            }.getOrNull()
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
