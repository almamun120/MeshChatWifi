package com.meshchat.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshchat.core.Content
import com.meshchat.core.MediaLimits
import com.meshchat.data.MessageEntity
import com.meshchat.media.PlayerState

// ---- icons drawn on a Canvas (the core icon set has neither pause nor microphone)

@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bw = w * 0.2f
            drawRoundRect(tint, Offset(w * 0.24f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw / 3))
            drawRoundRect(tint, Offset(w * 0.56f, h * 0.16f), Size(bw, h * 0.68f), CornerRadius(bw / 3))
        } else {
            val p = Path().apply {
                moveTo(w * 0.30f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.86f)
                close()
            }
            drawPath(p, tint)
        }
    }
}

@Composable
fun MicIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(tint, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.50f), CornerRadius(w * 0.14f))
        drawArc(tint, 0f, 180f, false, topLeft = Offset(w * 0.22f, h * 0.26f), size = Size(w * 0.56f, h * 0.46f), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.92f), sw, StrokeCap.Round)
    }
}

// ---- waveform

/** Bars are the real recording levels sent with the message. Tap or drag to seek. */
@Composable
fun Waveform(
    bars: ByteArray,
    progress: Float,
    played: Color,
    rest: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remember(bars) { if (bars.isEmpty()) ByteArray(MediaLimits.WAVE_BARS) { (60 + (it * 53) % 140).toByte() } else bars }
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { off -> seek((off.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    seek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val n = data.size
        val gap = 2.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        for (i in 0 until n) {
            val a = (data[i].toInt() and 0xFF) / 255f
            val bh = maxOf(3.dp.toPx(), a * size.height)
            val done = (i + 0.5f) / n <= progress
            drawRoundRect(
                color = if (done) played else rest,
                topLeft = Offset(i * (bw + gap), (size.height - bh) / 2f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2f),
            )
        }
    }
}

private fun speedLabel(s: Float) = if (s == 1f) "1x" else if (s == 2f) "2x" else "1.5x"

/** WhatsApp-like voice bubble body: round play/pause button, waveform with progress, time, speed chip. */
@Composable
fun VoiceBubbleContent(
    m: MessageEntity,
    st: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: () -> Unit,
) {
    val active = st.id == m.msgId
    val playing = active && st.playing
    val progress = if (active && st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (active && st.positionMs > 0) st.positionMs else m.durationMs
    val accent = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(accent).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) { PlayPauseIcon(playing, MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(170.dp)) {
            Waveform(m.waveform, progress, accent, rest, onSeek, Modifier.fillMaxWidth().height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Content.formatDuration(shownMs), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                if (active) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.15f))
                            .clickable(onClick = onSpeed).padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { Text(speedLabel(st.speed), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

/** Replaces the text field while the mic is held: blinking red dot, timer, live level, slide-to-cancel hint. */
@Composable
fun RecordingBar(elapsedMs: Long, level: Float, cancelling: Boolean, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    val red = Color(0xFFD32F2F)
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(red.copy(alpha = alpha)))
        Spacer(Modifier.width(8.dp))
        Text(Content.formatDuration(elapsedMs.toInt()), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0.04f, 1f)).background(red))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (cancelling) "Release to cancel" else "‹ Slide to cancel",
            style = MaterialTheme.typography.labelMedium,
            color = if (cancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hold to record, release to send, slide left to cancel.
 * [onStart] must return true only if recording really started (false e.g. while the permission dialog is shown).
 */
@Composable
fun MicHoldButton(
    recording: Boolean,
    cancelThreshold: Dp,
    onStart: () -> Boolean,
    onSlide: (dx: Float, cancelling: Boolean) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
) {
    val start by rememberUpdatedState(onStart)
    val slide by rememberUpdatedState(onSlide)
    val end by rememberUpdatedState(onEnd)
    val thresholdPx = with(LocalDensity.current) { cancelThreshold.toPx() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary)
            .pointerInput(thresholdPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val started = start()
                    var dx = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        dx += change.positionChange().x
                        if (started) slide(dx, dx < -thresholdPx)
                        if (!change.pressed) break
                        change.consume()
                    }
                    if (started) end(dx < -thresholdPx)
                }
            },
        contentAlignment = Alignment.Center,
    ) { MicIcon(Color.White) }
}
