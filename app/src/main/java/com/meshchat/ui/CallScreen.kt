package com.meshchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.call.CallPermissions
import com.meshchat.call.VideoHub
import com.meshchat.core.CallEnd
import com.meshchat.core.CallPhase
import com.meshchat.core.CallUi
import com.meshchat.core.StartResult
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Green = Color(0xFF25D366)
private val Red = Color(0xFFE53935)
private val Glass = Color(0x33FFFFFF)
private val GlassOn = Color(0xFFFFFFFF)

fun startResultText(r: StartResult): String? = when (r) {
    StartResult.OK -> null
    StartResult.BUSY -> "You are already in a call or transfer"
    StartResult.UNREACHABLE -> "Not in range right now"
    StartResult.NO_KEY -> "Waiting for the other phone's security key. Try again in a moment"
    StartResult.EMPTY -> "Nothing to send"
}

/**
 * Returns a function that asks for the microphone / camera / Wi-Fi permissions the call needs and then starts it.
 * [onMessage] gets any error text.
 */
class CallStarter(val start: (String, Boolean, Boolean) -> Unit) {
    operator fun invoke(peer: String, video: Boolean) = start(peer, video, false)
    fun walkieTalkie(peer: String) = start(peer, false, true)
}

@Composable
fun rememberCallStarter(vm: MainViewModel, onMessage: (String) -> Unit): CallStarter {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val p = pending
        pending = null
        if (p != null) {
            if (CallPermissions.missing(ctx, CallPermissions.forCall(p.second)).isEmpty()) {
                startResultText(vm.startCall(p.first, p.second, p.third))?.let(onMessage)
            } else onMessage("Microphone" + (if (p.second) ", camera" else "") + " and nearby-devices permission are needed for calls")
        }
    }
    return CallStarter { peer, video, ptt ->
        val missing = CallPermissions.missing(ctx, CallPermissions.forCall(video))
        if (missing.isEmpty()) startResultText(vm.startCall(peer, video, ptt))?.let(onMessage)
        else {
            pending = Triple(peer, video, ptt)
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** Full-screen call UI, WhatsApp style. Shown on top of everything while a call exists. */
@Composable
fun CallOverlay(vm: MainViewModel, peerName: String) {
    val ui by vm.callUi.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val now by rememberNow()

    val answerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.missing(ctx, CallPermissions.forCall(ui.video)).isEmpty()) vm.acceptCall() else vm.rejectCall()
    }
    val answer = {
        val missing = CallPermissions.missing(ctx, CallPermissions.forCall(ui.video))
        if (missing.isEmpty()) vm.acceptCall() else answerLauncher.launch(missing.toTypedArray())
    }

    val showVideo = ui.video && ui.phase == CallPhase.ACTIVE
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B141A), Color(0xFF123C45), Color(0xFF0B141A)))),
    ) {
        if (showVideo && ui.remoteCameraOn) {
            VideoSurface(VideoHub.remote.collectAsStateWithLifecycle().value, mirror = false, modifier = Modifier.fillMaxSize(), fill = true)
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (ui.ptt) "MeshChat walkie-talkie · end-to-end encrypted" else if (ui.video) "MeshChat video call · end-to-end encrypted" else "MeshChat call · end-to-end encrypted",
                color = Color(0xCCFFFFFF), fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(statusText(ui, now), color = Color(0xCCFFFFFF), fontSize = 16.sp)
            if (ui.poorConnection && ui.phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(6.dp))
                Text("Poor connection · move closer", color = Color(0xFFFFB74D), fontSize = 13.sp)
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!(showVideo && ui.remoteCameraOn)) PulsingAvatar(peerName, pulse = ui.phase != CallPhase.ACTIVE && ui.phase != CallPhase.ENDED)
                if (showVideo && ui.remoteCameraOn.not()) {
                    Text("Camera is off", color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 190.dp))
                }
                if (showVideo && ui.cameraOn) {
                    Box(Modifier.align(Alignment.TopEnd).width(104.dp).aspectRatio(0.6f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                        VideoSurface(
                            VideoHub.local.collectAsStateWithLifecycle().value,
                            mirror = VideoHub.frontCamera.collectAsStateWithLifecycle().value,
                            modifier = Modifier.fillMaxSize(), fill = true, overlay = true,
                        )
                    }
                }
            }

            when {
                ui.phase == CallPhase.INCOMING -> IncomingButtons(onDecline = vm::rejectCall, onAnswer = answer)
                ui.phase == CallPhase.ENDED -> Spacer(Modifier.height(96.dp))
                else -> ActiveButtons(ui, vm)
            }
        }
    }
}

