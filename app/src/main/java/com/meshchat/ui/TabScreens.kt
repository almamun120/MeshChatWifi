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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.meshchat.core.Distance
import com.meshchat.repo.ChatRow
import com.meshchat.repo.PeerUi

// ---------------------------------------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: MainViewModel, onOpenAnnounce: () -> Unit, onSelectTab: (Tab) -> Unit, onOpenSos: () -> Unit = {}) {
    val peers by vm.peers.collectAsState()
    val posts by vm.posts.collectAsState()
    val chats by vm.chats.collectAsState()
    val status by vm.transport.collectAsState()
    val reachable = peers.count { it.reachable }
    val unread = chats.sumOf { it.unread }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusBanner(status.bluetoothOn, status.error)
        SosHomeCard(vm, onOpenSos)

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
    var sub by remember { mutableStateOf(0) }                 // 0 = active, 1 = inactive
    var nearestFirst by remember { mutableStateOf(true) }
    var toForget by remember { mutableStateOf<PeerUi?>(null) }
    toForget?.let { p ->
        ConfirmDialog(
            title = "Delete ${p.name}?",
            text = "Removes this person from the list on this phone. They show up again when their phone is heard.",
            confirmLabel = "Delete",
            onConfirm = { vm.forgetPeer(p.nodeId) },
            onDismiss = { toForget = null },
        )
    }
    val active = peers.filter { it.reachable }
        .sortedBy { Distance.meters(it.hops, it.rssi) ?: Double.MAX_VALUE }
        .let { if (nearestFirst) it else it.reversed() }
    val inactive = peers.filter { !it.reachable }.sortedByDescending { it.lastSeen }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(selectedTabIndex = sub) {
            androidx.compose.material3.Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Active (${active.size})") })
            androidx.compose.material3.Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("Inactive (${inactive.size})") })
        }
        if (sub == 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortButton("Nearest → Farthest", nearestFirst, Modifier.weight(1f)) { nearestFirst = true }
                SortButton("Farthest → Nearest", !nearestFirst, Modifier.weight(1f)) { nearestFirst = false }
            }
            Text(
                "Distance is an estimate from signal strength (directly heard phones) or hop count (relayed).",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
            if (active.isEmpty()) {
                EmptyState("No active MeshChat users right now.\nKeep Bluetooth on and stay within a few metres of another phone running MeshChat.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    items(active, key = { it.nodeId }) { p -> PeerRow(p, now, onDelete = null) { onOpenChat(p.nodeId) } }
                }
            }
        } else {
            if (inactive.isEmpty()) {
                EmptyState("No inactive devices.\nPeople you have met but who are out of range now appear here.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                    items(inactive, key = { it.nodeId }) { p -> PeerRow(p, now, onDelete = { toForget = p }) { onOpenChat(p.nodeId) } }
                }
            }
        }
    }
}

@Composable
private fun SortButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
}

