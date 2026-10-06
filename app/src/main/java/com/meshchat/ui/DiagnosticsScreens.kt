package com.meshchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.meshchat.core.MeshLog
import com.meshchat.core.NodeIds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------- Debug

@Composable
fun DebugScreen(vm: MainViewModel, myNodeId: String, modifier: Modifier = Modifier) {
    val status by vm.transport.collectAsState()
    val neighbors by vm.neighbors.collectAsState()
    val routes by vm.routes.collectAsState()
    val stats by vm.stats.collectAsState()
    val pending by vm.pendingCount.collectAsState()
    val now by rememberNow()
    val mono = FontFamily.Monospace
    val logLines by MeshLog.lines.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DebugCard("BLE Status") {
            Mono("Bluetooth:  ${if (status.bluetoothOn) "ON" else "OFF"}")
            Mono("Advertiser: ${if (status.advertising) "Running" else "Stopped"}")
            Mono("Scanner:    ${if (status.scanning) "Running" else "Idle/duty-cycle"}")
            Mono("GATT links: ${status.links}")
            status.error?.let { Mono("Error: $it") }
        }
        DebugCard("Identity") { Mono("Node ID: ${NodeIds.display(myNodeId)}") }
        DebugCard("Neighbors (${neighbors.size})") {
            if (neighbors.isEmpty()) Mono("—")
            else {
                Mono("Node     RSSI  Seen   Link")
                neighbors.forEach { Mono("%-8s %-5s %-6s %s".format(it.nodeId.take(8), if (it.rssi == 0) "?" else "${it.rssi}", "${(now - it.lastSeen) / 1000}s", if (it.linked) "yes" else "adv")) }
            }
        }
        DebugCard("Routes (${routes.size})") {
            if (routes.isEmpty()) Mono("—")
            else {
                Mono("Dest     Hops  Via")
                routes.forEach { Mono("%-8s %-5d %s".format(it.dest, it.hops, it.nextHop)) }
            }
        }
        DebugCard("Messages") {
            Mono("Sent:       ${stats.sent}")
            Mono("Received:   ${stats.received}")
            Mono("Relayed:    ${stats.relayed}")
            Mono("Delivered:  ${stats.delivered} (ACKs)")
            Mono("Synced out: ${stats.synced}")
            Mono("Dropped:    ${stats.dropped}")
            if (stats.lastDrop.isNotEmpty()) Mono("Last drop: ${stats.lastDrop}")
            Mono("Pending (store-and-forward): $pending")
        }
        Button(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Re-announce identity & sync now") }
        DebugCard("Link log (newest last; no message content)") {
            if (logLines.isEmpty()) Mono("—")
            else logLines.takeLast(40).forEach { Mono(it) }
        }
        Button(
            onClick = { clipboard.setText(AnnotatedString(logLines.joinToString("\n"))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy full link log") }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// ---------------------------------------------------------------------------------------------- Topology

/**
 * Approximate map: "me" in the centre, one ring per hop count. A node with hops > 1 is joined to the node that is its
 * next hop from here (that is all this phone knows). Positions are laid out from Node IDs only — NO GPS is involved.
 */
@Composable
fun TopologyScreen(vm: MainViewModel, myName: String, modifier: Modifier = Modifier) {
    val peers by vm.peers.collectAsState()
    val routes by vm.routes.collectAsState()
    val reachable = remember(peers) { peers.filter { it.reachable && it.hops != null } }
    val nextHop = remember(routes) { routes.associate { it.dest to it.nextHop } }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Approximate topology as seen from this phone. Solid lines: direct links / next hops. Not a geographic map.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reachable.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No reachable nodes yet") }
            return@Column
        }
        val lineColor = MaterialTheme.colorScheme.outline
        val rings = reachable.groupBy { it.hops!! }
        val maxHop = rings.keys.max()

        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
            val w = maxWidth
            val h = maxHeight
            val center = Offset(w.value / 2f, h.value / 2f)
            val radiusStep = (min(w.value, h.value) / 2f - 36f) / maxHop

            val positions = HashMap<String, Offset>()
            rings.forEach { (hop, list) ->
                val sorted = list.sortedBy { it.nodeId }
                sorted.forEachIndexed { i, p ->
                    val angle = (2 * PI * i / sorted.size) - PI / 2 + hop * 0.35
                    positions[p.nodeId] = Offset(
                        center.x + (radiusStep * hop * cos(angle)).toFloat(),
                        center.y + (radiusStep * hop * sin(angle)).toFloat(),
                    )
                }
            }

            Canvas(Modifier.fillMaxSize()) {
                val d = density
                reachable.forEach { p ->
                    val to = positions[p.nodeId] ?: return@forEach
                    val parent = if (p.hops == 1) center else nextHop[p.nodeId]?.let { positions[it] } ?: center
                    drawLine(lineColor, Offset(parent.x * d, parent.y * d), Offset(to.x * d, to.y * d), strokeWidth = 2f * d, cap = StrokeCap.Round)
                }
            }
            NodeLabel(myName.ifBlank { "Me" }, center, w, h, true)
            reachable.forEach { p -> positions[p.nodeId]?.let { NodeLabel(p.name, it, w, h, false) } }
        }
    }
}

@Composable
private fun NodeLabel(name: String, pos: Offset, w: Dp, h: Dp, me: Boolean) {
    val size = if (me) 56.dp else 44.dp
    Box(
        Modifier.offset(pos.x.dp - size / 2, pos.y.dp - size / 2).size(size),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name, if (me) 36.dp else 28.dp, if (me) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
            Text(name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
