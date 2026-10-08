package com.meshchat.call

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.meshchat.core.CallLink
import com.meshchat.core.CallLinkListener
import com.meshchat.core.LinkParams
import com.meshchat.core.MediaSession
import com.meshchat.core.MeshLog
import com.meshchat.core.SecureStream
import com.meshchat.core.StreamSession
import com.meshchat.core.TcpSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio / video call over WebRTC, with no internet and no STUN/TURN: both phones sit in one Wi-Fi Direct group, so
 * host candidates are enough. SDP and ICE candidates travel over a [SecureStream] (AES-GCM TCP) in the same group,
 * keyed by the BLE-negotiated session key; media itself is SRTP/DTLS as usual.
 */
class WebRtcLink(ctx: Context) : CallLink {

    private val appCtx = ctx.applicationContext
    private val group = WifiDirectGroup(appCtx, highThroughput = false)
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val closed = AtomicBoolean(false)
    private val lastRx = AtomicLong(System.currentTimeMillis())
    private var lastPackets = -1L

    @Volatile private var listener: CallLinkListener? = null
    @Volatile private var stream: SecureStream? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var front = true
    private var cameraOn = false
    private var video = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    override suspend fun host(): LinkParams? = group.host(prefer5Ghz = com.meshchat.data.AppSettings.use5Ghz.value)

    override suspend fun join(p: LinkParams): Boolean = group.join(p)

    override fun setListener(l: CallLinkListener?) { listener = l }

    // ------------------------------------------------------------------ media

