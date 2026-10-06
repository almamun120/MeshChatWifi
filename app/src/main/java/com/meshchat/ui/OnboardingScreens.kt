package com.meshchat.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
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
            value = dob, onValueChange = { dob = it.take(10); error = null },
            label = { Text("Date of birth (DD/MM/YYYY)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
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
                Text("• Your name and a random Node ID are visible to MeshChat users in range.", style = MaterialTheme.typography.bodySmall)
                Text("• Your location is never read or shared.", style = MaterialTheme.typography.bodySmall)
                Text("• Reinstalling the app creates a new Node ID.", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.createProfile(name, dob, showDob) { error = it } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create profile") }
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
