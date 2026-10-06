package com.meshchat.call

import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.meshchat.core.LinkParams
import com.meshchat.core.MeshLog
import com.meshchat.core.TcpSessions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One Wi-Fi Direct group, used by a call (WebRTC) or a file transfer.
 *
 *  Starter (host): becomes group owner with createGroup(); the SSID / passphrase / owner MAC go to the other phone
 *                  inside the encrypted BLE LINK signal. Wi-Fi Direct's own owner negotiation is never relied on.
 *                  On Android 10+ we choose the credentials ourselves (no need to poll for them) and, for files,
 *                  ask for the 5 GHz band first: it is several times faster than 2.4 GHz.
 *  Guest:          joins that group with the credentials (Android 10+: no discovery dialog; Android 8-9: the system may
 *                  ask the starter's phone to accept the invitation).
 *
 * Needs NEARBY_WIFI_DEVICES (Android 13+) or ACCESS_FINE_LOCATION (older); the UI checks before starting.
 */
@SuppressLint("MissingPermission")
class WifiDirectGroup(ctx: Context, private val highThroughput: Boolean) {

    private val appCtx = ctx.applicationContext
    private val p2p = appCtx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val closed = AtomicBoolean(false)

    /** Host: the TCP server the guest connects to. */
    @Volatile var server: ServerSocket? = null
        private set

    /** Guest: the group owner's IP address on the Wi-Fi Direct network. */
    @Volatile var hostAddress: InetAddress? = null
        private set

    val isClosed: Boolean get() = closed.get()

    // ------------------------------------------------------------------ host

    suspend fun host(prefer5Ghz: Boolean): LinkParams? {
        val mgr = p2p ?: run { MeshLog.log("wifi: no Wi-Fi Direct on this phone"); return null }
        val ch = channelOrNull(mgr) ?: return null
        lockWifi()
        resetState(mgr, ch)
        var group: WifiP2pGroup? = null
        var ssid = ""
        var pass = ""

        // AUTO lets a dual-band phone pick 5 GHz, which a 2.4 GHz-only phone cannot even see (logs: "group owner not
        // seen", join timed out, only in that direction). So unless 5 GHz is explicitly preferred, force 2.4 GHz:
        // every phone supports it. Android 9 and older cannot choose the band.
        val band = if (prefer5Ghz) WifiP2pConfig.GROUP_OWNER_BAND_AUTO else WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
        var r = ERROR
        for (attempt in 1..3) {
            if (closed.get()) return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ssid = "DIRECT-mc-" + randomText(4, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
                pass = randomText(12, "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789")
                val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).enablePersistentMode(false)
                    .setGroupOperatingBand(band).build()
                r = act { mgr.createGroup(ch, cfg, it) }
            } else {
                r = act { mgr.createGroup(ch, it) }
            }
            MeshLog.log("wifi: createGroup attempt $attempt -> ${reasonName(r)}")
            if (r == OK) break
            resetState(mgr, ch)
            delay(600L * attempt)
        }
        if (r != OK || closed.get()) return null

        var tries = 0
        while (group == null && tries++ < 25) {
            if (closed.get()) return null
            val g = groupInfo(mgr, ch)
            if (g != null && !g.passphrase.isNullOrEmpty() && !g.networkName.isNullOrEmpty()) group = g else delay(250)
        }
        val g = group ?: run { MeshLog.log("wifi: group info never became available"); return null }
        return try {
            server = TcpSessions.listen(PORT)
            val mhz = if (Build.VERSION.SDK_INT >= 34) runCatching { g.frequency }.getOrDefault(0) else 0
            MeshLog.log("wifi: group ready, listening on $PORT, band ${if (mhz > 0) "$mhz MHz" else "unknown"}")
            LinkParams(g.networkName, g.passphrase, g.owner?.deviceAddress.orEmpty(), PORT)
        } catch (e: Exception) {
            MeshLog.log("wifi: cannot open server socket ${e.message}")
            null
        }
    }

    /** Clears anything left over from an earlier session. A stuck Wi-Fi Direct framework is a usual cause of "the next call never connects". */
    private suspend fun resetState(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        act { mgr.cancelConnect(ch, it) }
        act { mgr.stopPeerDiscovery(ch, it) }
        val r = act { mgr.removeGroup(ch, it) }
        MeshLog.log("wifi: reset (removeGroup -> ${reasonName(r)})")
        delay(400)
    }

    private fun reasonName(r: Int) = when (r) {
        OK -> "ok"
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "code $r"
    }

    // ------------------------------------------------------------------ guest

    suspend fun join(p: LinkParams): Boolean {
        val mgr = p2p ?: return false
        val ch = channelOrNull(mgr) ?: return false
        lockWifi()
        resetState(mgr, ch)
        MeshLog.log("wifi: joining ${p.ssid}")
        repeat(3) { attempt ->
            if (closed.get()) return false
            act { mgr.discoverPeers(ch, it) }                          // lets the phone see the starter's group
            val seen = waitForOwner(mgr, ch, p.ownerMac, if (attempt == 0) 6_000 else 4_000)
            MeshLog.log("wifi: group owner ${if (seen) "visible" else "not seen yet"}, connecting (attempt ${attempt + 1})")
            val r = act { mgr.connect(ch, configFor(p), it) }
            if (r != OK) {
                MeshLog.log("wifi: connect attempt ${attempt + 1} failed (${reasonName(r)})")
                return@repeat
            }
            val until = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < until && !closed.get()) {
                val info = connectionInfo(mgr, ch)
                if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                    hostAddress = info.groupOwnerAddress
                    MeshLog.log("wifi: joined, owner at ${info.groupOwnerAddress?.hostAddress}")
                    act { mgr.stopPeerDiscovery(ch, it) }
                    return true
                }
                delay(400)
            }
            MeshLog.log("wifi: join attempt ${attempt + 1} timed out")
            act { mgr.cancelConnect(ch, it) }
        }
        return false
    }

    /** Connecting before the owner shows up in the peer list is a common reason for "join timed out" on some phones. */
    private suspend fun waitForOwner(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, mac: String, maxMs: Long): Boolean {
        if (mac.isEmpty()) { delay(1_200); return false }
        val until = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < until && !closed.get()) {
            val seen = suspendCancellableCoroutine<Boolean> { c ->
                try {
                    mgr.requestPeers(ch) { list ->
                        if (c.isActive) c.resume(list.deviceList.any { it.deviceAddress.equals(mac, ignoreCase = true) })
                    }
                } catch (e: Exception) {
                    if (c.isActive) c.resume(false)
                }
            }
            if (seen) return true
            delay(500)
        }
        return false
    }

    private fun configFor(p: LinkParams): WifiP2pConfig =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val b = WifiP2pConfig.Builder().setNetworkName(p.ssid).setPassphrase(p.passphrase).enablePersistentMode(false)
            if (p.ownerMac.isNotEmpty()) runCatching { b.setDeviceAddress(MacAddress.fromString(p.ownerMac)) }
            b.build()
        } else {
            WifiP2pConfig().apply {
                deviceAddress = p.ownerMac
                wps.setup = WpsInfo.PBC
            }
        }

    // ------------------------------------------------------------------ teardown

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        val mgr = p2p
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.cancelConnect(ch, null) }
            runCatching { mgr.removeGroup(ch, null) }
            runCatching { mgr.stopPeerDiscovery(ch, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { ch.close() }
        }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    // ------------------------------------------------------------------ helpers

    private fun channelOrNull(mgr: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        return try {
            mgr.initialize(appCtx, Looper.getMainLooper(), null)?.also { channel = it }
        } catch (e: SecurityException) {
            MeshLog.log("wifi: Wi-Fi Direct permission missing")
            null
        }
    }

    /** Low latency for calls, full throughput for files, for as long as the session lasts. */
    private fun lockWifi() {
        if (wifiLock != null) return
        runCatching {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = when {
                highThroughput -> WifiManager.WIFI_MODE_FULL_HIGH_PERF
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else -> WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "meshchat-session").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
    }

    /** Runs a WifiP2pManager action and returns OK or the failure reason code. */
    private suspend fun act(block: (WifiP2pManager.ActionListener) -> Unit): Int =
        suspendCancellableCoroutine { c ->
            try {
                block(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (c.isActive) c.resume(OK) }
                    override fun onFailure(reason: Int) { if (c.isActive) c.resume(reason) }
                })
            } catch (e: Exception) {
                if (c.isActive) c.resume(ERROR)
            }
        }

    private suspend fun groupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { c ->
            try {
                mgr.requestGroupInfo(ch) { g -> if (c.isActive) c.resume(g) }
            } catch (e: Exception) {
                if (c.isActive) c.resume(null)
            }
        }

    private suspend fun connectionInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { c ->
            try {
                mgr.requestConnectionInfo(ch) { i -> if (c.isActive) c.resume(i) }
            } catch (e: Exception) {
                if (c.isActive) c.resume(null)
            }
        }

    private fun randomText(n: Int, alphabet: String): String {
        val r = SecureRandom()
        return String(CharArray(n) { alphabet[r.nextInt(alphabet.length)] })
    }

    companion object {
        const val PORT = 48_555
        private const val OK = -1
        private const val ERROR = -2
    }
}
