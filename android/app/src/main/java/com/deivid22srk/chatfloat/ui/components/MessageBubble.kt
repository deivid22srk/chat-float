package com.deivid22srk.chatfloat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.ui.theme.BubbleIncoming
import com.deivid22srk.chatfloat.ui.theme.BubbleOutgoing

@Composable
fun MessageBubble(message: ChatMessage) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (isOutgoing) BubbleOutgoing else BubbleIncoming
    val textColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        if (!isOutgoing) {
            Text(
                text = message.senderName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
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
