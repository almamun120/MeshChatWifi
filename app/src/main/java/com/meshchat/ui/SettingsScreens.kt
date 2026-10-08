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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tree by AppSettings.downloadTree.collectAsState()
    val ringtone by AppSettings.ringtone.collectAsState()
    val msgN by AppSettings.messageNotifications.collectAsState()
    val callN by AppSettings.callNotifications.collectAsState()
    val xferN by AppSettings.transferNotifications.collectAsState()
    val vib by AppSettings.callVibrate.collectAsState()
    val use5 by AppSettings.use5Ghz.collectAsState()
    val sosTone by AppSettings.sosTone.collectAsState()
    // re-check the call-screen permissions every time the user comes back from system settings
    var resumes by remember { mutableStateOf(0) }
    val owner = ctx as? androidx.lifecycle.LifecycleOwner
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    val overlayOk = remember(resumes) { android.provider.Settings.canDrawOverlays(ctx) }
    val fullScreenOk = remember(resumes) {
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }
    val sosPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            val picked: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AppSettings.setSosTone(picked?.toString() ?: "")
        }
    }
    val sosOn by AppSettings.sosAlerts.collectAsState()
    val pttOn by AppSettings.pttEnabled.collectAsState()
    val pttPlay by AppSettings.pttAutoPlay.collectAsState()
    val pttAnswer by AppSettings.pttAutoAnswer.collectAsState()

    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var exportPass by remember { mutableStateOf("") }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) { exportUri = u; exportPass = ""; exportMsg = null }
    }
    exportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { exportUri = null },
            title = { Text("Protect backup with a passphrase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You need this passphrase to restore. It cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = exportPass, onValueChange = { exportPass = it }, singleLine = true, label = { Text("Passphrase (min 6)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    exportMsg?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.exportBackup(uri, exportPass) { m -> exportMsg = m; if (m == "Backup saved") exportUri = null } }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { exportUri = null }) { Text("Cancel") } },
        )
    }

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
        Section("Incoming call screen") {
            Text(
                "To make the call screen fill the whole display (like a normal phone call), allow MeshChat to display over other apps" +
                    (if (Build.VERSION.SDK_INT >= 34) " and to use full-screen alerts." else "."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            Text(if (overlayOk) "✅ Display over other apps: allowed" else "⚠ Display over other apps: not allowed")
            if (!overlayOk) Button(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow display over other apps") }
            if (Build.VERSION.SDK_INT >= 34) {
                Text(if (fullScreenOk) "✅ Full-screen alerts: allowed" else "⚠ Full-screen alerts: not allowed")
                if (!fullScreenOk) Button(onClick = {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
                }) { Text("Allow full-screen alerts") }
            }
        }
        Section("SOS alert tone") {
            Text(
                when {
                    sosTone == null -> "Phone's default alarm sound"
                    sosTone!!.isEmpty() -> "Silent (vibration only)"
                    else -> runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(sosTone))?.getTitle(ctx) }.getOrNull() ?: "Custom"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "SOS alert tone")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sosTone?.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    sosPicker.launch(i)
                }) { Text("Choose tone") }
                if (sosTone != null) OutlinedButton(onClick = { AppSettings.setSosTone(null) }) { Text("Use default") }
                OutlinedButton(onClick = {
                    com.meshchat.service.Notifier.notifySos(ctx, "test", "Test", true, "This is a test of the SOS alert tone")
                }) { Text("Test") }
            }
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
        Section("Push-to-talk") {
            SwitchRow("Allow push-to-talk to me", pttOn, AppSettings::setPttEnabled)
            Text("Off = short clips are dropped and live walkie-talkie requests are declined without ringing. Normal messages and calls are not affected.", style = MaterialTheme.typography.bodySmall)
            if (pttOn) {
                SwitchRow("Play incoming clips automatically (app open)", pttPlay, AppSettings::setPttAutoPlay)
                SwitchRow("Connect live walkie-talkie without ringing", pttAnswer, AppSettings::setPttAutoAnswer)
            }
        }
        Section("SOS") {
            SwitchRow("Show alerts when someone sends an SOS", sosOn, AppSettings::setSosAlerts)
            Text("Off = no alert or list entry on this phone. It still relays other people's SOS so the mesh keeps working.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Identity backup") {
            Text(
                "Save your identity (Node ID, name, date of birth) as an encrypted JSON file. " +
                    "After reinstalling, choose Restore from backup on the first screen to get the same Node ID back.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { exportPicker.launch("meshchat-identity-backup.json") }, modifier = Modifier.fillMaxWidth()) {
                Text("Export identity backup")
            }
            if (exportMsg == "Backup saved") Text("Backup saved")
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
