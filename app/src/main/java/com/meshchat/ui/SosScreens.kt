package com.meshchat.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshchat.data.AppSettings
import com.meshchat.data.SosEntity
import com.meshchat.media.LocationHelper
import java.util.Locale

private val SosRed = Color(0xFFC62828)

/** Big red SOS card for the Home tab. Starting an SOS always asks for confirmation first (no accidental alarms). */
@Composable
fun SosHomeCard(vm: MainViewModel, onOpenSos: () -> Unit) {
    val active by vm.sosActive.collectAsState()
    val list by vm.sosList.collectAsState()
    val incoming = list.count { it.active }
    var confirm by remember { mutableStateOf(false) }
    var includeLocation by remember { mutableStateOf(AppSettings.sosIncludeLocation) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.none { it }) includeLocation = false
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (active) SosRed else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active) {
                Text("🆘 YOUR SOS IS ACTIVE", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Repeating every ~45 seconds to everyone in the mesh until you press the button below.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.stopSos() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SosRed)) {
                    Text("✅ I'm safe — stop SOS")
                }
            } else {
                Text("🆘 SOS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Send an emergency alert to every MeshChat phone in range and beyond (through relays).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SosRed)) { Text("Send SOS") }
            }
            if (list.isNotEmpty()) {
                TextButton(onClick = onOpenSos) {
                    Text(if (incoming > 0) "⚠ $incoming active SOS from others — view" else "SOS history", color = if (active) Color.White else MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            error?.let { Text(it, color = if (active) Color.White else MaterialTheme.colorScheme.error) }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send an SOS?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Everyone running MeshChat within reach gets a loud alert with your name and battery level. " +
                            "It is a broadcast: anyone who receives it can see it. It only reaches people the mesh can reach — " +
                            "it does NOT call emergency services.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(Modifier.fillMaxWidth().clickable {
                        includeLocation = !includeLocation
                        if (includeLocation && !LocationHelper.hasPermission(ctx)) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeLocation, onCheckedChange = null)
                        Text("Include my location (visible to everyone who gets the alert)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    error = if (vm.startSos(includeLocation && LocationHelper.hasPermission(ctx))) null else "Mesh is not running — check Bluetooth and permissions."
                }) { Text("SEND SOS", color = SosRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** SOS alerts received from other people (latest per person). */
@Composable
fun SosScreen(vm: MainViewModel, onOpenChat: (String) -> Unit, onMessage: (String) -> Unit) {
    val list by vm.sosList.collectAsState()
    val now by rememberNow()
    val ctx = LocalContext.current
    LaunchedEffect(list.size, list.count { !it.seen }) { vm.markSosSeen() }
    if (list.isEmpty()) {
        EmptyState("No SOS alerts received.\nYou can switch SOS alerts off in Settings.")
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.nodeId }) { s -> SosRow(s, agoText(now, s.timestamp), onOpenChat, onMessage, ctx) }
        }
        TextButton(onClick = { vm.clearSos() }, modifier = Modifier.padding(8.dp)) { Text("Clear list") }
    }
}

@Composable
private fun SosRow(s: SosEntity, ago: String, onOpenChat: (String) -> Unit, onMessage: (String) -> Unit, ctx: android.content.Context) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (s.active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (s.active) "🆘 " else "✅ ") + s.name + if (s.active) " needs help" else " is safe", fontWeight = FontWeight.Bold)
            Text(
                "$ago • ${s.hops} hop(s) away" + (if (s.battery <= 100) " • battery ${s.battery}%" else "") +
                    if (s.verified) " • ✓ signed" else " • unverified sender",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.hasLocation) {
                val coords = String.format(Locale.US, "%.5f, %.5f (±%d m)", s.lat, s.lon, s.accuracyM)
                Text("📍 $coords", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val uri = Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", s.lat, s.lon, s.lat, s.lon, Uri.encode("SOS " + s.name)))
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        onMessage("No map app installed. Coordinates: $coords")
                    }
                }) { Text("Open in map") }
            } else if (s.active) {
                Text("No location attached.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { onOpenChat(s.nodeId) }) { Text("Message") }
        }
    }
}
