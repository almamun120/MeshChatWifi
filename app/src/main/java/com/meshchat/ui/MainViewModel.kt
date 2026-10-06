package com.meshchat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.MeshChatApp
import com.meshchat.core.CallUi
import com.meshchat.core.StartResult
import com.meshchat.core.TransferUi
import com.meshchat.share.MediaItem
import com.meshchat.core.MeshStats
import com.meshchat.core.Neighbor
import com.meshchat.core.Route
import com.meshchat.core.SendResult
import com.meshchat.core.TransportStatus
import com.meshchat.data.MessageEntity
import com.meshchat.data.PublicPostEntity
import com.meshchat.data.UserEntity
import com.meshchat.media.LocationHelper
import com.meshchat.media.RecordedVoice
import com.meshchat.media.VoicePlayer
import com.meshchat.media.VoiceRecorder
import com.meshchat.repo.ChatRow
import com.meshchat.repo.PeerUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.max
import kotlin.math.roundToLong

sealed interface Screen {
    data object Tabs : Screen
    data object Announce : Screen
    data class Chat(val peerId: String) : Screen
    data object Debug : Screen
    data object Topology : Screen
    data object SharePick : Screen
    data object SharePeers : Screen
    data object ShareReceive : Screen
    data object Settings : Screen
    data object About : Screen
}

enum class Tab { Home, Nearby, Chats, Share, Me }

