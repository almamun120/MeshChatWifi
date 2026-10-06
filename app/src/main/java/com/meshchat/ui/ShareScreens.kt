package com.meshchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab as MTab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.call.CallPermissions
import com.meshchat.core.FileRow
import com.meshchat.core.TransferEnd
import com.meshchat.core.TransferPhase
import com.meshchat.share.MediaBrowser
import com.meshchat.share.MediaItem
import com.meshchat.share.MediaKind
import com.meshchat.share.formatBytes
import com.meshchat.share.formatEta
import com.meshchat.share.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ShareBlue = Color(0xFF1E88E5)
private val ShareTeal = Color(0xFF00BFA5)

// ---------------------------------------------------------------------------------------------- Share tab

@Composable
fun ShareHomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(ShareBlue, ShareTeal))).padding(24.dp),
        ) {
            Column {
                Text("Share", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Photos, videos, music and any files, phone to phone. No internet, no data.", color = Color(0xE6FFFFFF))
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigShareButton("Send", "⬆", ShareBlue, Modifier.weight(1f)) { vm.open(Screen.SharePick) }
            BigShareButton("Receive", "⬇", ShareTeal, Modifier.weight(1f)) { vm.open(Screen.ShareReceive) }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How it works", fontWeight = FontWeight.SemiBold)
                Text("1. Pick files and choose a nearby person.", style = MaterialTheme.typography.bodyMedium)
                Text("2. They get a request over Bluetooth and tap Accept.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Both phones switch to a private Wi-Fi Direct link and the files fly across, fast and encrypted.", style = MaterialTheme.typography.bodyMedium)
                Text("Received files are saved in Downloads/MeshChat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun BigShareButton(label: String, glyph: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(color).clickable(onClick = onClick).padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 40.sp, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ShareReceiveScreen(myName: String) {
    val t = rememberInfiniteTransition(label = "radar")
    val k by t.animateFloat(0.4f, 2.2f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "k")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(110.dp).scale(k).alpha((2.2f - k).coerceIn(0f, 1f) * 0.35f).clip(CircleShape).background(ShareTeal))
            Box(Modifier.size(110.dp).scale(k * 0.6f).alpha((2.2f - k).coerceIn(0f, 1f) * 0.35f).clip(CircleShape).background(ShareBlue))
            Avatar(myName, size = 96.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Ready to receive as $myName", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Keep this app open (or running in the background). When someone nearby sends you files, you will be asked to accept.",
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---------------------------------------------------------------------------------------------- file browser

@Composable
fun SharePickScreen(vm: MainViewModel, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val selection by vm.selection.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val kinds = listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.FILE)
    val labels = listOf("Photos", "Videos", "Music", "Files")
    val kind = kinds[tab]

    var granted by remember(kind) { mutableStateOf(MediaBrowser.hasPermission(ctx, kind)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = MediaBrowser.hasPermission(ctx, kind)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addSelected(uris.mapNotNull { MediaBrowser.fromUri(ctx, it) })
    }

    val items by produceState<List<MediaItem>?>(null, kind, granted) {
        value = if (kind == MediaKind.FILE || !granted) emptyList() else withContext(Dispatchers.IO) { MediaBrowser.load(ctx, kind) }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            labels.forEachIndexed { i, l -> MTab(selected = tab == i, onClick = { tab = i }, text = { Text(l) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                kind == MediaKind.FILE -> FilesTab(selection.filter { it.kind == MediaKind.FILE }, { picker.launch(arrayOf("*/*")) }) { vm.toggleSelected(it) }
                !granted -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow access to your ${labels[tab].lowercase()} to choose what to send.", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(MediaBrowser.permissionsFor(kind).toTypedArray()) }) { Text("Allow") }
                    OutlinedButton(onClick = { picker.launch(arrayOf(if (kind == MediaKind.PHOTO) "image/*" else if (kind == MediaKind.VIDEO) "video/*" else "audio/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Or pick with the system browser")
                    }
                }
                items == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                items!!.isEmpty() -> EmptyState("Nothing here")
                kind == MediaKind.PHOTO || kind == MediaKind.VIDEO -> LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    gridItems(items!!, key = { it.uri.toString() }) { m ->
                        Thumb(m, selection.any { it.uri == m.uri }) { vm.toggleSelected(m) }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items!!, key = { it.uri.toString() }) { m ->
                        FileLine(m, selection.any { it.uri == m.uri }) { vm.toggleSelected(m) }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (selection.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${selection.size} selected", fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(selection.sumOf { it.size }), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = vm::clearSelection) { Text("Clear") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = { vm.open(Screen.SharePeers) }) { Text("Send") }
            }
        }
    }
}

@Composable
private fun FilesTab(chosen: List<MediaItem>, onBrowse: () -> Unit, onToggle: (MediaItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse files…") }
        Text(
            "Documents, archives, APKs, anything. The system file browser also reaches Downloads and your other storage.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn {
            items(chosen, key = { it.uri.toString() }) { m ->
                FileLine(m, true) { onToggle(m) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Thumb(m: MediaItem, selected: Boolean, onClick: () -> Unit) {
    val cr = LocalContext.current.contentResolver
    val bmp by produceState<android.graphics.Bitmap?>(null, m.uri) { value = withContext(Dispatchers.IO) { MediaBrowser.thumbnail(cr, m) } }
    Box(Modifier.aspectRatio(1f).background(Color(0xFF263238)).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.kind == MediaKind.VIDEO) {
            Text(durationText(m.durationMs), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        SelectMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color(0x331E88E5)))
    }
}

@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier) {
    Box(
        modifier.size(22.dp).clip(CircleShape).background(if (selected) ShareBlue else Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
}

@Composable
private fun FileLine(m: MediaItem, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (m.kind) { MediaKind.AUDIO -> "🎵"; MediaKind.VIDEO -> "🎬"; MediaKind.PHOTO -> "🖼"; MediaKind.FILE -> "📄" },
            fontSize = 26.sp,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatBytes(m.size) + if (m.durationMs > 0) " · " + durationText(m.durationMs) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        SelectMark(selected, Modifier)
    }
}

private fun durationText(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------------------------------------- choose a person

@Composable
fun SharePeersScreen(vm: MainViewModel, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val peers by vm.peers.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    var pendingPeer by remember { mutableStateOf<String?>(null) }

    fun go(id: String) = startResultText(vm.sendSelected(id))?.let(onMessage)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val id = pendingPeer
        pendingPeer = null
        if (id != null) {
            if (CallPermissions.missing(ctx, CallPermissions.forTransfer()).isEmpty()) go(id)
            else onMessage("Nearby-devices permission is needed for file transfer")
        }
    }
    val reachable = peers.filter { it.reachable && it.canEncrypt }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Send ${selection.size} file(s) · ${formatBytes(selection.sumOf { it.size })} to:",
            Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold,
        )
        if (reachable.isEmpty()) EmptyState("Nobody in range yet. Stand close to the other phone with MeshChat open.")
        LazyColumn {
            items(reachable, key = { it.nodeId }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val missing = CallPermissions.missing(ctx, CallPermissions.forTransfer())
                        if (missing.isEmpty()) go(p.nodeId) else { pendingPeer = p.nodeId; launcher.launch(missing.toTypedArray()) }
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p.name)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name, fontWeight = FontWeight.Medium)
                        Text(hopsText(p.hops), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Icon(Icons.Default.Share, null, tint = ShareBlue)
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- transfer overlay

/** Full-screen request / progress / result screen, for both the sender and the receiver. */
@Composable
fun TransferOverlay(vm: MainViewModel, peerName: String) {
    val ui by vm.transferUi.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val answerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.missing(ctx, CallPermissions.forTransfer()).isEmpty()) vm.acceptTransfer() else vm.declineTransfer()
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (ui.outgoing) "Sending to" else "Receiving from", color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Avatar(peerName, size = 72.dp)
        Spacer(Modifier.height(8.dp))
        Text(peerName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))

        when (ui.phase) {
            TransferPhase.ASKING -> {
                val o = ui.offer
                Text("wants to send you", color = MaterialTheme.colorScheme.outline)
                Text(o?.title.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${o?.count ?: 0} file(s) · ${formatBytes(o?.totalBytes ?: 0)}", color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm::declineTransfer, Modifier.weight(1f)) { Text("Decline") }
                    Button(onClick = {
                        val missing = CallPermissions.missing(ctx, CallPermissions.forTransfer())
                        if (missing.isEmpty()) vm.acceptTransfer() else answerLauncher.launch(missing.toTypedArray())
                    }, Modifier.weight(1f)) { Text("Accept") }
                }
            }
            TransferPhase.OFFERING, TransferPhase.CONNECTING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        ui.phase == TransferPhase.OFFERING -> "Waiting for $peerName to accept…"
                        else -> "Connecting over Wi-Fi Direct…"
                    },
                )
                Text("${ui.offer?.count ?: 0} file(s) · ${formatBytes(ui.offer?.totalBytes ?: 0)}", color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = vm::cancelTransfer, Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            TransferPhase.TRANSFERRING, TransferPhase.ENDED -> {
                val ended = ui.phase == TransferPhase.ENDED
                if (ended) {
                    Text(ui.endReason.label(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        color = if (ui.endReason == TransferEnd.COMPLETED) ShareTeal else MaterialTheme.colorScheme.error)
                } else {
                    Text("${(ui.fraction * 100).toInt()}%", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { ui.fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatBytes(ui.doneBytes)} / ${formatBytes(ui.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                    if (!ended) Text("${formatSpeed(ui.speedBps)} · ${formatEta(ui.etaSec)}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(ui.files.size) { i ->
                        val f = ui.files[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (f.state) { FileRow.DONE -> "✅"; FileRow.FAILED -> "❌"; FileRow.ACTIVE -> "⏳"; else -> "•" },
                                Modifier.width(28.dp),
                            )
                            Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatBytes(f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                if (!ended) OutlinedButton(onClick = vm::cancelTransfer, Modifier.fillMaxWidth()) { Text("Cancel") }
                else if (!ui.outgoing && ui.endReason != TransferEnd.CANCELLED_BY_ME) {
                    Text("Saved to your download folder (change in Settings)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            TransferPhase.IDLE -> Unit
        }
    }
}

private fun Modifier.width(d: androidx.compose.ui.unit.Dp) = this.then(Modifier.size(d, 24.dp))
