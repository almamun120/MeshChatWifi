package com.meshchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshchat.core.GroupLimits
import com.meshchat.core.NodeIds

/**
 * Group settings. Everybody sees the name and members; only the creator can rename, add and remove people, or delete
 * the group for everyone. Other members can leave.
 */
@Composable
fun GroupInfoDialog(vm: MainViewModel, gid: String, onDismiss: () -> Unit, onGone: () -> Unit) {
    val g by vm.group(gid).collectAsState(initial = null)
    val peers by vm.peers.collectAsState()
    val group = g
    if (group == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Group") },
            text = { Text("This group is not available any more.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }
    val me = vm.myNodeId
    val members = group.members.split(',').filter { it.isNotBlank() }       // stored as "ID,ID,ID"
    val isCreator = group.creator == me
    var newName by remember(group.name) { mutableStateOf(group.name) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    val nameOf: (String) -> String = { id -> if (id == me) "You" else peers.firstOrNull { it.nodeId == id }?.name ?: NodeIds.display(id) }
    val others = members.filter { it != me }
    val failText = "Could not change the group (is Bluetooth on, and do you have everybody's key?)"

    if (confirmEnd) {
        ConfirmDialog(
            title = if (isCreator) "Delete group?" else "Leave group?",
            text = if (isCreator) "The group is deleted for all ${members.size} members and its messages are removed from their phones as soon as they receive this."
            else "You stop receiving messages from this group and its messages are removed from this phone.",
            confirmLabel = if (isCreator) "Delete" else "Leave",
            onConfirm = { vm.leaveGroup(gid); onGone() },
            onDismiss = { confirmEnd = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group info") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCreator) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(GroupLimits.MAX_NAME); message = null },
                        singleLine = true, label = { Text("Group name") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = newName.isNotBlank() && newName.trim() != group.name,
                        onClick = { vm.updateGroup(gid, newName, null) { ok -> message = if (ok) null else failText } },
                    ) { Text("Rename") }
                } else {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text("Only the creator (${nameOf(group.creator)}) can change this group.", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text("Members (${members.size}/${GroupLimits.MAX_MEMBERS})", style = MaterialTheme.typography.labelLarge)
                members.forEach { m ->
                    val email = peers.firstOrNull { it.nodeId == m }?.email.orEmpty()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nameOf(m) + if (m == group.creator) "  (creator)" else "", fontWeight = FontWeight.Medium)
                            if (email.isNotEmpty()) Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (isCreator && m != me) {
                            TextButton(onClick = {
                                vm.updateGroup(gid, null, others.filter { it != m }) { ok ->
                                    message = if (ok) null else "A group needs at least 2 people. Delete the group instead."
                                }
                            }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (isCreator) {
                    HorizontalDivider()
                    Text("Add people", style = MaterialTheme.typography.labelLarge)
                    val candidates = peers.filter { it.canEncrypt && it.nodeId !in members }
                    val full = members.size >= GroupLimits.MAX_MEMBERS
                    when {
                        full -> Text("The group is full (${GroupLimits.MAX_MEMBERS} members).", style = MaterialTheme.typography.bodySmall)
                        candidates.isEmpty() -> Text("Nobody else to add. People appear here after your phone has heard them on the mesh.", style = MaterialTheme.typography.bodySmall)
                        else -> candidates.forEach { p ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(p.reachable)
                                Spacer(Modifier.width(8.dp))
                                Text(p.name, Modifier.weight(1f))
                                TextButton(onClick = {
                                    vm.updateGroup(gid, null, others + p.nodeId) { ok -> message = if (ok) null else failText }
                                }) { Text("＋ Add") }
                            }
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (isCreator) Text("Changes are sent to every member; people who are out of range get them when they are back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmEnd = true }) { Text(if (isCreator) "Delete group" else "Leave group", color = MaterialTheme.colorScheme.error) } },
    )
}