sealed interface ProfileState {
    data object Loading : ProfileState
    data object None : ProfileState
    data class Ready(val user: UserEntity) : ProfileState
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as MeshChatApp).repo

    // ---- voice: WhatsApp-style recorder and player
    val player = VoicePlayer(app, viewModelScope)
    private val recorder = VoiceRecorder(app)
    val recActive: StateFlow<Boolean> = recorder.active
    val recElapsedMs: StateFlow<Long> = recorder.elapsedMs
    val recLevel: StateFlow<Float> = recorder.level

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // ---- navigation (kept in the ViewModel so it survives rotation)
    private val _screen = MutableStateFlow<Screen>(Screen.Tabs)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val _tab = MutableStateFlow(Tab.Home)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private var visible = false

    fun selectTab(t: Tab) {
        _tab.value = t
    }

    fun open(s: Screen) {
        _screen.value = s
        applyUiState()
    }

    fun back() {
        _screen.value = Screen.Tabs
        applyUiState()
    }

    fun setVisible(v: Boolean) {
        visible = v
        applyUiState()
    }

    private fun applyUiState() {
        val s = _screen.value
        repo.setUiState(visible, (s as? Screen.Chat)?.peerId ?: if (s is Screen.Announce) "announce" else null)
    }

    // ---- state
    val profile: StateFlow<ProfileState> = repo.profile
        .map { if (it == null) ProfileState.None else ProfileState.Ready(it) }
        .state(ProfileState.Loading)

    val peers: StateFlow<List<PeerUi>> = repo.peers.state(emptyList())
    val chats: StateFlow<List<ChatRow>> = repo.chats.state(emptyList())
    val posts: StateFlow<List<PublicPostEntity>> = repo.posts.state(emptyList())
    val neighbors: StateFlow<List<Neighbor>> = repo.neighbors.state(emptyList())
    val routes: StateFlow<List<Route>> = repo.routes.state(emptyList())
    val stats: StateFlow<MeshStats> = repo.stats.state(MeshStats())
    val transport: StateFlow<TransportStatus> = repo.transportStatus.state(TransportStatus())
    val pendingCount: StateFlow<Int> = repo.pendingCount.state(0)
    val mediaProgress: StateFlow<Map<String, Float>> = repo.mediaProgress.state(emptyMap())

    // ---- calls
    val callUi: StateFlow<CallUi> = repo.callUi.state(CallUi())
    fun startCall(peerId: String, video: Boolean): StartResult = repo.startCall(peerId, video)
    fun acceptCall() = repo.acceptCall()
    fun rejectCall() = repo.rejectCall()
    fun hangUp() = repo.hangUp()
    fun setMuted(v: Boolean) = repo.setMuted(v)
    fun setSpeaker(v: Boolean) = repo.setSpeaker(v)
    fun setCamera(v: Boolean) = repo.setCamera(v)
    fun switchCamera() = repo.switchCamera()

    // ---- file sharing (ShareIt-style)
    val transferUi: StateFlow<TransferUi> = repo.transferUi.state(TransferUi())
    private val _selection = MutableStateFlow<List<MediaItem>>(emptyList())
    val selection: StateFlow<List<MediaItem>> = _selection.asStateFlow()

    fun toggleSelected(item: MediaItem) {
        _selection.value = if (_selection.value.any { it.uri == item.uri }) _selection.value.filter { it.uri != item.uri } else _selection.value + item
    }
    fun addSelected(items: List<MediaItem>) {
        val have = _selection.value.map { it.uri }.toSet()
        _selection.value = _selection.value + items.filter { it.uri !in have }
    }
    fun clearSelection() { _selection.value = emptyList() }

    fun sendSelected(peerId: String): StartResult {
        val r = repo.sendFiles(peerId, _selection.value.map { it.uri })
        if (r == StartResult.OK) { _selection.value = emptyList(); back() }
        return r
    }
    fun acceptTransfer() = repo.acceptTransfer()
    fun declineTransfer() = repo.declineTransfer()
    fun cancelTransfer() = repo.cancelTransfer()

    fun messages(peerId: String): Flow<List<MessageEntity>> = repo.messages(peerId)
    fun nodeName(peerId: String): Flow<String> = repo.nodeName(peerId)

    // ---- actions
    fun createProfile(name: String, dobText: String, showDob: Boolean, onError: (String) -> Unit) {
        val n = name.trim()
        if (n.isEmpty()) return onError("Please enter your name")
        if (n.length > 24) return onError("Name is too long (max 24 characters)")
        val dob = parseDob(dobText) ?: return onError("Enter date of birth as DD/MM/YYYY")
        viewModelScope.launch { repo.createProfile(n, dob.toEpochDay(), showDob) }
    }

    fun setShowDob(v: Boolean) {
        viewModelScope.launch { repo.setShowDob(v) }
    }

    fun setPhoto(uri: Uri?) {
        if (uri != null) viewModelScope.launch { repo.setPhoto(uri) }
    }

    fun sendAnnounce(text: String, onResult: (SendResult) -> Unit) {
        viewModelScope.launch { onResult(repo.sendAnnounce(text)) }
    }

    fun sendPrivate(peerId: String, text: String, onResult: (SendResult) -> Unit) {
        viewModelScope.launch { onResult(repo.sendPrivate(peerId, text)) }
    }

    fun sendImage(peerId: String, uri: Uri, onResult: (SendResult) -> Unit) {
        viewModelScope.launch { onResult(repo.sendImage(peerId, uri)) }
    }

    fun startRecording(): Boolean {
        player.stop()
        return recorder.start(viewModelScope)
    }

    fun cancelRecording() = recorder.cancel()

    /** Stops recording and sends the voice note. [onTooShort] is called if nothing usable was recorded. */
    fun finishRecording(peerId: String, onTooShort: () -> Unit, onResult: (SendResult) -> Unit) {
        val rec: RecordedVoice? = recorder.stop()
        if (rec == null) {
            onTooShort()
            return
        }
        viewModelScope.launch { onResult(repo.sendVoice(peerId, rec)) }
    }

    /**
     * Explicit, one-time location share to ONE contact. [approximate] rounds to ~1 km. Returns an error text or null.
     * Requires the user to have confirmed the dialog and granted the location permission first.
     */
    fun shareLocation(peerId: String, approximate: Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val loc = LocationHelper.current(app)
            if (loc == null) {
                onDone("Could not get a location fix. Turn Location on in system settings and try again (GPS works best outdoors).")
                return@launch
            }
            var lat = loc.latitude
            var lon = loc.longitude
            var acc = if (loc.hasAccuracy()) loc.accuracy.toInt() else 0
            if (approximate) {
                lat = (lat * 100).roundToLong() / 100.0
                lon = (lon * 100).roundToLong() / 100.0
                acc = max(acc, 1100)
            }
            onDone(sendResultMessage(repo.sendLocation(peerId, lat, lon, acc)))
        }
    }

    override fun onCleared() {
        player.release()
        recorder.cancel()
        super.onCleared()
    }

    fun markRead(peerId: String) {
        viewModelScope.launch { repo.markChatRead(peerId) }
    }

    fun block(peerId: String) {
        viewModelScope.launch { repo.setBlocked(peerId, true) }
    }

    fun syncNow() = repo.syncNow()

    val myNodeId: String get() = repo.myNodeId

    companion object {
        private val dobFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

        fun parseDob(text: String): LocalDate? = try {
            val d = LocalDate.parse(text.trim(), dobFormat)
            if (d.isAfter(LocalDate.now()) || d.year < 1900) null else d
        } catch (e: DateTimeException) {
            null
        }

        fun formatDob(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}
