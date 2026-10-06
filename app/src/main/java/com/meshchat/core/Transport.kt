package com.meshchat.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class DiscoveryMode { HIGH, NORMAL, LOW }

data class TransportStatus(
    val bluetoothOn: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val links: Int = 0,
    val error: String? = null,
)

sealed interface TransportEvent {
    /** A GATT link to [peerId] is ready for traffic. Emitted before any Frame from that peer. */
    data class LinkUp(val peerId: String, val weInitiated: Boolean) : TransportEvent
    data class LinkDown(val peerId: String) : TransportEvent

    /** Advertisement heard. */
    data class PeerSeen(val peerId: String, val rssi: Int, val caps: Int) : TransportEvent

    /** A fully reassembled packet from a linked peer. */
    data class Frame(val peerId: String, val channel: Channel, val data: ByteArray) : TransportEvent
}

/**
 * The only thing MeshEngine knows about radio. BleTransport implements it on Android;
 * unit tests implement it in memory.
 */
interface MeshTransport {
    val events: SharedFlow<TransportEvent>
    val status: StateFlow<TransportStatus>

    fun start()
    fun stop()
    fun linkedPeers(): Set<String>

    /** Best-effort request to open a link to an advertised peer. The transport applies tie-break and link limits. */
    fun connectTo(peerId: String)

    /** Sends one complete packet (fragmenting if needed). Suspends until written or failed. */
    suspend fun send(peerId: String, channel: Channel, data: ByteArray): Boolean

    fun setDiscoveryMode(mode: DiscoveryMode)
}
