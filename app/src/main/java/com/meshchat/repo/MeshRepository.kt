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
)

data class ChatRow(val peerId: String, val name: String, val lastMessage: String, val lastTimestamp: Long, val unread: Int)

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
    @Volatile var myNodeId: String = ""
        private set

    @Volatile private var appVisible = false
    @Volatile private var openChatPeer: String? = null

    // ---------------------------------------------------------------- profile

    val profile: Flow<UserEntity?> = db.userDao().observe()

    /**
     * Creates the profile. The identity key pair (and so the Node ID) is created here if it does not exist yet.
     * DOB is stored locally only. Name is the only profile field that ever leaves the phone (inside signed packets).
     */
    suspend fun createProfile(name: String, dobEpochDay: Long, showDob: Boolean): UserEntity = withContext(Dispatchers.IO) {
        val id = vault.loadOrCreate()
        val user = UserEntity(
            id = 1, name = name.trim(), dobEpochDay = dobEpochDay, showDob = showDob,
            photoPath = null, nodeId = id.nodeId, createdAt = System.currentTimeMillis(),
        )
        db.userDao().upsert(user)
        myNodeId = id.nodeId
        myName = user.name
        user
    }

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

                val transport = BleTransport(appContext, identity.nodeId, Cap.ALL, scope)
                val engine = MeshEngine(identity, { myName }, transport, store, scope)
                transportFlow.value = transport
                engineFlow.value = engine
                val signaler = object : CallSignaler {
                    override suspend fun send(peerId: String, signal: CallSignal) = engine.sendCallSignal(peerId, signal)
                    override suspend fun callKey(peerId: String, callId: ByteArray) = engine.callKey(peerId, callId)
                    override fun isReachable(peerId: String) = engine.isReachable(peerId)
                }
                val calls = CallManager(scope, signaler, gate, { WebRtcLink(appContext) }, ::onCallFinished)
                val transfers = TransferManager(scope, signaler, gate, { WifiTransferLink(appContext) }, { DownloadsSink(appContext) }, ::onTransferFinished)
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
            }
        }
    }

    fun stopMesh() {
        eventsJob?.cancel()
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

    private fun currentMode(): DiscoveryMode = when {
        !appVisible -> DiscoveryMode.LOW
        openChatPeer != null -> DiscoveryMode.HIGH
        else -> DiscoveryMode.NORMAL
    }

    private suspend fun onMeshEvent(e: MeshEvent) {
        when (e) {
            is MeshEvent.PrivateReceived -> {
                if (!appVisible || openChatPeer != e.peerId) {
                    val name = db.nodeDao().get(e.peerId)?.name?.ifBlank { null } ?: NodeIds.display(e.peerId)
                    Notifier.notifyMessage(appContext, e.peerId, name, e.preview)
                } else {
                    db.chatDao().markRead(e.peerId)
                }
            }
            is MeshEvent.PostReceived -> if (!appVisible) Notifier.notifyMessage(appContext, "announce", "Announce • ${e.authorName}", e.content)
            is MeshEvent.Delivered -> Unit
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
            Notifier.incomingCall(appContext, peerName(ui.peerId), ui.video)
        }
        if (ui.phase != com.meshchat.core.CallPhase.INCOMING && lastCallPhase == com.meshchat.core.CallPhase.INCOMING) {
            Notifier.cancelIncomingCall(appContext)
        }
        if (ui.inCall != (lastCallPhase != com.meshchat.core.CallPhase.IDLE && lastCallPhase != com.meshchat.core.CallPhase.ENDED)) {
            com.meshchat.service.MeshService.setCallActive(appContext, ui.inCall, ui.video)
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

    fun startCall(peerId: String, video: Boolean): StartResult = callMgr.value?.startCall(peerId, video) ?: StartResult.UNREACHABLE
    fun acceptCall() { callMgr.value?.accept() }
    fun rejectCall() { callMgr.value?.reject() }
    fun hangUp() { callMgr.value?.hangUp() }
    fun setMuted(v: Boolean) { callMgr.value?.setMuted(v) }
    fun setSpeaker(v: Boolean) { callMgr.value?.setSpeaker(v) }
    fun setCamera(v: Boolean) { callMgr.value?.setCamera(v) }
    fun switchCamera() { callMgr.value?.switchCamera() }

    // ---------------------------------------------------------------- file transfer

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
                canEncrypt = node?.publicKey != null,
            )
        }.filter { it.reachable || it.canEncrypt }
            .sortedWith(compareByDescending<PeerUi> { it.reachable }.thenBy { it.hops ?: 99 }.thenBy { it.name.lowercase() })
    }

    val chats: Flow<List<ChatRow>> = combine(db.chatDao().observeAll(), db.nodeDao().observeAll()) { chats: List<ChatEntity>, nodes ->
        val names = nodes.associate { it.nodeId to it.name }
        chats.map { ChatRow(it.peerId, names[it.peerId]?.ifBlank { null } ?: NodeIds.display(it.peerId), it.lastMessage, it.lastTimestamp, it.unread) }
    }

    fun messages(peerId: String): Flow<List<MessageEntity>> = db.messageDao().observe(peerId)

    fun nodeName(peerId: String): Flow<String> =
        db.nodeDao().observeAll().map { list -> list.firstOrNull { it.nodeId == peerId }?.name?.ifBlank { null } ?: NodeIds.display(peerId) }

    // ---------------------------------------------------------------- actions

    suspend fun sendAnnounce(text: String): SendResult = engineFlow.value?.sendAnnounce(text) ?: SendResult.NOT_RUNNING

    suspend fun sendPrivate(peerId: String, text: String): SendResult = engineFlow.value?.sendPrivate(peerId, text) ?: SendResult.NOT_RUNNING

    /** One-time, user-initiated location share to ONE person (end-to-end encrypted). Never broadcast. */
    suspend fun sendLocation(peerId: String, lat: Double, lon: Double, accuracyM: Int): SendResult =
        engineFlow.value?.sendContent(peerId, Content.ofLocation(lat, lon, accuracyM)) ?: SendResult.NOT_RUNNING

    /** Compresses the picked photo (<= 480 px, ~20-45 KB JPEG, metadata stripped) and sends it. */
    suspend fun sendImage(peerId: String, uri: Uri): SendResult {
        val jpeg = withContext(Dispatchers.IO) { ImageCodec.compress(appContext, uri) } ?: return SendResult.BAD_MEDIA
        return engineFlow.value?.sendContent(peerId, Content.ofImage(jpeg)) ?: SendResult.NOT_RUNNING
    }

    suspend fun sendVoice(peerId: String, rec: RecordedVoice): SendResult {
        val audio = withContext(Dispatchers.IO) { runCatching { rec.file.readBytes() }.getOrNull().also { rec.file.delete() } }
            ?: return SendResult.BAD_MEDIA
        return engineFlow.value?.sendContent(peerId, Content.ofVoice(rec.codec, rec.durationMs, rec.waveform, audio)) ?: SendResult.NOT_RUNNING
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
