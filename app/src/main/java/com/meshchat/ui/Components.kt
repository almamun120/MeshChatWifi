package com.meshchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** A clock that ticks every second so "last seen" labels stay live. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000)
    }
}

fun agoText(now: Long, then: Long): String {
    if (then <= 0L) return "never"
    val s = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s sec ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

fun hopsText(hops: Int?): String = when (hops) {
    null -> "not in range"
    1 -> "1 hop"
    else -> "$hops hops"
}

fun timeText(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

@Composable
fun Avatar(name: String, size: Dp = 40.dp, tint: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun StatusDot(online: Boolean, size: Dp = 10.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline),
    )
}
