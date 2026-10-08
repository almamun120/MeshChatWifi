package com.meshchat.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.meshchat.ble.BleTransport
import com.meshchat.call.CallAlerts
import com.meshchat.call.WebRtcLink
import com.meshchat.core.CallManager
import com.meshchat.core.CallSignal
import com.meshchat.core.CallSignaler
import com.meshchat.core.CallUi
import com.meshchat.core.FileSource
import com.meshchat.core.LinkGate
import com.meshchat.core.SessionKind
import com.meshchat.core.StartResult
import com.meshchat.core.TransferManager
import com.meshchat.core.TransferUi
import com.meshchat.share.DownloadsSink
import com.meshchat.share.UriFileSource
import com.meshchat.share.WifiTransferLink
import com.meshchat.core.Cap
import com.meshchat.core.Content
import com.meshchat.core.DiscoveryMode
import com.meshchat.core.MeshEngine
import com.meshchat.core.MeshEvent
import com.meshchat.core.MeshStats
import com.meshchat.core.Neighbor
import com.meshchat.core.NodeIds
import com.meshchat.core.Route
import com.meshchat.core.SendResult
import com.meshchat.core.TransportStatus
import com.meshchat.data.ChatEntity
import com.meshchat.data.ChatPrefEntity
import com.meshchat.data.GroupEntity
import com.meshchat.data.SosEntity
import com.meshchat.data.MeshDatabase
import com.meshchat.data.MessageEntity
import com.meshchat.data.PublicPostEntity
import com.meshchat.data.RoomMeshStore
import com.meshchat.data.UserEntity
import com.meshchat.data.KeyVault
import com.meshchat.media.ImageCodec
import com.meshchat.media.RecordedVoice
import com.meshchat.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A person on the mesh as the UI sees it. Never contains a MAC address. */
data class PeerUi(
    val nodeId: String,
    val name: String,
    val hops: Int?,            // null = not reachable right now
    val rssi: Int?,            // only for directly heard (1-hop) nodes
    val lastSeen: Long,
    val reachable: Boolean,
    val canEncrypt: Boolean,   // we hold their public key
    val email: String = "",
)

data class ChatRow(
    val peerId: String, val name: String, val lastMessage: String, val lastTimestamp: Long, val unread: Int,
    val pinned: Boolean = false, val muted: Boolean = false, val isGroup: Boolean = false,
)