private fun statusText(ui: CallUi, now: Long): String = when (ui.phase) {
    CallPhase.CALLING -> "Calling…"
    CallPhase.RINGING -> "Ringing…"
    CallPhase.INCOMING -> if (ui.ptt) "Walkie-talkie request" else if (ui.video) "Incoming video call" else "Incoming call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> clockText(((now - ui.connectedAtMs) / 1000).coerceAtLeast(0))
    CallPhase.ENDED -> ui.endReason.label()
    CallPhase.IDLE -> ""
}

private fun clockText(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)

@Composable
private fun PulsingAvatar(name: String, pulse: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val k by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "k")
    Box(contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(Modifier.size(150.dp).scale(k).alpha((1.4f - k).coerceIn(0f, 1f) * 0.5f).clip(CircleShape).background(Color.White))
        }
        Avatar(name, size = 150.dp, tint = Color(0xFF2A6F7A))
    }
}

@Composable
private fun IncomingButtons(onDecline: () -> Unit, onAnswer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction("Decline", Red, 76.dp, onDecline) { Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.rotate(135f)) }
        RoundAction("Answer", Green, 76.dp, onAnswer) { Icon(Icons.Default.Call, null, tint = Color.White) }
    }
}

@Composable
private fun ActiveButtons(ui: CallUi, vm: MainViewModel) {
    val connecting = ui.phase != CallPhase.ACTIVE
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 12.dp)) {
        if (ui.ptt) {
            // Hold to talk: the microphone is open only while the finger is down.
            Box(
                Modifier.fillMaxWidth().height(96.dp).padding(bottom = 12.dp).clip(RoundedCornerShape(48.dp))
                    .background(if (!ui.muted) Green else Glass)
                    .alpha(if (connecting) 0.4f else 1f)
                    .pointerInput(connecting) {
                        if (!connecting) detectTapGestures(onPress = {
                            vm.setMuted(false)
                            try { tryAwaitRelease() } finally { vm.setMuted(true) }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text(if (!ui.muted) "🎙 Talking… release to listen" else "🎙 Hold to talk", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToggleAction("Speaker", "🔊", ui.speaker, !connecting) { vm.setSpeaker(!ui.speaker) }
            if (ui.video) {
                ToggleAction(if (ui.cameraOn) "Camera off" else "Camera on", if (ui.cameraOn) "📹" else "🚫", !ui.cameraOn, !connecting) { vm.setCamera(!ui.cameraOn) }
                ToggleAction("Flip", "🔄", false, !connecting && ui.cameraOn) { vm.switchCamera() }
            }
            if (!ui.ptt) ToggleAction(if (ui.muted) "Unmute" else "Mute", if (ui.muted) "🔇" else "🎤", ui.muted, !connecting) { vm.setMuted(!ui.muted) }
        }
        Spacer(Modifier.height(26.dp))
        RoundAction(if (ui.phase == CallPhase.CALLING || ui.phase == CallPhase.RINGING) "Cancel" else "End", Red, 72.dp, vm::hangUp) {
            Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.rotate(135f))
        }
    }
}

@Composable
private fun RoundAction(label: String, bg: Color, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xE6FFFFFF), fontSize = 12.sp)
    }
}

@Composable
private fun ToggleAction(label: String, glyph: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(if (active) GlassOn else Glass)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = 24.sp) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xE6FFFFFF), fontSize = 12.sp)
    }
}

/** A WebRTC video track drawn with a SurfaceViewRenderer. */
@Composable
private fun VideoSurface(track: VideoTrack?, mirror: Boolean, modifier: Modifier, fill: Boolean, overlay: Boolean = false) {
    val ctx = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(VideoHub.egl.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(track) {
        track?.addSink(renderer)
        onDispose { runCatching { track?.removeSink(renderer) } }
    }
    DisposableEffect(Unit) { onDispose { runCatching { renderer.release() } } }
    AndroidView(factory = { renderer }, modifier = modifier, update = { it.setMirror(mirror) })
}
