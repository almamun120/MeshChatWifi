package com.meshchat.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchat.data.AppSettings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val tree by AppSettings.downloadTree.collectAsState()
    val ringtone by AppSettings.ringtone.collectAsState()
    val msgN by AppSettings.messageNotifications.collectAsState()
    val callN by AppSettings.callNotifications.collectAsState()
    val xferN by AppSettings.transferNotifications.collectAsState()
    val vib by AppSettings.callVibrate.collectAsState()
    val use5 by AppSettings.use5Ghz.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            AppSettings.setDownloadTree(uri)
        }
    }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AppSettings.setRingtone(picked?.toString() ?: "")      // "None" chosen = silent
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Downloads") {
            Text("Received files are saved to:", style = MaterialTheme.typography.bodySmall)
            Text(folderName(tree), fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                if (tree != null) OutlinedButton(onClick = { AppSettings.setDownloadTree(null) }) { Text("Reset") }
            }
        }
        Section("Wi-Fi Direct band") {
            SwitchRow("Use 5 GHz (faster, shorter range)", use5, AppSettings::setUse5Ghz)
            Text(
                "Off = 2.4 GHz: works with every phone and reaches further. On = 5 GHz: smoother video and faster file transfer, " +
                    "but only if both phones support 5 GHz Wi-Fi Direct. The phone that starts the call or transfer decides.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        Section("Ringtone") {
            Text(
                when {
                    ringtone == null -> "Phone default"
                    ringtone!!.isEmpty() -> "Silent"
                    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(ringtone))?.getTitle(ctx) }.getOrNull() ?: "Custom"
                },
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Call ringtone")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtone?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    ringtonePicker.launch(i)
                }) { Text("Choose ringtone") }
                if (ringtone != null) OutlinedButton(onClick = { AppSettings.setRingtone(null) }) { Text("Use default") }
            }
            SwitchRow("Vibrate on incoming call", vib, AppSettings::setCallVibrate)
        }
        Section("Notifications") {
            SwitchRow("New messages", msgN, AppSettings::setMessageNotifications)
            SwitchRow("Incoming calls and missed calls", callN, AppSettings::setCallNotifications)
            SwitchRow("Received files", xferN, AppSettings::setTransferNotifications)
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("System notification settings") }
        }
    }
}

private fun folderName(tree: String?): String {
    if (tree == null) return "Downloads/MeshChat (default)"
    return runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))     // e.g. primary:Movies/Share
        id.substringAfter(':', id).ifEmpty { "Internal storage" }
    }.getOrDefault("Custom folder")
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------------------------- About

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar("আব্দুল্লাহ আল মামুন", size = 96.dp)
        Text("MeshChat", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Offline chat, calls and file sharing over Bluetooth, Wi-Fi Direct and GPS", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Developer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Text("আব্দুল্লাহ আল মামুন", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                InfoRow("📞", "Phone", "+8801945120109") { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+8801945120109"))) }
                HorizontalDivider()
                InfoRow("✉", "Email", "mamun995599@gmail.com") {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mamun995599@gmail.com")))
                }
                HorizontalDivider()
                InfoRow("📍", "Address", "সিদ্ধিরগঞ্জ, ওয়ার্ড-৪, পোস্ট অফিস: ১৪২৮, নারায়ণগঞ্জ, বাংলাদেশ", null)
            }
        }
        Text("Version ${com.meshchat.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { runCatching(onClick) } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(
                value, style = MaterialTheme.typography.bodyLarge,
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
