package com.meshchat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.meshchat.ui.ConfirmDialog
import com.meshchat.ui.GroupInfoDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.ble.BlePermissions
import com.meshchat.service.MeshService
import com.meshchat.ui.AnnounceScreen
import com.meshchat.ui.SettingsScreen
import com.meshchat.ui.AboutScreen
import com.meshchat.ui.CallOverlay
import com.meshchat.ui.ShareHomeScreen
import com.meshchat.ui.SharePeersScreen
import com.meshchat.ui.SharePickScreen
import com.meshchat.ui.ShareReceiveScreen
import com.meshchat.ui.TransferOverlay
import com.meshchat.ui.rememberCallStarter
import com.meshchat.core.CallPhase
import com.meshchat.core.TransferPhase
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Share
import com.meshchat.ui.ChatScreen
import com.meshchat.ui.ChatsScreen
import com.meshchat.ui.DebugScreen
import com.meshchat.ui.HomeScreen
import com.meshchat.ui.MainViewModel
import com.meshchat.ui.MeScreen
import com.meshchat.ui.NearbyScreen
import com.meshchat.ui.OnboardingScreen
import com.meshchat.ui.PermissionScreen
import com.meshchat.ui.ProfileState
import com.meshchat.ui.Screen
import com.meshchat.ui.Tab
import com.meshchat.ui.TopologyScreen
import com.meshchat.ui.theme.MeshChatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened by the incoming-call notification: show over the lock screen and wake the display right away,
        // before the call state reaches the UI.
        if (intent?.getBooleanExtra(EXTRA_INCOMING_CALL, false) == true) showOverLockScreen(true)
        enableEdgeToEdge()
        setContent {
            MeshChatTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_INCOMING_CALL, false)) showOverLockScreen(true)
    }

    companion object {
        const val EXTRA_INCOMING_CALL = "incoming_call"
    }

    /** While a call is ringing / running the call screen may show over the lock screen and wake the display. */
    fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val f = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
    }

    // Scan aggressiveness follows visibility: NORMAL while visible, LOW in the background.
    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    when (val p = profile) {
        ProfileState.Loading -> Unit
        ProfileState.None -> OnboardingScreen(vm)
        is ProfileState.Ready -> PermissionGate(vm, p)
    }
}

