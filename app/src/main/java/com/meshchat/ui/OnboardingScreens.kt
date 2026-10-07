package com.meshchat.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var showDob by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Welcome to MeshChat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Chat with people nearby — no Internet, no server, no phone number. " +
                "Your phone talks to other MeshChat phones over Bluetooth Low Energy and relays messages through the mesh.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(24); error = null },
            label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it.take(60); error = null },
            label = { Text("E-mail (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("If you enter it, people in range see it next to your name.") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        DobField(dob) { dob = it; error = null }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show my date of birth on my Profile screen")
                Text("Never sent over the mesh either way.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = showDob, onCheckedChange = { showDob = it })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleSmall)
                Text("• Your date of birth and photo stay on this phone only.", style = MaterialTheme.typography.bodySmall)
                Text("• Your name, a random Node ID and (only if you enter one) your e-mail are visible to MeshChat users in range.", style = MaterialTheme.typography.bodySmall)
                Text("• Your location is never read or shared.", style = MaterialTheme.typography.bodySmall)
                Text("• Reinstalling creates a new Node ID unless you restore an identity backup (Settings > Identity backup).", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.createProfile(name, dob, showDob, email) { error = it } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create profile") }
        RestoreBackupSection(vm)
    }
}

/** "Restore from backup": pick the JSON file, type the passphrase, get the old Node ID back. */
@Composable
private fun RestoreBackupSection(vm: MainViewModel) {
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { picked = u; pass = ""; err = null } }
    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
        Text("Restore from backup (get my old Node ID)")
    }
    picked?.let { uri ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Enter backup passphrase") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it; err = null }, singleLine = true, label = { Text("Passphrase") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.importBackup(uri, pass) { e -> if (e == null) picked = null else err = e } }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}

/** Date of birth chosen from a calendar (the keyboard entry mode is still one tap away inside the dialog). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DobField(value: String, onPicked: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val utc = java.time.ZoneOffset.UTC
    val todayUtc = remember { java.time.LocalDate.now().atStartOfDay(utc).toInstant().toEpochMilli() }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Date of birth") },
            placeholder = { Text("Tap to choose") },
            trailingIcon = { Text("📅", Modifier.padding(end = 12.dp)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // The text field swallows taps, so a transparent layer on top opens the calendar.
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val initial = MainViewModel.parseDob(value)?.atStartOfDay(utc)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            initialDisplayedMonthMillis = initial ?: java.time.LocalDate.of(2000, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli(),
            yearRange = 1900..java.time.LocalDate.now().year,
            initialDisplayMode = DisplayMode.Picker,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                override fun isSelectableYear(year: Int) = year in 1900..java.time.LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val d = java.time.Instant.ofEpochMilli(ms).atZone(utc).toLocalDate()
                            onPicked(d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                        }
                        open = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** Explains each permission before the system dialog appears. */
@Composable
fun PermissionScreen(
    needsLocationNote: Boolean,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Permissions needed", style = MaterialTheme.typography.headlineMedium)
        if (needsLocationNote) {
            Text(
                "On Android 11 and older, the system only lets apps scan for Bluetooth devices if Location permission is granted " +
                    "and the Location switch is on. MeshChat does not read or share your location.",
            )
        } else {
            Text("MeshChat needs \"Nearby devices\" permission to find and talk to other phones over Bluetooth:")
            Text("• Scan — discover nearby MeshChat users", style = MaterialTheme.typography.bodyMedium)
            Text("• Advertise — let them discover you (only a random Node ID is broadcast)", style = MaterialTheme.typography.bodyMedium)
            Text("• Connect — exchange messages over BLE", style = MaterialTheme.typography.bodyMedium)
            Text("It is not used to learn your location.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Notifications (optional) keep the mesh service visible and alert you to new messages.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
        if (permanentlyDenied) {
            OutlinedButton(
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
        }
    }
}
