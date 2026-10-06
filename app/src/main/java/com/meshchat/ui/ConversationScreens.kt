package com.meshchat.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.meshchat.core.MediaLimits
import com.meshchat.core.NodeIds
import com.meshchat.core.Protocol
import com.meshchat.core.SendResult
import com.meshchat.data.MessageEntity
import com.meshchat.media.LocationHelper
import com.meshchat.media.PlayerState
import java.util.Locale

fun sendResultMessage(r: SendResult): String? = when (r) {
    SendResult.OK -> null
    SendResult.EMPTY -> null
    SendResult.TOO_LONG -> "Message is too long"
    SendResult.NO_KEY -> "This user's encryption key has not arrived yet. Stay in range for a few seconds and retry."
    SendResult.SELF -> "You cannot message yourself"
    SendResult.NOT_RUNNING -> "Mesh is not running (check Bluetooth and permissions)"
    SendResult.QUEUE_FULL -> "Too many photos/voice messages are waiting to be delivered. Wait for some to finish."
    SendResult.BAD_MEDIA -> "That file could not be prepared for sending"
}

// ---------------------------------------------------------------------------------------------- Announce

@Composable
fun AnnounceScreen(vm: MainViewModel, myNodeId: String, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val posts by vm.posts.collectAsState()
    val ordered = remember(posts) { posts.reversed() }          // oldest first, newest at the bottom
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }

    LaunchedEffect(ordered.size) { if (ordered.isNotEmpty()) listState.animateScrollToItem(ordered.size - 1) }

    Column(modifier.fillMaxSize().imePadding()) {
        Text(
            "Public room: anyone in the mesh can read and post (text only — photos, voice and location are private-chat features). " +
                "Posts are signed with the author's Node ID; there is no administrator.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ordered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No announcements yet") }
        } else {
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ordered, key = { it.postKey }) { post ->
                    val mine = post.authorId == myNodeId
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (mine) "You" else post.authorName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text(timeText(post.timestamp), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(post.content)
                            Text(
                                NodeIds.display(post.authorId) + if (post.verified) "  ✓ signed" else "  (unverified)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(Protocol.MAX_POST_CHARS) },
                placeholder = { Text("Announce to everyone nearby…") },
                modifier = Modifier.weight(1f), maxLines = 4,
                supportingText = { Text("${text.length}/${Protocol.MAX_POST_CHARS}") },
            )
            IconButton(
                onClick = {
                    val t = text
                    vm.sendAnnounce(t) { r -> if (r == SendResult.OK) text = "" else sendResultMessage(r)?.let(onMessage) }
                },
                enabled = text.isNotBlank(),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Private chat

@Composable
fun ChatScreen(vm: MainViewModel, peerId: String, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val messages by remember(peerId) { vm.messages(peerId) }.collectAsState(initial = emptyList())
    val peers by vm.peers.collectAsState()
    val progress by vm.mediaProgress.collectAsState()
    val player by vm.player.state.collectAsState()
    val recording by vm.recActive.collectAsState()
    val recElapsed by vm.recElapsedMs.collectAsState()
    val recLevel by vm.recLevel.collectAsState()
    val peerName by remember(peerId) { vm.nodeName(peerId) }.collectAsState(initial = "")
    val listState = rememberLazyListState()
    val peer = peers.firstOrNull { it.nodeId == peerId }

    var text by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var locationDialog by remember { mutableStateOf(false) }
    var pendingApprox by remember { mutableStateOf<Boolean?>(null) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf(false) }

    val report: (SendResult) -> Unit = { r -> sendResultMessage(r)?.let(onMessage) }

    LaunchedEffect(peerId, messages.size) {
        vm.markRead(peerId)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // Leaving the chat stops playback and discards an unfinished recording.
    androidx.compose.runtime.DisposableEffect(peerId) {
        onDispose {
            vm.player.stop()
            if (vm.recActive.value) vm.cancelRecording()
        }
    }
    // 60 s limit: send automatically, like WhatsApp.
    LaunchedEffect(recording, recElapsed) {
        if (recording && recElapsed >= MediaLimits.MAX_VOICE_MS) {
            vm.finishRecording(peerId, { onMessage("Recording too short") }, report)
        }
    }

    // ---- attachments
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            onMessage("Compressing photo…")
            vm.sendImage(peerId, uri, report)
        }
    }

    fun share(approximate: Boolean) {
        onMessage("Getting your location…")
        vm.shareLocation(peerId, approximate) { err -> err?.let(onMessage) }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val approx = pendingApprox
        pendingApprox = null
        if (approx != null && result.values.any { it }) share(approx)
        else onMessage("Location permission denied — nothing was shared.")
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onMessage(if (ok) "Microphone allowed — now hold the mic button to record." else "Microphone permission is needed for voice messages.")
    }

    fun startShare(approximate: Boolean) {
        if (LocationHelper.hasPermission(ctx)) share(approximate)
        else {
            pendingApprox = approximate
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        val info = when {
            peer?.reachable == true -> "${hopsText(peer.hops)} away • end-to-end encrypted"
            else -> "Not in range — messages, photos and voice are saved here and delivered automatically when this person is reachable"
        }
        Text(info, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.msgId }) { m ->
                MessageRow(m, progress[m.msgId], player, vm, onOpenImage = { viewingImage = it }, onMessage = onMessage)
            }
        }

        // ---- composer
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                RecordingBar(recElapsed, recLevel, cancelling, Modifier.weight(1f))
            } else {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("🖼  Photo") }, onClick = {
                            attachMenu = false
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("📍  Location") }, onClick = {
                            attachMenu = false
                            locationDialog = true
                        })
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(Protocol.MAX_PRIVATE_CHARS) },
                    placeholder = { Text("Message") }, modifier = Modifier.weight(1f), maxLines = 4,
                )
            }
            Spacer(Modifier.padding(horizontal = 3.dp))
            if (text.isNotBlank() && !recording) {
                IconButton(onClick = {
                    val t = text
                    vm.sendPrivate(peerId, t) { r -> if (r == SendResult.OK) text = "" else report(r) }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            } else {
                MicHoldButton(
                    recording = recording,
                    cancelThreshold = 90.dp,
                    onStart = {
                        when {
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                            vm.startRecording() -> {
                                cancelling = false
                                true
                            }
                            else -> {
                                onMessage("Could not start the microphone")
                                false
                            }
                        }
                    },
                    onSlide = { _, c -> cancelling = c },
                    onEnd = { cancelled ->
                        cancelling = false
                        if (vm.recActive.value) {
                            if (cancelled) vm.cancelRecording()
                            else vm.finishRecording(peerId, { onMessage("Too short — hold the mic button while you speak") }, report)
                        }
                    },
                )
            }
        }
    }

    if (locationDialog) {
        AlertDialog(
            onDismissRequest = { locationDialog = false },
            title = { Text("Share your location with ${peerName.ifBlank { "this user" }}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "• Sent end-to-end encrypted to this person only; relay phones cannot read it.\n" +
                            "• A one-time snapshot — it is not tracked or updated.\n" +
                            "• They will see where you are right now, so share only with people you trust.\n" +
                            "• Never broadcast to Announce or to anyone else.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = {
                        locationDialog = false
                        startShare(true)
                    }) { Text("Share approximate (~1 km)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    locationDialog = false
                    startShare(false)
                }) { Text("Share exact location") }
            },
            dismissButton = { TextButton(onClick = { locationDialog = false }) { Text("Cancel") } },
        )
    }

    viewingImage?.let { path ->
        val bmp = remember(path) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
        Dialog(onDismissRequest = { viewingImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { viewingImage = null }, contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, "Photo", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun MessageRow(
    m: MessageEntity,
    progress: Float?,
    player: PlayerState,
    vm: MainViewModel,
    onOpenImage: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val out = m.outgoing
    val bubble = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 300.dp).background(bubble, RoundedCornerShape(14.dp)).padding(8.dp)) {
            when (m.kind) {
                "IMAGE" -> ImageContent(m, onOpenImage)
                "VOICE" -> VoiceBubbleContent(
                    m, player,
                    onToggle = { m.mediaPath?.let { vm.player.toggle(m.msgId, it, m.durationMs) } },
                    onSeek = { f -> m.mediaPath?.let { vm.player.seekTo(m.msgId, it, m.durationMs, f) } },
                    onSpeed = { vm.player.cycleSpeed() },
                )
                "LOCATION" -> LocationContent(m, onMessage)
                else -> Text(m.text, Modifier.padding(horizontal = 4.dp))
            }
            val status = when {
                !out -> ""
                progress != null -> "  ⏳ ${(progress * 100).toInt()}%"
                else -> "  " + statusLabel(m.status)
            }
            Text(
                timeText(m.timestamp) + status,
                Modifier.align(Alignment.End).padding(top = 2.dp, start = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ImageContent(m: MessageEntity, onOpen: (String) -> Unit) {
    val bmp = remember(m.mediaPath) { m.mediaPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    if (bmp != null) {
        Image(
            bmp, "Photo",
            Modifier.widthIn(max = 240.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).clickable { m.mediaPath?.let(onOpen) },
            contentScale = ContentScale.Fit,
        )
    } else Text("📷 Photo unavailable", Modifier.padding(4.dp))
}

@Composable
private fun LocationContent(m: MessageEntity, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val approximate = m.accuracyM >= 1000
    val coords = String.format(Locale.US, "%.5f, %.5f", m.lat, m.lon)
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "📍 " + (if (m.outgoing) "You shared" else "Shared") + (if (approximate) " an approximate location" else " a location"),
            fontWeight = FontWeight.SemiBold,
        )
        Text(coords, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(
            if (m.accuracyM > 0) "Accuracy ±${m.accuracyM} m" else "Accuracy unknown",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = {
                val label = Uri.encode(if (m.outgoing) "My location" else "Shared location")
                val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", m.lat, m.lon, m.lat, m.lon, label))
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    onMessage("No map app installed. Coordinates: $coords")
                }
            }) { Text("Open in map") }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(String.format(Locale.US, "%.6f, %.6f", m.lat, m.lon)))
                onMessage("Coordinates copied")
            }) { Text("Copy") }
        }
    }
}

private fun statusLabel(s: String) = when (s) {
    "QUEUED" -> "⏳ waiting (saved, will deliver when reachable)"
    "SENT" -> "✓ sent"
    "DELIVERED" -> "✓✓ delivered"
    "FAILED" -> "✗ failed"
    else -> ""
}
