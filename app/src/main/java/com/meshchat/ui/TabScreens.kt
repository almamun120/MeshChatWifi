package com.meshchat.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshchat.core.NodeIds
import com.meshchat.data.UserEntity
import com.meshchat.repo.PeerUi

// ---------------------------------------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: MainViewModel, onOpenAnnounce: () -> Unit, onSelectTab: (Tab) -> Unit) {
    val peers by vm.peers.collectAsState()
    val posts by vm.posts.collectAsState()
    val chats by vm.chats.collectAsState()
    val status by vm.transport.collectAsState()
    val reachable = peers.count { it.reachable }
    val unread = chats.sumOf { it.unread }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusBanner(status.bluetoothOn, status.error)

        Card(
            Modifier.fillMaxWidth().clickable(onClick = onOpenAnnounce),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Announce", style = MaterialTheme.typography.titleLarge)
                    Text("Public community chat", style = MaterialTheme.typography.bodyMedium)
                    posts.firstOrNull()?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("${it.authorName}: ${it.content}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable { onSelectTab(Tab.Nearby) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Nearby", style = MaterialTheme.typography.titleLarge)
                    Text("$reachable nearby users", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Card(Modifier.fillMaxWidth().clickable { onSelectTab(Tab.Chats) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Email, null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Chats", style = MaterialTheme.typography.titleLarge)
                    Text("Private conversations", style = MaterialTheme.typography.bodyMedium)
                }
                if (unread > 0) Badge { Text("$unread") }
            }
        }
    }
}

@Composable
fun StatusBanner(bluetoothOn: Boolean, error: String?) {
    val text = when {
        !bluetoothOn -> "Bluetooth is off — turn it on to join the mesh."
        error != null -> "BLE: $error"
        else -> null
    } ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

// ---------------------------------------------------------------------------------------------- Nearby

@Composable
fun NearbyScreen(vm: MainViewModel, onOpenChat: (String) -> Unit) {
    val peers by vm.peers.collectAsState()
    val now by rememberNow()
    if (peers.isEmpty()) {
        EmptyState("No MeshChat users found yet.\nKeep Bluetooth on and stay within a few metres of another phone running MeshChat.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item { Text("Nearby Mesh Users", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
        items(peers, key = { it.nodeId }) { p -> PeerRow(p, now) { onOpenChat(p.nodeId) } }
    }
}

@Composable
private fun PeerRow(p: PeerUi, now: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(p.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(p.reachable)
                Spacer(Modifier.width(8.dp))
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val detail = buildString {
                append(hopsText(p.hops))
                append(" • ")
                append(if (p.reachable) "Online" else "Last seen ${agoText(now, p.lastSeen)}")
                if (p.rssi != null) append(" • ${p.rssi} dBm")
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(NodeIds.display(p.nodeId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!p.canEncrypt) Text("key pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider()
}

// ---------------------------------------------------------------------------------------------- Chats

@Composable
fun ChatsScreen(vm: MainViewModel, onOpenChat: (String) -> Unit) {
    val chats by vm.chats.collectAsState()
    val now by rememberNow()
    if (chats.isEmpty()) {
        EmptyState("No private chats yet.\nOpen the Nearby tab and tap a user to start one.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(chats, key = { it.peerId }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenChat(c.peerId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(c.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(c.lastMessage, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(agoText(now, c.lastTimestamp), style = MaterialTheme.typography.labelSmall)
                    if (c.unread > 0) Badge { Text("${c.unread}") }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------- Me

@Composable
fun MeScreen(vm: MainViewModel, user: UserEntity, onOpenDebug: () -> Unit, onOpenTopology: () -> Unit, onOpenSettings: () -> Unit = {}, onOpenAbout: () -> Unit = {}) {
    val peers by vm.peers.collectAsState()
    val status by vm.transport.collectAsState()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setPhoto(it) }
    val photo = remember(user.photoPath) { user.photoPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    val connected = status.links > 0
    val nearby = peers.count { it.reachable }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (photo != null) {
            Image(photo, null, Modifier.size(96.dp).clip(CircleShape).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentScale = ContentScale.Crop)
        } else {
            Row(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Avatar(user.name, 96.dp) }
        }
        Text("Tap photo to change (stays on this phone)", style = MaterialTheme.typography.labelSmall)
        Text(user.name, style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledValue("DOB", if (user.showDob) MainViewModel.formatDob(user.dobEpochDay) else "Hidden")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show DOB here", Modifier.weight(1f))
                    Switch(checked = user.showDob, onCheckedChange = vm::setShowDob)
                }
                Text("Your date of birth is stored only on this phone and is never sent over the mesh.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LabeledValue("Node ID", NodeIds.display(user.nodeId))
                LabeledValue("Mesh status", if (connected) "Connected" else if (status.bluetoothOn) "Searching…" else "Bluetooth off")
                LabeledValue("Nearby nodes", "$nearby")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keep the mesh alive", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Some phone makers kill background apps aggressively. If relaying stops when the screen is off, exempt MeshChat from battery optimisation.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ignoring) "Battery optimisation: exempt ✓" else "Open battery settings") }
            }
        }

        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) { Text("About the developer") }
        OutlinedButton(onClick = onOpenTopology, modifier = Modifier.fillMaxWidth()) { Text("Mesh visualization") }
        OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) { Text("Debug") }
        Text("MeshChat 1.0 • protocol v1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