@Composable
private fun PeerRow(p: PeerUi, now: Long, onDelete: (() -> Unit)?, onClick: () -> Unit) {
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
            if (p.email.isNotEmpty()) Text(p.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(NodeIds.display(p.nodeId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Distance.meters(p.hops, p.rssi)?.let {
            Text("≈ ${Distance.format(it)}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        }
        if (!p.canEncrypt) Text("key pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        if (onDelete != null) androidx.compose.material3.IconButton(onClick = onDelete) { Text("🗑") }
    }
    HorizontalDivider()
}

// ---------------------------------------------------------------------------------------------- Chats

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(vm: MainViewModel, onOpenChat: (String) -> Unit) {
    val chats by vm.chats.collectAsState()
    val peers by vm.peers.collectAsState()
    val now by rememberNow()
    var toDelete by remember { mutableStateOf<ChatRow?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.meshchat.data.MessageEntity>>(emptyList()) }
    var newGroup by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.isBlank()) results = emptyList() else vm.search(query, null) { results = it }
    }
    toDelete?.let { c ->
        ConfirmDialog(
            title = "Delete chat?",
            text = "All messages with ${c.name} will be removed from this phone. The other person keeps their copy.",
            confirmLabel = "Delete",
            onConfirm = { if (c.isGroup) vm.leaveGroup(c.peerId) else vm.clearChat(c.peerId, removeChat = true) },
            onDismiss = { toDelete = null },
        )
    }
    if (newGroup) NewGroupDialog(vm, peers.filter { it.canEncrypt }, onDismiss = { newGroup = false }, onCreated = { newGroup = false; onOpenChat(it) })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search messages") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.OutlinedButton(onClick = { newGroup = true }) { Text("＋ Group") }
        }
        if (query.isNotBlank()) {
            if (results.isEmpty()) EmptyState("No messages found")
            else LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.msgId }) { m ->
                    val chatName = chats.firstOrNull { it.peerId == m.peerId }?.name ?: NodeIds.display(m.peerId)
                    Column(Modifier.fillMaxWidth().clickable { onOpenChat(m.peerId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(chatName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(m.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(agoText(now, m.timestamp), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider()
                }
            }
            return@Column
        }
        if (chats.isEmpty()) {
            EmptyState("No chats yet.\nOpen the Nearby tab and tap a user, or create a group.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(chats, key = { it.peerId }) { c ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenChat(c.peerId) }, onLongClick = { toDelete = c }).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (c.isGroup) "👥 " else "") + c.name + (if (c.pinned) " 📌" else "") + (if (c.muted) " 🔕" else ""), fontWeight = FontWeight.SemiBold, maxLines = 1)
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
}

/** Pick a name and 1-7 people (whose keys we already hold) to start a private group. */
@Composable
private fun NewGroupDialog(vm: MainViewModel, candidates: List<com.meshchat.repo.PeerUi>, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New private group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(com.meshchat.core.GroupLimits.MAX_NAME) }, singleLine = true, label = { Text("Group name") })
                Text("Members (up to ${com.meshchat.core.GroupLimits.MAX_MEMBERS - 1}). Every message is sent to each member separately, so keep groups small.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(candidates, key = { it.nodeId }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (p.nodeId in picked) picked - p.nodeId else picked + p.nodeId }, verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = p.nodeId in picked, onCheckedChange = { picked = if (it) picked + p.nodeId else picked - p.nodeId })
                            Text(p.name)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || picked.isEmpty() || picked.size > com.meshchat.core.GroupLimits.MAX_MEMBERS - 1) error = "Enter a name and choose 1–${com.meshchat.core.GroupLimits.MAX_MEMBERS - 1} people"
                else vm.createGroup(name, picked.toList()) { id -> if (id == null) error = "Could not create the group (is Bluetooth on?)" else onCreated(id) }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    var editEmail by remember { mutableStateOf(false) }
    if (editEmail) EmailDialog(vm, user.email) { editEmail = false }
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
                LabeledValue("E-mail (shared with your name)", user.email.ifBlank { "Not set" })
                OutlinedButton(onClick = { editEmail = true }) { Text(if (user.email.isBlank()) "Add e-mail" else "Change e-mail") }
                HorizontalDivider()
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
        OutlinedButton(onClick = onOpenTopology, modifier = Modifier.fillMaxWidth()) { Text("Mesh visualization") }
        OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) { Text("Debug") }
        OutlinedButton(onClick = { com.meshchat.share.AppShare.send(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Share this app (Bluetooth / Quick Share / WhatsApp)") }
        OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) { Text("About the developer") }
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

@Composable
private fun EmailDialog(vm: MainViewModel, current: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("E-mail (optional)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it.take(60); error = null }, singleLine = true, label = { Text("E-mail") })
                Text("Broadcast with your name to MeshChat users in range. Leave empty to share none.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setEmail(text) { e -> if (e == null) onDismiss() else error = e } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