/**
 * Single access point for the UI: owns the database, the identity, the BLE transport and the MeshEngine.
 * Layering: UI -> ViewModel -> MeshRepository -> MeshEngine -> BleTransport -> Android Bluetooth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRepository(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = MeshDatabase.build(appContext)
    private val store = RoomMeshStore(db, File(appContext.filesDir, "media"))
    private val vault = KeyVault(appContext)
    private val startMutex = Mutex()

    private val engineFlow = MutableStateFlow<MeshEngine?>(null)
    private val transportFlow = MutableStateFlow<BleTransport?>(null)
    private var eventsJob: Job? = null

    // ---- calls and file transfer (one Wi-Fi session at a time: both managers share the gate)
    private val gate = LinkGate()
    private val callMgr = MutableStateFlow<CallManager?>(null)
    private val xferMgr = MutableStateFlow<TransferManager?>(null)
    private val alerts = CallAlerts(appContext)
    val callUi: Flow<CallUi> = callMgr.flatMapLatest { it?.ui ?: flowOf(CallUi()) }
    val transferUi: Flow<TransferUi> = xferMgr.flatMapLatest { it?.ui ?: flowOf(TransferUi()) }

    @Volatile private var myName: String = ""
    @Volatile private var myEmail: String = ""
    @Volatile var myNodeId: String = ""
        private set

    @Volatile private var appVisible = false
    @Volatile private var openChatPeer: String? = null

    init {
        // Disappearing messages: check every few seconds so they vanish close to their time.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                runCatching { store.purgeExpiredMessages(System.currentTimeMillis()) }
            }
        }
    }

    // ---------------------------------------------------------------- SOS

    val sosList: Flow<List<SosEntity>> = db.sosDao().observeAll()
    val sosActive: Flow<Boolean> = com.meshchat.data.AppSettings.sosActive
    suspend fun markSosSeen() = db.sosDao().markAllSeen()
    suspend fun clearSos() = db.sosDao().deleteAll()

    private var sosSession: com.meshchat.core.SosSession? = null
    private var lastSosLocation: com.meshchat.core.SosLocation? = null

    private fun batteryPercent(): Int {
        val bm = appContext.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val v = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (v in 0..100) v else com.meshchat.core.SosCodec.UNKNOWN_BATTERY
    }

    private fun session(): com.meshchat.core.SosSession = sosSession ?: com.meshchat.core.SosSession(scope, send = { active -> sendSosOnce(active) }).also { sosSession = it }

    private suspend fun sendSosOnce(active: Boolean) {
        val e = engineFlow.value
        if (e == null) return
        var loc: com.meshchat.core.SosLocation? = null
        if (active && com.meshchat.data.AppSettings.sosIncludeLocation) {
            // Fresh fix when possible, otherwise the last one we had. Only ever attached because the user ticked the box.
            val fix = com.meshchat.media.LocationHelper.current(appContext, 8_000)
            if (fix != null) lastSosLocation = com.meshchat.core.SosLocation(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy.toInt() else 0)
            loc = lastSosLocation
        }
        e.sendSos(active, batteryPercent(), loc)
    }

    /** Starts broadcasting an SOS (repeats until [stopSos]). Needs the mesh to be running. */
    fun startSos(includeLocation: Boolean): Boolean {
        if (engineFlow.value == null) return false
        com.meshchat.data.AppSettings.setSosIncludeLocation(includeLocation)
        com.meshchat.data.AppSettings.setSosActive(true)
        session().start()
        return true
    }

    fun stopSos() {
        com.meshchat.data.AppSettings.setSosActive(false)
        sosSession?.stopSafe()
        lastSosLocation = null
    }

    private suspend fun onSos(a: com.meshchat.core.SosAlert) {
        if (!com.meshchat.data.AppSettings.sosAlerts.value) return          // receiver opted out: still relayed by the engine
        if (db.nodeDao().get(a.nodeId)?.blocked == true) return
        val old = db.sosDao().observeAll().first().firstOrNull { it.nodeId == a.nodeId }
        val loc = a.location
        db.sosDao().upsert(
            SosEntity(
                a.nodeId, a.name.ifBlank { NodeIds.display(a.nodeId) }, a.active, a.battery, loc != null, loc?.lat ?: 0.0, loc?.lon ?: 0.0,
                loc?.accuracyM ?: 0, a.timestamp, a.hops, a.verified, seen = appVisible && openSosScreen,
            ),
        )
        val firstOfThisAlert = old == null || old.active != a.active
        if (firstOfThisAlert) {
            val detail = (if (a.active) "Needs help" else "Cancelled the alert") + " • ${a.hops} hop(s) away" +
                (if (a.battery <= 100) " • battery ${a.battery}%" else "") + (if (loc != null) " • location attached" else "")
            Notifier.notifySos(appContext, a.nodeId, a.name.ifBlank { NodeIds.display(a.nodeId) }, a.active, detail)
        }
    }

    @Volatile var openSosScreen = false

    /** Incoming push-to-talk clips that should be played right away (the ViewModel owns the player). */
    val pttPlay = kotlinx.coroutines.flow.MutableSharedFlow<MessageEntity>(extraBufferCapacity = 8)

    // ---------------------------------------------------------------- profile

    val profile: Flow<UserEntity?> = db.userDao().observe()

    /**
     * Creates the profile. The identity key pair (and so the Node ID) is created here if it does not exist yet.
     * DOB is stored locally only. Name is the only profile field that ever leaves the phone (inside signed packets).
     */
    suspend fun createProfile(name: String, dobEpochDay: Long, showDob: Boolean, email: String = ""): UserEntity = withContext(Dispatchers.IO) {
        val id = vault.loadOrCreate()
        val user = UserEntity(
            id = 1, name = name.trim(), dobEpochDay = dobEpochDay, showDob = showDob,
            photoPath = null, nodeId = id.nodeId, createdAt = System.currentTimeMillis(), email = email.trim(),
        )
        db.userDao().upsert(user)
        myNodeId = id.nodeId
        myName = user.name
        myEmail = user.email
        user
    }

    /** Changes the optional e-mail and sends the new identity to everybody in range. */
    suspend fun setEmail(email: String) = withContext(Dispatchers.IO) {
        val u = db.userDao().get() ?: return@withContext
        db.userDao().upsert(u.copy(email = email.trim()))
        myEmail = email.trim()
        engineFlow.value?.refreshIdentity()
    }

    /** Passphrase-protected JSON backup of the identity + profile; null if there is no profile yet. */
    suspend fun exportBackup(passphrase: CharArray): String? = withContext(Dispatchers.IO) {
        val u = db.userDao().get() ?: return@withContext null
        com.meshchat.core.IdentityBackup.export(vault.loadOrCreate(), u.name, u.dobEpochDay, u.showDob, passphrase)
    }

    /** Restores a backup; only allowed while no profile exists (fresh install). Returns null on success or the error. */
    suspend fun importBackup(json: String, passphrase: CharArray): com.meshchat.core.RestoreError? = withContext(Dispatchers.IO) {
        if (db.userDao().get() != null) return@withContext com.meshchat.core.RestoreError.CORRUPT
        val r = com.meshchat.core.IdentityBackup.restore(json, passphrase)
        val d = r.data ?: return@withContext r.error
        val id = com.meshchat.core.Identity.restore(d.privatePkcs8, d.publicX509)
        vault.save(id)
        db.userDao().upsert(
            UserEntity(id = 1, name = d.name, dobEpochDay = d.dobEpochDay, showDob = d.showDob,
                photoPath = null, nodeId = id.nodeId, createdAt = System.currentTimeMillis()),
        )
        myNodeId = id.nodeId
        myName = d.name
        null
    }

    fun readUriText(uri: Uri): String? = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    fun writeUriText(uri: Uri, text: String): Boolean = runCatching {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    suspend fun setShowDob(value: Boolean) = db.userDao().setShowDob(value)

    /** Photo stays on this phone (it is never transmitted in v1). Scaled to <= 256 px. */
    suspend fun setPhoto(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext false
            val scale = 256f / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val file = File(appContext.filesDir, "profile.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            db.userDao().setPhoto(file.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun startMesh() {
        scope.launch {
            startMutex.withLock {
                if (engineFlow.value != null) return@launch
                val user = db.userDao().get() ?: return@launch
                val identity = vault.loadOrCreate()
                if (user.nodeId != identity.nodeId) db.userDao().setNodeId(identity.nodeId)   // keystore key was lost -> new identity
                myNodeId = identity.nodeId
                myName = user.name
                myEmail = user.email

                val transport = BleTransport(appContext, identity.nodeId, Cap.ALL, scope)
                val engine = MeshEngine(identity, { myName }, transport, store, scope, pttAllowed = { com.meshchat.data.AppSettings.pttEnabled.value }, emailProvider = { myEmail })
                transportFlow.value = transport
                engineFlow.value = engine
                val signaler = object : CallSignaler {
                    override suspend fun send(peerId: String, signal: CallSignal) = engine.sendCallSignal(peerId, signal)
                    override suspend fun callKey(peerId: String, callId: ByteArray) = engine.callKey(peerId, callId)
                    override fun isReachable(peerId: String) = engine.isReachable(peerId)
                }
                val calls = CallManager(scope, signaler, gate, { WebRtcLink(appContext) }, ::onCallFinished, com.meshchat.core.CallConfig(
                    prejoin = true,
                    acceptPtt = { com.meshchat.data.AppSettings.pttEnabled.value },
                    autoAnswerPtt = { com.meshchat.data.AppSettings.pttAutoAnswer.value },
                ))
                val transfers = TransferManager(scope, signaler, gate, { WifiTransferLink(appContext) }, { DownloadsSink(appContext) }, ::onTransferFinished, acceptIncoming = { receiveReady && appVisible })
                callMgr.value = calls
                xferMgr.value = transfers
                scope.launch {
                    calls.ui.collect { ui ->
                        alerts.onState(ui)
                        onCallState(ui)
                    }
                }
                eventsJob = scope.launch { engine.events.collect(::onMeshEvent) }
                engine.setDiscoveryMode(currentMode())
                engine.start()
                if (com.meshchat.data.AppSettings.sosActive.value) session().start()      // my SOS survives an app restart
            }
        }
    }

    fun stopMesh() {
        eventsJob?.cancel()
        sosSession?.let { it.stopSafe() }
        sosSession = null
        callMgr.value?.hangUp()
        xferMgr.value?.cancel()
        alerts.release()
        callMgr.value = null
        xferMgr.value = null
        engineFlow.value?.stop()
        engineFlow.value = null
        transportFlow.value = null
    }

    val isRunning: Boolean get() = engineFlow.value != null

    /** UI visibility drives scan aggressiveness: HIGH while a chat is open, NORMAL when visible, LOW in background. */
    fun setUiState(visible: Boolean, chatPeer: String?) {
        appVisible = visible
        openChatPeer = chatPeer
        engineFlow.value?.setDiscoveryMode(currentMode())
    }

    /** Bluetooth scanning shares the 2.4 GHz radio with Wi-Fi Direct; keep it quiet while a call is connecting or active. */
    @Volatile private var callBusy = false

    private fun currentMode(): DiscoveryMode = when {
        callBusy -> DiscoveryMode.LOW
        !appVisible -> DiscoveryMode.LOW
        openChatPeer != null -> DiscoveryMode.HIGH
        else -> DiscoveryMode.NORMAL
    }

    private suspend fun onMeshEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.PrivateReceived -> {
                if (e.ptt && appVisible && com.meshchat.data.AppSettings.pttAutoPlay.value) {
                    db.messageDao().get(e.msgIdHex)?.let { pttPlay.tryEmit(it) }
                }
                if (!appVisible || openChatPeer != e.peerId) {
                    val muted = db.chatPrefDao().get(e.peerId)?.muted == true
                    val name = db.groupDao().get(e.peerId)?.name ?: db.nodeDao().get(e.peerId)?.name?.ifBlank { null } ?: NodeIds.display(e.peerId)
                    if (!muted) Notifier.notifyMessage(appContext, e.peerId, name, e.preview)
                } else {
                    db.chatDao().markRead(e.peerId)
                }
            }
            is MeshEvent.PostReceived -> if (!appVisible) Notifier.notifyMessage(appContext, "announce", "Announce • ${e.authorName}", e.content)
            is MeshEvent.Delivered -> Unit
            is MeshEvent.GroupGone -> {
                clearChat(e.groupId, removeChat = true)
                db.chatPrefDao().delete(e.groupId)
            }
            is MeshEvent.SosReceived -> onSos(e.alert)
            is MeshEvent.CallSignalReceived ->
                if (e.signal.kind == SessionKind.FILES) xferMgr.value?.onSignal(e.peerId, e.signal)
                else callMgr.value?.onSignal(e.peerId, e.signal)
        }
    }

    private suspend fun peerName(peerId: String): String =
        db.nodeDao().get(peerId)?.name?.ifBlank { null } ?: NodeIds.display(peerId)

    private var lastCallPhase = com.meshchat.core.CallPhase.IDLE

    /** Incoming-call notification (so a call is noticed with the screen off) and the service's media foreground type. */
    private suspend fun onCallState(ui: CallUi) {
        if (ui.phase == com.meshchat.core.CallPhase.INCOMING && lastCallPhase != ui.phase) {
            if (!(ui.ptt && com.meshchat.data.AppSettings.pttAutoAnswer.value)) Notifier.incomingCall(appContext, peerName(ui.peerId), ui.video, ui.ptt)
        }
        if (ui.phase != com.meshchat.core.CallPhase.INCOMING && lastCallPhase == com.meshchat.core.CallPhase.INCOMING) {
            Notifier.cancelIncomingCall(appContext)
        }
        if (ui.inCall != (lastCallPhase != com.meshchat.core.CallPhase.IDLE && lastCallPhase != com.meshchat.core.CallPhase.ENDED)) {
            com.meshchat.service.MeshService.setCallActive(appContext, ui.inCall, ui.video)
        }
        val busy = ui.phase == com.meshchat.core.CallPhase.CONNECTING || ui.phase == com.meshchat.core.CallPhase.ACTIVE
        if (busy != callBusy) {
            callBusy = busy
            engineFlow.value?.setDiscoveryMode(currentMode())
        }
        lastCallPhase = ui.phase
    }

    private fun onCallFinished(r: com.meshchat.core.CallRecord) {
        if (!r.outgoing && r.outcome == com.meshchat.core.CallEnd.MISSED) {
            scope.launch { Notifier.notifyMessage(appContext, "call-" + r.peerId, "Missed " + (if (r.video) "video " else "") + "call", peerName(r.peerId), com.meshchat.data.AppSettings.callNotifications.value) }
        }
    }

    private fun onTransferFinished(r: com.meshchat.core.TransferRecord) {
        if (!r.outgoing && r.okCount > 0 && !appVisible) {
            scope.launch { Notifier.notifyMessage(appContext, "files-" + r.peerId, "Received " + r.okCount + " file(s)", "from " + peerName(r.peerId) + " · saved to your download folder", com.meshchat.data.AppSettings.transferNotifications.value) }
        }
    }

    // ---------------------------------------------------------------- calls

    fun startCall(peerId: String, video: Boolean, ptt: Boolean = false): StartResult = callMgr.value?.startCall(peerId, video, ptt) ?: StartResult.UNREACHABLE
    fun acceptCall() { callMgr.value?.accept() }
    fun rejectCall() { callMgr.value?.reject() }
    fun hangUp() { callMgr.value?.hangUp() }
    fun setMuted(v: Boolean) { callMgr.value?.setMuted(v) }
    fun setSpeaker(v: Boolean) { callMgr.value?.setSpeaker(v) }
    fun setCamera(v: Boolean) { callMgr.value?.setCamera(v) }
    fun switchCamera() { callMgr.value?.switchCamera() }

    // ---------------------------------------------------------------- file transfer

    /** True only while the Share > Receive screen is open: nothing is accepted (or even announced) otherwise. */
    @Volatile private var receiveReady = false
    fun setReceiveReady(on: Boolean) { receiveReady = on }

    fun sendFiles(peerId: String, uris: List<Uri>): StartResult {
        val sources: List<FileSource> = uris.mapIndexedNotNull { i, u -> UriFileSource.create(appContext, u, i) }
        if (sources.isEmpty()) return StartResult.EMPTY
        return xferMgr.value?.send(peerId, sources) ?: StartResult.UNREACHABLE
    }
    fun acceptTransfer() { xferMgr.value?.accept() }
    fun declineTransfer() { xferMgr.value?.decline() }
    fun cancelTransfer() { xferMgr.value?.cancel() }

    // ---------------------------------------------------------------- observable state

    val neighbors: Flow<List<Neighbor>> = engineFlow.flatMapLatest { it?.neighbors ?: flowOf(emptyList()) }
    val routes: Flow<List<Route>> = engineFlow.flatMapLatest { it?.routes ?: flowOf(emptyList()) }
    val mediaProgress: Flow<Map<String, Float>> = engineFlow.flatMapLatest { it?.mediaProgress ?: flowOf(emptyMap()) }
    val stats: Flow<MeshStats> = engineFlow.flatMapLatest { it?.stats ?: flowOf(MeshStats()) }
    val transportStatus: Flow<TransportStatus> = transportFlow.flatMapLatest { it?.status ?: flowOf(TransportStatus()) }
    val pendingCount: Flow<Int> = db.pendingDao().observeCount()

    val posts: Flow<List<PublicPostEntity>> = db.postDao().observeRecent(300)

    /** Everyone reachable now (route table) or heard directly (advertisements), plus known-but-away nodes. */
    val peers: Flow<List<PeerUi>> = combine(neighbors, routes, db.nodeDao().observeAll()) { nbrs, rts, nodes ->
        val byId = nodes.associateBy { it.nodeId }
        val nbrById = nbrs.associateBy { it.nodeId }
        val routeById = rts.associateBy { it.dest }
        val ids = (nbrById.keys + routeById.keys + byId.keys) - myNodeId
        ids.mapNotNull { id ->
            val node = byId[id]
            if (node?.blocked == true) return@mapNotNull null
            val nbr = nbrById[id]
            val route = routeById[id]
            val reachable = route != null || nbr != null
            val hops = route?.hops ?: if (nbr != null) 1 else null
            PeerUi(
                nodeId = id,
                name = node?.name?.ifBlank { null } ?: NodeIds.display(id),
                hops = hops,
                rssi = nbr?.rssi?.takeIf { it != 0 },
                lastSeen = maxOf(nbr?.lastSeen ?: 0L, route?.updated ?: 0L, node?.lastSeen ?: 0L),
                reachable = reachable,
                email = node?.email ?: "",
                canEncrypt = node?.publicKey != null,
            )
        }.filter { it.reachable || it.canEncrypt }
            .sortedWith(compareByDescending<PeerUi> { it.reachable }.thenBy { it.hops ?: 99 }.thenBy { it.name.lowercase() })
    }

    val chats: Flow<List<ChatRow>> = combine(
        db.chatDao().observeAll(), db.nodeDao().observeAll(), db.chatPrefDao().observeAll(), db.groupDao().observeAll(),
    ) { chats: List<ChatEntity>, nodes, prefs, groups ->
        val names = nodes.associate { it.nodeId to it.name }
        val prefBy = prefs.associateBy { it.peerId }
        val groupBy = groups.associateBy { it.id }
        chats.map {
            val g = groupBy[it.peerId]
            ChatRow(
                it.peerId, g?.name ?: names[it.peerId]?.ifBlank { null } ?: NodeIds.display(it.peerId), it.lastMessage, it.lastTimestamp, it.unread,
                pinned = prefBy[it.peerId]?.pinned == true, muted = prefBy[it.peerId]?.muted == true, isGroup = g != null,
            )
        }.sortedWith(compareByDescending<ChatRow> { it.pinned }.thenByDescending { it.lastTimestamp })
    }

    /** Settings of every chat (pin / mute / disappearing timer). */
    val chatPrefs: Flow<Map<String, ChatPrefEntity>> = db.chatPrefDao().observeAll().map { l -> l.associateBy { it.peerId } }

    fun group(id: String): Flow<GroupEntity?> = db.groupDao().observeAll().map { l -> l.firstOrNull { it.id == id } }

    private suspend fun pref(peerId: String) = db.chatPrefDao().get(peerId) ?: ChatPrefEntity(peerId, pinned = false, muted = false, disappearSec = 0)
    suspend fun setPinned(peerId: String, v: Boolean) = db.chatPrefDao().upsert(pref(peerId).copy(pinned = v))
    suspend fun setChatMuted(peerId: String, v: Boolean) = db.chatPrefDao().upsert(pref(peerId).copy(muted = v))
    suspend fun setDisappear(peerId: String, sec: Int) = db.chatPrefDao().upsert(pref(peerId).copy(disappearSec = sec))

    /** Text search in one chat, or across all chats when [peerId] is null. */
    suspend fun search(query: String, peerId: String?): List<MessageEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.messageDao().search(esc, peerId)
    }

    /** Applies the chat's disappearing timer and an optional reply reference to outgoing content. */
    private suspend fun decorate(peerId: String, c: Content, reply: MessageEntity? = null): Content {
        val ttl = db.chatPrefDao().get(peerId)?.disappearSec ?: 0
        return if (ttl == 0 && reply == null) c
        else c.copyEnvelope(ttlSec = ttl, replyToId = reply?.msgId ?: "", replyQuote = reply?.preview()?.take(60) ?: "")
    }

    suspend fun deleteMessage(peerId: String, m: MessageEntity, forEveryone: Boolean) {
        val e = engineFlow.value
        if (e != null) e.deleteMessage(peerId, m.msgId, forEveryone && m.outgoing) else store.deleteMessageLocal(m.msgId)
    }

    /** Creates a private group; returns its id or null (a member's key is unknown, bad name, too many members). */
    suspend fun createGroup(name: String, members: List<String>): String? = engineFlow.value?.createGroup(name, members)

    /** Creator only: new name and/or new member list ([others] = all members except me). */
    suspend fun updateGroup(id: String, name: String?, others: List<String>?): Boolean =
        engineFlow.value?.updateGroup(id, name, others) ?: false

    /** Removes a person who is not currently on the mesh from this phone's list (they reappear if heard again). */
    suspend fun forgetPeer(nodeId: String) = db.nodeDao().delete(nodeId)

    suspend fun leaveGroup(id: String) {
        engineFlow.value?.leaveGroup(id) ?: db.groupDao().delete(id)
        clearChat(id, removeChat = true)
        db.chatPrefDao().delete(id)
    }

    fun messages(peerId: String): Flow<List<MessageEntity>> = db.messageDao().observe(peerId)

    fun nodeName(peerId: String): Flow<String> =
        if (com.meshchat.core.GroupIds.isGroup(peerId)) db.groupDao().observeAll().map { l -> l.firstOrNull { it.id == peerId }?.name ?: "Group" }
        else db.nodeDao().observeAll().map { list -> list.firstOrNull { it.nodeId == peerId }?.name?.ifBlank { null } ?: NodeIds.display(peerId) }

    // ---------------------------------------------------------------- actions

    suspend fun sendAnnounce(text: String): SendResult = engineFlow.value?.sendAnnounce(text) ?: SendResult.NOT_RUNNING

    suspend fun sendPrivate(peerId: String, text: String, reply: MessageEntity? = null): SendResult =
        engineFlow.value?.sendContent(peerId, decorate(peerId, Content.ofText(text), reply)) ?: SendResult.NOT_RUNNING

    /** One-time, user-initiated location share to ONE person (end-to-end encrypted). Never broadcast. */
    suspend fun sendLocation(peerId: String, lat: Double, lon: Double, accuracyM: Int): SendResult =
        engineFlow.value?.sendContent(peerId, decorate(peerId, Content.ofLocation(lat, lon, accuracyM))) ?: SendResult.NOT_RUNNING

    /** Compresses the picked photo (<= 480 px, ~20-45 KB JPEG, metadata stripped) and sends it. */
    suspend fun sendImage(peerId: String, uri: Uri): SendResult {
        val jpeg = withContext(Dispatchers.IO) { ImageCodec.compress(appContext, uri) } ?: return SendResult.BAD_MEDIA
        return engineFlow.value?.sendContent(peerId, decorate(peerId, Content.ofImage(jpeg))) ?: SendResult.NOT_RUNNING
    }

    suspend fun sendVoice(peerId: String, rec: RecordedVoice, ptt: Boolean = false): SendResult {
        val audio = withContext(Dispatchers.IO) { runCatching { rec.file.readBytes() }.getOrNull().also { rec.file.delete() } }
            ?: return SendResult.BAD_MEDIA
        return engineFlow.value?.sendContent(peerId, decorate(peerId, Content.ofVoice(rec.codec, rec.durationMs, rec.waveform, audio).copyEnvelope(ptt = ptt))) ?: SendResult.NOT_RUNNING
    }

    suspend fun markChatRead(peerId: String) = db.chatDao().markRead(peerId)

    /** Removes every message of the conversation (and its image/voice files). With [removeChat] the chat row goes too. */
    suspend fun clearChat(peerId: String, removeChat: Boolean) = withContext(Dispatchers.IO) {
        db.messageDao().mediaPaths(peerId).forEach { runCatching { java.io.File(it).delete() } }
        db.messageDao().deleteForPeer(peerId)
        val chat = db.chatDao().get(peerId)
        if (removeChat) db.chatDao().delete(peerId)
        else if (chat != null) db.chatDao().upsert(chat.copy(lastMessage = "", unread = 0))
    }

    /** Clears this phone's copy of the public room. Older posts are not re-imported by sync. */
    suspend fun clearAnnounce() = withContext(Dispatchers.IO) {
        com.meshchat.data.AppSettings.markAnnounceCleared()
        db.postDao().deleteAll()
    }

    suspend fun setBlocked(peerId: String, value: Boolean) {
        engineFlow.value?.setBlocked(peerId, value)
    }

    fun syncNow() {
        engineFlow.value?.syncNow()
    }
}