@Composable
private fun PermissionGate(vm: MainViewModel, ready: ProfileState.Ready) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var askedOnce by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val granted = remember(tick) { BlePermissions.hasRequired(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        denied = !BlePermissions.hasRequired(ctx)
        tick++
    }

    if (!granted) {
        PermissionScreen(
            needsLocationNote = BlePermissions.needsLocationSwitch(),
            permanentlyDenied = askedOnce && denied,
            onRequest = { launcher.launch((BlePermissions.required() + BlePermissions.optional()).toTypedArray()) },
        )
        return
    }

    // Permissions are in place: start the foreground service (we are visible, so Android 12+ allows it).
    LaunchedEffect(Unit) { ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java)) }
    val call by vm.callUi.collectAsStateWithLifecycle()
    val xfer by vm.transferUi.collectAsStateWithLifecycle()
    val callName by remember(call.peerId) { vm.nodeName(call.peerId) }.collectAsStateWithLifecycle(initialValue = "")
    val xferName by remember(xfer.peerId) { vm.nodeName(xfer.peerId) }.collectAsStateWithLifecycle(initialValue = "")
    val callShown = call.phase != CallPhase.IDLE
    LaunchedEffect(call.inCall) { (ctx as? MainActivity)?.showOverLockScreen(call.inCall) }
    Box(Modifier.fillMaxSize()) {
        MainScaffold(vm, ready.user.nodeId, ready.user.name, ready, tick)
        if (xfer.phase != TransferPhase.IDLE && !callShown) TransferOverlay(vm, xferName.ifBlank { "…" })
        if (callShown) CallOverlay(vm, callName.ifBlank { "…" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(vm: MainViewModel, myNodeId: String, myName: String, ready: ProfileState.Ready, tick: Int) {
    val ctx = LocalContext.current
    val screen by vm.screen.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val status by vm.transport.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val chatPrefs by vm.chatPrefs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { m -> scope.launch { snackbar.showSnackbar(m) } }
    val startCall = rememberCallStarter(vm, showMessage)
    var chatMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }       // "clear", "delete" or "announce"
    (screen as? Screen.Chat)?.let { cs ->
        when (confirm) {
            "clear" -> ConfirmDialog("Clear chat?", "All messages in this chat will be removed from this phone.", "Clear",
                { vm.clearChat(cs.peerId, removeChat = false) }, { confirm = null })
            "groupinfo" -> GroupInfoDialog(vm, cs.peerId, onDismiss = { confirm = null }, onGone = { confirm = null; vm.back() })
            "leave" -> ConfirmDialog("Leave group?", "You will stop receiving messages and the group's messages are removed from this phone.", "Leave",
                { vm.leaveGroup(cs.peerId); vm.back() }, { confirm = null })
            "disappear" -> androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Disappearing messages") },
                text = {
                    Column {
                        Text("Messages you send from now on delete themselves on both phones after this time (counted from sending / receiving).", style = MaterialTheme.typography.bodySmall)
                        listOf(0 to "Off", 300 to "5 minutes", 3600 to "1 hour", 86400 to "1 day", 604800 to "7 days").forEach { (sec, label) ->
                            TextButton(onClick = { vm.setDisappear(cs.peerId, sec); confirm = null }) { Text(label) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            )
            "delete" -> ConfirmDialog("Delete chat?", "The chat and all its messages will be removed from this phone.", "Delete",
                { vm.clearChat(cs.peerId, removeChat = true); vm.back() }, { confirm = null })
        }
    }
    if (confirm == "announce") {
        ConfirmDialog("Clear announcements?", "Removes all announcements from this phone. Older ones will not come back.", "Clear",
            { vm.clearAnnounce() }, { confirm = null })
    }

    BackHandler(enabled = screen != Screen.Tabs) { vm.back() }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val btOff = remember(tick, status.bluetoothOn) {
        val adapter = (ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter != null && !adapter.isEnabled
    }
    val locationOff = remember(tick) { BlePermissions.needsLocationSwitch() && !BlePermissions.isLocationSwitchOn(ctx) }

    val title = when (val s = screen) {
        Screen.Tabs -> "MeshChat"
        Screen.Announce -> "Announce"
        Screen.Debug -> "Debug"
        Screen.Topology -> "Mesh visualization"
        Screen.SharePick -> "Choose files"
        Screen.SharePeers -> "Send to"
        Screen.ShareReceive -> "Receive"
        Screen.Settings -> "Settings"
        Screen.About -> "About the developer"
        Screen.Sos -> "SOS alerts"
        is Screen.Chat -> {
            val name by remember(s.peerId) { vm.nodeName(s.peerId) }.collectAsStateWithLifecycle(initialValue = "")
            name.ifBlank { "Chat" }
        }
    }
    val reachable = peers.count { it.reachable }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (screen == Screen.Tabs) {
                            Text("● Mesh: $reachable nodes", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                actions = {
                    val cs = screen
                    if (cs is Screen.Chat) {
                        val isGroup = com.meshchat.core.GroupIds.isGroup(cs.peerId)
                        val pref = chatPrefs[cs.peerId]
                        if (!isGroup) {
                            IconButton(onClick = { startCall(cs.peerId, true) }) { Text("📹") }
                            IconButton(onClick = { startCall(cs.peerId, false) }) { Icon(Icons.Default.Call, "Call") }
                        }
                        Box {
                            IconButton(onClick = { chatMenu = true }) { Text("⋮") }
                            DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                                DropdownMenuItem(text = { Text(if (pref?.pinned == true) "Unpin chat" else "Pin chat") }, onClick = { chatMenu = false; vm.setPinned(cs.peerId, pref?.pinned != true) })
                                DropdownMenuItem(text = { Text(if (pref?.muted == true) "Unmute notifications" else "Mute notifications") }, onClick = { chatMenu = false; vm.setChatMuted(cs.peerId, pref?.muted != true) })
                                if (!isGroup) DropdownMenuItem(text = { Text("📻 Live walkie-talkie") }, onClick = { chatMenu = false; startCall.walkieTalkie(cs.peerId) })
                                DropdownMenuItem(text = { Text("Disappearing messages…") }, onClick = { chatMenu = false; confirm = "disappear" })
                                if (isGroup) DropdownMenuItem(text = { Text("Group info & settings") }, onClick = { chatMenu = false; confirm = "groupinfo" })
                                DropdownMenuItem(text = { Text("Clear chat") }, onClick = { chatMenu = false; confirm = "clear" })
                                DropdownMenuItem(text = { Text("Delete chat") }, onClick = { chatMenu = false; confirm = "delete" })
                            }
                        }
                    }
                    if (cs == Screen.Announce) {
                        IconButton(onClick = { confirm = "announce" }) { Text("🗑") }
                    }
                },
                navigationIcon = {
                    if (screen != Screen.Tabs) {
                        IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
            )
        },
        bottomBar = {
            if (screen == Screen.Tabs) {
                NavigationBar {
                    NavigationBarItem(tab == Tab.Home, { vm.selectTab(Tab.Home) }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(tab == Tab.Nearby, { vm.selectTab(Tab.Nearby) }, { Icon(Icons.Default.Search, null) }, label = { Text("Nearby") })
                    NavigationBarItem(tab == Tab.Chats, { vm.selectTab(Tab.Chats) }, { Icon(Icons.Default.Email, null) }, label = { Text("Chats") })
                    NavigationBarItem(tab == Tab.Share, { vm.selectTab(Tab.Share) }, { Icon(Icons.Default.Share, null) }, label = { Text("Share") })
                    NavigationBarItem(tab == Tab.Me, { vm.selectTab(Tab.Me) }, { Icon(Icons.Default.Person, null) }, label = { Text("Me") })
                }
            }
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        Column(body.fillMaxSize()) {
            val sosIn by vm.sosList.collectAsStateWithLifecycle()
            val activeSos = sosIn.count { it.active }
            if (activeSos > 0 && screen != Screen.Sos) {
                Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xFFC62828)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("🆘 $activeSos active SOS alert(s)", Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White)
                    TextButton(onClick = { vm.open(Screen.Sos) }) { Text("View", color = androidx.compose.ui.graphics.Color.White) }
                }
            }
            if (btOff) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Bluetooth is off", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = {
                        // Android 12+ needs BLUETOOTH_CONNECT for this prompt, which the gate already guarantees.
                        enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("Turn on") }
                }
            }
            if (locationOff) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "Android ${Build.VERSION.RELEASE} needs the Location switch ON for Bluetooth scanning (no location data is used).",
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Open") }
                }
            }
            when (val s = screen) {
                Screen.Tabs -> when (tab) {
                    Tab.Home -> HomeScreen(vm, onOpenAnnounce = { vm.open(Screen.Announce) }, onSelectTab = vm::selectTab, onOpenSos = { vm.open(Screen.Sos) })
                    Tab.Nearby -> NearbyScreen(vm) { vm.open(Screen.Chat(it)) }
                    Tab.Chats -> ChatsScreen(vm) { vm.open(Screen.Chat(it)) }
                    Tab.Share -> ShareHomeScreen(vm)
                    Tab.Me -> MeScreen(vm, ready.user, { vm.open(Screen.Debug) }, { vm.open(Screen.Topology) }, { vm.open(Screen.Settings) }, { vm.open(Screen.About) })
                }
                Screen.Announce -> AnnounceScreen(vm, myNodeId, showMessage)
                is Screen.Chat -> ChatScreen(vm, s.peerId, showMessage)
                Screen.Debug -> DebugScreen(vm, myNodeId)
                Screen.Topology -> TopologyScreen(vm, myName)
                Screen.SharePick -> SharePickScreen(vm, showMessage)
                Screen.SharePeers -> SharePeersScreen(vm, showMessage)
                Screen.ShareReceive -> ShareReceiveScreen(vm, myName)
                Screen.Settings -> SettingsScreen(vm)
                Screen.About -> AboutScreen()
                Screen.Sos -> com.meshchat.ui.SosScreen(vm, { vm.open(Screen.Chat(it)) }, showMessage)
            }
        }
    }
}