    override suspend fun startMedia(session: MediaSession): Boolean = withContext(Dispatchers.IO) {
        video = session.video
        val ss = StreamSession(session.callId, session.key, session.isHost)
        val s = if (session.isHost) {
            val server = group.server ?: return@withContext false
            TcpSessions.accept(server, ss, 30_000)
        } else {
            val addr = group.hostAddress ?: return@withContext false
            TcpSessions.connect(addr, WifiDirectGroup.PORT, ss, 20_000)
        } ?: run { MeshLog.log("webrtc: TCP channel to the other phone failed"); return@withContext false }
        if (closed.get()) { s.close(); return@withContext false }
        stream = s
        MeshLog.log("webrtc: signalling channel up (host=${session.isHost}, video=${session.video})")

        enterCallAudio()
        initFactory()
        val connected = CompletableDeferred<Boolean>()
        val peer = createPeer(s, connected) ?: return@withContext false
        pc = peer
        addLocalMedia(peer)
        // WebRTC starts at ~300 kbit/s and ramps up slowly, which looks blurry for most of a short call.
        // The Wi-Fi Direct link has plenty of room, so start higher and allow more.
        runCatching { peer.setBitrate(300_000, 700_000, 1_500_000) }

        val reader = Thread({ readSignals(s, peer) }, "webrtc-signal").apply { isDaemon = true; start() }
        if (session.isHost) {
            val offer = createSdp(peer, offer = true) ?: return@withContext false
            setLocal(peer, offer)
            s.send(T_SDP_OFFER, offer.description.toByteArray())
        }
        val ok = withTimeoutOrNull(25_000) { connected.await() } == true
        if (!ok) MeshLog.log("webrtc: no connection (ice=${peer.iceConnectionState()})")
        if (ok) { lastRx.set(System.currentTimeMillis()); sendCamera(cameraOn) }
        reader.name = "webrtc-signal"
        ok
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions()
        )
        val egl = VideoHub.egl
        factory = PeerConnectionFactory.builder()
            // The Wi-Fi Direct interface (p2p-wlan0-N) is usually not a ConnectivityManager "Network", so WebRTC's
            // Android network monitor would filter it out and ICE would only see the ordinary Wi-Fi/mobile network.
            // Disable the monitor and ignore nothing, so host candidates on the group interface are gathered.
            .setOptions(PeerConnectionFactory.Options().apply {
                networkIgnoreMask = 0
                disableNetworkMonitor = true
            })
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeer(s: SecureStream, connected: CompletableDeferred<Boolean>): PeerConnection? {
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // No STUN/TURN: only addresses of the local Wi-Fi Direct interface are used.
        }
        return factory?.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                runCatching { s.send(T_ICE, "${c.sdpMid}\n${c.sdpMLineIndex}\n${c.sdp}".toByteArray()) }
            }
            override fun onConnectionChange(st: PeerConnection.PeerConnectionState) {
                MeshLog.log("webrtc: $st")
                if (st == PeerConnection.PeerConnectionState.CONNECTED) connected.complete(true)
                if (st == PeerConnection.PeerConnectionState.FAILED) connected.complete(false)
            }
            override fun onTrack(t: RtpTransceiver) {
                val track = t.receiver.track()
                if (track is VideoTrack) VideoHub.setRemote(track)
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) { MeshLog.log("webrtc: ice $p0") }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })
    }

    private fun addLocalMedia(peer: PeerConnection) {
        val f = factory ?: return
        val src = f.createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        })
        audioTrack = f.createAudioTrack("a0", src)
        peer.addTrack(audioTrack, listOf("ms0"))
        if (!video) return

        val enumerator = Camera2Enumerator(appCtx)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (name == null) { MeshLog.log("webrtc: no camera"); return }
        front = enumerator.isFrontFacing(name)
        VideoHub.setFront(front)
        textureHelper = SurfaceTextureHelper.create("capture", VideoHub.egl.eglBaseContext)
        videoSource = f.createVideoSource(false)
        capturer = enumerator.createCapturer(name, cameraEvents)
        capturer?.initialize(textureHelper, appCtx, videoSource!!.capturerObserver)
        capturer?.startCapture(VIDEO_W, VIDEO_H, VIDEO_FPS)
        cameraOn = true
        localVideo = f.createVideoTrack("v0", videoSource)
        val sender = peer.addTrack(localVideo, listOf("ms0"))
        runCatching {
            val params = sender.parameters
            params.encodings.forEach { e ->
                e.maxBitrateBps = 1_500_000
                e.minBitrateBps = 300_000
                e.maxFramerate = VIDEO_FPS
            }
            sender.setParameters(params)
        }
        VideoHub.setLocal(localVideo)
    }

    // ------------------------------------------------------------------ signalling

    private fun readSignals(s: SecureStream, peer: PeerConnection) {
        val pending = ArrayList<IceCandidate>()      // candidates that arrive before the remote description
        var remoteSet = false
        try {
            while (!closed.get()) {
                val m = s.receive() ?: break
                when (m.type) {
                    T_SDP_OFFER -> {
                        val offer = SessionDescription(SessionDescription.Type.OFFER, String(m.payload))
                        setRemoteSync(peer, offer)
                        remoteSet = true
                        pending.forEach { peer.addIceCandidate(it) }; pending.clear()
                        val answer = createSdpSync(peer, offer = false) ?: continue
                        setLocalSync(peer, answer)
                        s.send(T_SDP_ANSWER, answer.description.toByteArray())
                    }
                    T_SDP_ANSWER -> {
                        setRemoteSync(peer, SessionDescription(SessionDescription.Type.ANSWER, String(m.payload)))
                        remoteSet = true
                        pending.forEach { peer.addIceCandidate(it) }; pending.clear()
                    }
                    T_ICE -> {
                        val p = String(m.payload).split("\n", limit = 3)
                        if (p.size == 3) {
                            val c = IceCandidate(p[0], p[1].toIntOrNull() ?: 0, p[2])
                            if (remoteSet) peer.addIceCandidate(c) else pending += c
                        }
                    }
                    T_CAMERA -> listener?.onRemoteCamera(m.payload.firstOrNull()?.toInt() == 1)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) MeshLog.log("webrtc: signalling ended ${e.message}")
        }
    }

    private fun sendCamera(on: Boolean) {
        if (!video) return
        runCatching { stream?.send(T_CAMERA, byteArrayOf(if (on) 1 else 0)) }
    }

    // SDP helpers (blocking, used on the signalling thread)

    private suspend fun createSdp(peer: PeerConnection, offer: Boolean): SessionDescription? =
        withContext(Dispatchers.IO) { createSdpSync(peer, offer) }

    private suspend fun setLocal(peer: PeerConnection, d: SessionDescription) =
        withContext(Dispatchers.IO) { setLocalSync(peer, d) }

    private fun createSdpSync(peer: PeerConnection, offer: Boolean): SessionDescription? {
        val result = CompletableDeferred<SessionDescription?>()
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { result.complete(d) }
            override fun onCreateFailure(e: String?) { MeshLog.log("webrtc: sdp create failed $e"); result.complete(null) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        val c = MediaConstraints()
        if (offer) peer.createOffer(obs, c) else peer.createAnswer(obs, c)
        return kotlinx.coroutines.runBlocking { withTimeoutOrNull(8_000) { result.await() } }
    }

    private fun setLocalSync(peer: PeerConnection, d: SessionDescription) = setSync { peer.setLocalDescription(it, d) }
    private fun setRemoteSync(peer: PeerConnection, d: SessionDescription) = setSync { peer.setRemoteDescription(it, d) }

    private fun setSync(call: (SdpObserver) -> Unit) {
        val done = CompletableDeferred<Unit>()
        call(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(e: String?) { MeshLog.log("webrtc: sdp set failed $e"); done.complete(Unit) }
        })
        kotlinx.coroutines.runBlocking { withTimeoutOrNull(8_000) { done.await() } }
    }

    // ------------------------------------------------------------------ controls

    override fun lastPacketAgeMs(): Long {
        val p = pc ?: return 0
        p.getStats { report: RTCStatsReport ->
            var packets = 0L
            for (st in report.statsMap.values) {
                if (st.type == "inbound-rtp") (st.members["packetsReceived"] as? Number)?.let { packets += it.toLong() }
            }
            if (packets != lastPackets) {
                lastPackets = packets
                lastRx.set(System.currentTimeMillis())
            }
        }
        return System.currentTimeMillis() - lastRx.get()
    }

    // The system may take the camera away (screen off, another app, OEM power rules). Bring it back instead of freezing the picture.
    private var cameraRetries = 0
    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(e: String?) = restartCamera("error $e")
        override fun onCameraDisconnected() = restartCamera("disconnected")
        override fun onCameraFreezed(e: String?) = restartCamera("freezed $e")
        override fun onCameraOpening(cameraName: String?) {}
        override fun onFirstFrameAvailable() { cameraRetries = 0 }
        override fun onCameraClosed() {}
    }

    private fun restartCamera(why: String) {
        if (closed.get() || !cameraOn || cameraRetries >= 30) return
        cameraRetries++
        MeshLog.log("webrtc: camera $why, restart #$cameraRetries")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!closed.get() && cameraOn) runCatching {
                capturer?.stopCapture()
                capturer?.startCapture(VIDEO_W, VIDEO_H, VIDEO_FPS)
            }
        }, 1_500)
    }

    override fun setMuted(muted: Boolean) { audioTrack?.setEnabled(!muted) }

    @Suppress("DEPRECATION")
    override fun setSpeaker(on: Boolean) {
        audioManager.isSpeakerphoneOn = on
    }

    override fun setCamera(on: Boolean) {
        if (!video) return
        cameraOn = on
        localVideo?.setEnabled(on)
        runCatching { if (on) capturer?.startCapture(VIDEO_W, VIDEO_H, VIDEO_FPS) else capturer?.stopCapture() }
        sendCamera(on)
    }

    override fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { front = isFront; VideoHub.setFront(isFront) }
            override fun onCameraSwitchError(e: String?) { MeshLog.log("webrtc: camera switch failed $e") }
        })
    }

    // ------------------------------------------------------------------ audio focus / teardown

    @Suppress("DEPRECATION")
    private fun enterCallAudio() {
        savedMode = audioManager.mode
        savedSpeaker = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener = null
        runCatching { stream?.close() }
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { VideoHub.clear() }
        runCatching { localVideo?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { textureHelper?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { factory?.dispose() }
        runCatching {
            audioManager.mode = savedMode
            audioManager.isSpeakerphoneOn = savedSpeaker
        }
        group.close()
    }

    private companion object {
        const val T_SDP_OFFER = 1
        const val T_SDP_ANSWER = 2
        const val T_ICE = 3
        const val T_CAMERA = 4
        // 480p/24 fps at <= 1.5 Mbit/s: smooth on 2.4 GHz Wi-Fi Direct and within what low-end hardware encoders can do.
        const val VIDEO_W = 854
        const val VIDEO_H = 480
        const val VIDEO_FPS = 24
    }
}
