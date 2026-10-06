package com.meshchat.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.meshchat.core.Channel as MeshChannel
import com.meshchat.core.DiscoveryMode
import com.meshchat.core.Fragmenter
import com.meshchat.core.MeshLog
import com.meshchat.core.MeshPacket
import com.meshchat.core.MeshTransport
import com.meshchat.core.NodeIds
import com.meshchat.core.PacketType
import com.meshchat.core.Protocol
import com.meshchat.core.Reassembler
import com.meshchat.core.TransportEvent
import com.meshchat.core.TransportStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real BLE transport: every phone is advertiser + scanner + GATT server + GATT client.
 *
 * Link model
 *  - Scanning finds peers (service UUID filter, manufacturer data = proto|caps|nodeId).
 *  - The peer with the LOWER Node ID opens the GATT connection (client). The higher one waits ~12 s before
 *    trying itself. If both directions end up connected, the link initiated by the lower ID survives.
 *  - One link is bidirectional: client -> peer via WRITE on RX/SYNC/CONTROL; peer -> client via NOTIFY.
 *  - A server-side link is bound to a peer by its first HELLO packet (HELLO is link-local, src = neighbor).
 *  - No Bluetooth pairing/bonding is ever requested: these are plain unauthenticated GATT connections.
 *    Authenticity and secrecy come from the application layer (signatures, E2E encryption).
 *
 * Android limits respected here (see README): <= MAX_LINKS concurrent connections, one GATT operation at a time,
 * one connection attempt at a time, scan start throttling (5 starts / 30 s), MTU negotiation with a 23-byte fallback.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val myId: String,
    private val myCaps: Int,
    private val scope: CoroutineScope,
) : MeshTransport {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 4096)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private fun emit(e: TransportEvent) {
        if (!_events.tryEmit(e)) MeshLog.log("ble: event buffer full, dropped ${e::class.simpleName}")
    }

    private fun blog(msg: String) = MeshLog.log("ble: $msg")

    private val _status = MutableStateFlow(TransportStatus())
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    // ---- state
    @Volatile private var wanted = false
    @Volatile private var radioOn = false
    private val modeFlow = MutableStateFlow(DiscoveryMode.NORMAL)

    private val links = ConcurrentHashMap<String, BleLink>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val addressByPeer = ConcurrentHashMap<String, String>()   // internal only; never shown in UI
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val failUntil = ConcurrentHashMap<String, Long>()
    private val failCount = ConcurrentHashMap<String, Int>()
    private val connectMutex = Mutex()

    private var gattServer: BluetoothGattServer? = null
    private var msgTxChar: BluetoothGattCharacteristic? = null
    private var syncChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private val sessions = ConcurrentHashMap<String, ServerSession>()
    private val notifyMutex = Mutex()
    @Volatile private var notifyDeferred: CompletableDeferred<Boolean>? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanJob: Job? = null
    private var advertiseWatchJob: Job? = null
    private val scanStarts = ArrayDeque<Long>()
    @Volatile private var scanActive = false

    // ------------------------------------------------------------------ MeshTransport

    override fun start() {
        if (wanted) return
        wanted = true
        context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (adapter?.isEnabled == true) startRadio() else _status.update { it.copy(bluetoothOn = false, error = "Bluetooth is off") }
    }

    override fun stop() {
        if (!wanted) return
        wanted = false
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stopRadio()
    }

    override fun linkedPeers(): Set<String> = links.keys.toSet()

    override fun setDiscoveryMode(mode: DiscoveryMode) {
        if (modeFlow.value == mode) return
        modeFlow.value = mode
        if (radioOn) startAdvertising(false)   // advertise interval follows the mode
    }

    override suspend fun send(peerId: String, channel: MeshChannel, data: ByteArray): Boolean {
        val link = links[peerId] ?: return false
        return try {
            link.send(channel, data)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun connectTo(peerId: String) {
        if (!wanted || !radioOn || links.containsKey(peerId) || links.size >= MAX_LINKS) return
        val address = addressByPeer[peerId] ?: return
        val now = SystemClock.elapsedRealtime()
        if ((failUntil[peerId] ?: 0L) > now) return
        val first = firstSeen[peerId] ?: now
        // Tie-break: the lower Node ID initiates. The higher one only steps in after a grace period.
        if (myId > peerId && now - first < HIGHER_ID_DELAY_MS) return
        if (!connecting.add(peerId)) return
        blog("connecting to ${peerId.take(8)} (attempt after ${failCount[peerId] ?: 0} failures)")
        scope.launch {
            try {
                connectMutex.withLock {          // Android handles one outgoing connection attempt at a time most reliably
                    if (!links.containsKey(peerId) && links.size < MAX_LINKS) connectClient(peerId, address)
                }
            } finally {
                connecting.remove(peerId)
            }
        }
    }

    // ------------------------------------------------------------------ radio lifecycle

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    stopRadio()
                    _status.update { it.copy(bluetoothOn = false, error = "Bluetooth is off") }
                }
                BluetoothAdapter.STATE_ON -> if (wanted && !radioOn) startRadio()
            }
        }
    }

    private fun startRadio() {
        if (!BlePermissions.hasRequired(context)) {
            _status.update { it.copy(error = "Bluetooth permissions missing") }
            return
        }
        radioOn = true
        _status.update { it.copy(bluetoothOn = true, error = null) }
        openGattServer()                       // advertising starts from onServiceAdded
        scanJob = scope.launch { modeFlow.collectLatest { runScanProfile(it) } }
        advertiseWatchJob = scope.launch {
            while (true) {
                delay(15_000)
                if (radioOn && !_status.value.advertising) {
                    blog("advertiser was not running, restarting")
                    startAdvertising(false)
                }
                // Link watchdog: HELLOs flow every <= 20 s in both directions, so a long silence means a dead link.
                val now = SystemClock.elapsedRealtime()
                for (l in links.values.toList()) {
                    if (now - l.lastRx > RX_SILENCE_MS) {
                        blog("link ${l.peerId.take(8)} silent for ${(now - l.lastRx) / 1000}s, dropping it")
                        l.close()
                    }
                }
            }
        }
    }

    private fun stopRadio() {
        radioOn = false
        scanJob?.cancel()
        advertiseWatchJob?.cancel()
        stopScan()
        stopAdvertising()
        links.values.toList().forEach { it.close() }
        links.clear()
        connecting.clear()
        sessions.clear()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        _status.update { it.copy(advertising = false, scanning = false, links = 0) }
    }

    // ------------------------------------------------------------------ advertising

    private fun startAdvertising(useScanResponse: Boolean) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            _status.update { it.copy(advertising = false, error = "BLE advertising not supported") }
            return
        }
        stopAdvertising()
        val advMode = when (modeFlow.value) {
            DiscoveryMode.HIGH -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            DiscoveryMode.NORMAL -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            DiscoveryMode.LOW -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Nothing personal: service UUID + proto + caps + node id. 3 (flags) + 18 + 10 = 31 bytes.
        val mfg = Protocol.encodeAdvert(myCaps, myId)
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
        val response = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        if (useScanResponse) response.addManufacturerData(Protocol.COMPANY_ID, mfg) else data.addManufacturerData(Protocol.COMPANY_ID, mfg)

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                blog("advertising started (${if (useScanResponse) "scan-response" else "primary"} data)")
                _status.update { it.copy(advertising = true) }
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
                blog("advertising FAILED code=$errorCode")
                _status.update { it.copy(advertising = false, error = "advertise error $errorCode") }
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !useScanResponse) {
                    main.post { if (radioOn) startAdvertising(true) }   // fallback: manufacturer data in scan response
                }
            }
        }
        advertiseCallback = cb
        try {
            if (useScanResponse) advertiser.startAdvertising(settings, data.build(), response.build(), cb)
            else advertiser.startAdvertising(settings, data.build(), cb)
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising", e)
            _status.update { it.copy(advertising = false, error = "advertise: ${e.message}") }
        }
    }

    private fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: Exception) {
        }
        advertiseCallback = null
        _status.update { it.copy(advertising = false) }
    }

    // ------------------------------------------------------------------ scanning (adaptive)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScan(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleScan)
        override fun onScanFailed(errorCode: Int) {
            scanActive = false
            blog("scan FAILED code=$errorCode")
            _status.update { it.copy(scanning = false, error = "scan failed $errorCode") }
        }
    }

    private fun handleScan(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val (caps, id) = Protocol.decodeAdvert(rec.getManufacturerSpecificData(Protocol.COMPANY_ID)) ?: return
        if (id == myId || !NodeIds.isValid(id)) return
        if (addressByPeer.put(id, r.device.address) == null) blog("scan: found ${id.take(8)} rssi=${r.rssi}")
        firstSeen.putIfAbsent(id, SystemClock.elapsedRealtime())
        emit(TransportEvent.PeerSeen(id, r.rssi, caps))
    }

    /**
     * HIGH   : LOW_LATENCY, continuous            (a chat is open)
     * NORMAL : BALANCED, 10 s on / 5 s off         (app visible)
     * LOW    : LOW_POWER, 6 s on / 24 s off        (background)
     * Starts are counted against Android's limit of 5 scan starts per 30 s.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runScanProfile(mode: DiscoveryMode) {
        val (scanMode, onMs, offMs) = when (mode) {
            DiscoveryMode.HIGH -> Triple(ScanSettings.SCAN_MODE_LOW_LATENCY, 0L, 0L)
            DiscoveryMode.NORMAL -> Triple(ScanSettings.SCAN_MODE_BALANCED, 10_000L, 5_000L)
            DiscoveryMode.LOW -> Triple(ScanSettings.SCAN_MODE_LOW_POWER, 6_000L, 24_000L)
        }
        try {
            while (true) {
                awaitScanBudget()
                startScan(scanMode)
                if (onMs == 0L) awaitCancellation()
                delay(onMs)
                stopScan()
                delay(offMs)
            }
        } finally {
            stopScan()
        }
    }

    private suspend fun awaitScanBudget() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000) scanStarts.removeFirst()
            if (scanStarts.size < 5) {
                scanStarts.addLast(now)
                return
            }
            delay(30_000 - (now - scanStarts.first()) + 100)
        }
    }

    private fun startScan(scanMode: Int) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _status.update { it.copy(scanning = false, error = "BLE scanner unavailable") }
            return
        }
        stopScan()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).setReportDelay(0).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanActive = true
            _status.update { it.copy(scanning = true) }
        } catch (e: Exception) {
            _status.update { it.copy(scanning = false, error = "scan: ${e.message}") }
        }
    }

    private fun stopScan() {
        if (!scanActive) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanActive = false
        _status.update { it.copy(scanning = false) }
    }

    // ------------------------------------------------------------------ link registry

    /** @return false if the link must be rejected (limit reached, or lost the duplicate tie-break). */
    @Synchronized
    private fun registerLink(link: BleLink): Boolean {
        val existing = links[link.peerId]
        if (existing != null && existing !== link) {
            // Both sides connected to each other: keep the link initiated by the lower Node ID (same decision on both phones).
            val keepNew = link.weInitiated == (myId < link.peerId)
            if (!keepNew) return false
            links[link.peerId] = link
            existing.close()
        } else {
            if (links.size >= MAX_LINKS) return false
            links[link.peerId] = link
        }
        blog("link UP ${link.peerId.take(8)} (${if (link.weInitiated) "we connected" else "they connected"}) links=${links.size}")
        failUntil.remove(link.peerId)
        failCount.remove(link.peerId)
        firstSeen.remove(link.peerId)
        _status.update { it.copy(links = links.size) }
        emit(TransportEvent.LinkUp(link.peerId, link.weInitiated))
        return true
    }

    @Synchronized
    private fun unregisterLink(link: BleLink) {
        if (links.remove(link.peerId, link)) {
            blog("link DOWN ${link.peerId.take(8)}")
            // The higher-ID phone must wait again before dialling out, otherwise both phones reconnect at the same moment
            // and the duplicate-link tie-break tears the new link down again.
            firstSeen[link.peerId] = SystemClock.elapsedRealtime()
            _status.update { it.copy(links = links.size) }
            emit(TransportEvent.LinkDown(link.peerId))
        }
    }

    private fun channelOf(uuid: UUID): MeshChannel? = when (uuid) {
        Protocol.CHAR_MSG_RX, Protocol.CHAR_MSG_TX -> MeshChannel.MSG
        Protocol.CHAR_SYNC -> MeshChannel.SYNC
        Protocol.CHAR_CONTROL -> MeshChannel.CONTROL
        else -> null
    }

    // ------------------------------------------------------------------ link base class

    private abstract inner class BleLink(val peerId: String, val weInitiated: Boolean) {
        private val sendMutex = Mutex()
        private val fragmenter = Fragmenter()
        abstract val maxFrame: Int
        protected abstract suspend fun writeFrame(channel: MeshChannel, frame: ByteArray): Boolean
        abstract fun close()

        @Volatile var lastRx: Long = SystemClock.elapsedRealtime()
        @Volatile private var failedSends = 0
        fun touchRx() {
            lastRx = SystemClock.elapsedRealtime()
        }

        /**
         * Whole packets are serialized per link so fragments of different packets never interleave.
         * A link whose writes keep failing is a zombie (the OS still says "connected" but nothing gets through):
         * after [MAX_FAILED_SENDS] consecutive failed packets it is torn down so that a fresh connection is made.
         */
        suspend fun send(channel: MeshChannel, data: ByteArray): Boolean {
            val ok = sendMutex.withLock {
                // Never exceed FRAME_CAP: Android 14+ negotiates MTU 517 on its own, but one attribute value is at most
                // 512 bytes, so a 514-byte write fails. Small packets (text) fit in one frame and hid this bug.
                val frames = fragmenter.split(data, minOf(maxFrame, FRAME_CAP))
                var good = true
                for (f in frames) if (!writeFrame(channel, f)) {
                    good = false
                    break
                }
                good
            }
            if (ok) {
                failedSends = 0
            } else if (++failedSends >= MAX_FAILED_SENDS) {
                blog("link ${peerId.take(8)} dropped: $failedSends consecutive write failures")
                failedSends = 0
                close()
            }
            return ok
        }
    }

    // ------------------------------------------------------------------ GATT client link (we connect out)

    private suspend fun connectClient(peerId: String, address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return
        val link = ClientLink(peerId, device)
        val ok = link.connect()
        if (!ok && links.containsKey(peerId)) {
            blog("connect to ${peerId.take(8)} not needed: they already connected to us")
            link.close()
        } else if (!ok) {
            blog("connect to ${peerId.take(8)} FAILED")
            link.close()
            val n = (failCount[peerId] ?: 0) + 1
            failCount[peerId] = n
            failUntil[peerId] = SystemClock.elapsedRealtime() + minOf(120_000L, 10_000L * n)
        }
    }

    private inner class ClientLink(peerId: String, private val device: BluetoothDevice) : BleLink(peerId, true) {
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var mtu = 23
        @Volatile private var closed = false
        private val ready = CompletableDeferred<Boolean>()
        @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
        private val reassemblers = MeshChannel.entries.associateWith { Reassembler() }

        private var msgRxCh: BluetoothGattCharacteristic? = null
        private var msgTxCh: BluetoothGattCharacteristic? = null
        private var syncCh: BluetoothGattCharacteristic? = null
        private var ctrlCh: BluetoothGattCharacteristic? = null
        private var infoCh: BluetoothGattCharacteristic? = null
        private var step = 0

        override val maxFrame: Int get() = mtu - 3

        suspend fun connect(): Boolean {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            return withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } ?: false
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
            pendingWrite?.complete(false)
            if (!ready.isCompleted) ready.complete(false)
            unregisterLink(this)
        }

        private fun fail(why: String) {
            Log.w(TAG, "client link to $peerId failed: $why")
            blog("client link ${peerId.take(8)} setup failed: $why")
            close()
        }

        /** Setup is a strict chain (GATT allows one operation at a time): CCCD x3, then read Node Info. */
        private fun nextStep() {
            val g = gatt ?: return
            val ok = when (step++) {
                0 -> enableNotify(g, msgTxCh)
                1 -> enableNotify(g, syncCh)
                2 -> enableNotify(g, ctrlCh)
                3 -> infoCh?.let { g.readCharacteristic(it) } ?: false
                else -> true
            }
            if (!ok) fail("setup step ${step - 1}")
        }

        @Suppress("DEPRECATION")
        private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?): Boolean {
            ch ?: return false
            if (!g.setCharacteristicNotification(ch, true)) return false
            val d = ch.getDescriptor(Protocol.CCCD_UUID) ?: return false
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            return g.writeDescriptor(d)
        }

        @Suppress("DEPRECATION")
        override suspend fun writeFrame(channel: MeshChannel, frame: ByteArray): Boolean {
            val g = gatt ?: return false
            val ch = when (channel) {
                MeshChannel.MSG -> msgRxCh
                MeshChannel.SYNC -> syncCh
                MeshChannel.CONTROL -> ctrlCh
            } ?: return false
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT   // with response = link-level ack
            ch.value = frame
            if (!g.writeCharacteristic(ch)) {
                blog("writeCharacteristic refused (busy/invalid) len=${frame.size}")
                pendingWrite = null
                return false
            }
            return withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                blog("gatt client state peer=${peerId.take(8)} status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt = g
                    // A short pause before the MTU request avoids a known race on several vendors' stacks.
                    main.postDelayed({
                        if (!closed && !g.requestMtu(DESIRED_MTU)) g.discoverServices()
                    }, 300)
                } else {
                    close()    // disconnected, or connection error such as status 133
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                blog("mtu=$newMtu status=$status (frames capped at $FRAME_CAP)")
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                if (!g.discoverServices()) fail("discoverServices")
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(Protocol.SERVICE_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    fail("service not found")
                    return
                }
                infoCh = svc.getCharacteristic(Protocol.CHAR_NODE_INFO)
                msgRxCh = svc.getCharacteristic(Protocol.CHAR_MSG_RX)
                msgTxCh = svc.getCharacteristic(Protocol.CHAR_MSG_TX)
                syncCh = svc.getCharacteristic(Protocol.CHAR_SYNC)
                ctrlCh = svc.getCharacteristic(Protocol.CHAR_CONTROL)
                nextStep()
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) nextStep() else fail("descriptor write $status")
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (ch.uuid != Protocol.CHAR_NODE_INFO) return
                val adv = Protocol.decodeAdvert(ch.value)
                if (status != BluetoothGatt.GATT_SUCCESS || adv == null || adv.second != peerId) {
                    fail("node info mismatch")
                    return
                }
                if (registerLink(this@ClientLink)) ready.complete(true) else fail("link rejected")
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) blog("write to ${peerId.take(8)} failed status=$status")
                pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                val frame = ch.value ?: return
                val channel = channelOf(ch.uuid) ?: return
                val packet = reassemblers.getValue(channel).accept(frame) ?: return
                touchRx(); emit(TransportEvent.Frame(peerId, channel, packet))
            }
        }
    }

    // ------------------------------------------------------------------ GATT server (peers connect in)

    private inner class ServerSession(val device: BluetoothDevice) {
        @Volatile var mtu = 23
        val subscribed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val reassemblers = MeshChannel.entries.associateWith { Reassembler() }
        @Volatile var link: ServerLink? = null
    }

    private inner class ServerLink(peerId: String, val session: ServerSession) : BleLink(peerId, false) {
        override val maxFrame: Int get() = session.mtu - 3

        @Suppress("DEPRECATION")
        override suspend fun writeFrame(channel: MeshChannel, frame: ByteArray): Boolean {
            val server = gattServer ?: return false
            val ch = when (channel) {
                MeshChannel.MSG -> msgTxChar
                MeshChannel.SYNC -> syncChar
                MeshChannel.CONTROL -> controlChar
            } ?: return false
            if (!session.subscribed.contains(ch.uuid)) return false
            return notifyMutex.withLock {
                val done = CompletableDeferred<Boolean>()
                notifyDeferred = done
                ch.value = frame
                if (!server.notifyCharacteristicChanged(session.device, ch, false)) {
                    notifyDeferred = null
                    return@withLock false
                }
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false
            }
        }

        override fun close() {
            try {
                gattServer?.cancelConnection(session.device)
            } catch (_: Exception) {
            }
            session.link = null
            unregisterLink(this)
        }
    }

    private fun session(device: BluetoothDevice): ServerSession =
        sessions.getOrPut(device.address) { ServerSession(device) }

    private fun openGattServer() {
        val server = manager.openGattServer(context, serverCallback)
        if (server == null) {
            _status.update { it.copy(error = "GATT server unavailable") }
            return
        }
        gattServer = server

        fun cccd() = BluetoothGattDescriptor(
            Protocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val info = BluetoothGattCharacteristic(
            Protocol.CHAR_NODE_INFO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val rx = BluetoothGattCharacteristic(
            Protocol.CHAR_MSG_RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            Protocol.CHAR_MSG_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0,
        ).also { it.addDescriptor(cccd()) }
        val sync = BluetoothGattCharacteristic(
            Protocol.CHAR_SYNC,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).also { it.addDescriptor(cccd()) }
        val control = BluetoothGattCharacteristic(
            Protocol.CHAR_CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).also { it.addDescriptor(cccd()) }
        service.addCharacteristic(info)
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        service.addCharacteristic(sync)
        service.addCharacteristic(control)
        msgTxChar = tx
        syncChar = sync
        controlChar = control
        if (!server.addService(service)) _status.update { it.copy(error = "addService failed") }
    }

    @Suppress("DEPRECATION")
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == Protocol.SERVICE_UUID) {
                main.post { if (radioOn) startAdvertising(false) }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            blog("gatt server state status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val s = sessions.remove(device.address) ?: return
                s.link?.let { unregisterLink(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            session(device).mtu = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val server = gattServer ?: return
            if (ch.uuid == Protocol.CHAR_NODE_INFO) {
                val value = Protocol.encodeAdvert(myCaps, myId)
                val slice = if (offset <= value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val server = gattServer ?: return
            if (descriptor.uuid == Protocol.CCCD_UUID) {
                val s = session(device)
                val charUuid = descriptor.characteristic.uuid
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) s.subscribed.add(charUuid)
                else s.subscribed.remove(charUuid)
            }
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val server = gattServer ?: return
            val channel = channelOf(ch.uuid)
            if (channel == null || preparedWrite) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val s = session(device)
            val packet = s.reassemblers.getValue(channel).accept(value) ?: return
            onServerPacket(s, channel, packet)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) blog("notify failed status=$status")
            notifyDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
    }

    /**
     * A server-side session is anonymous until the peer's first HELLO (link-local, so its `src` is the neighbor itself).
     * Anything else before that is ignored. Relayed packets carry the ORIGIN as src, so they can never bind a session.
     */
    private fun onServerPacket(s: ServerSession, channel: MeshChannel, data: ByteArray) {
        var link = s.link
        if (link == null) {
            if (data.size < Protocol.HEADER_SIZE || (data[1].toInt() and 0xFF) != PacketType.HELLO.code) return
            val src = MeshPacket.peekSrc(data) ?: return
            if (src == myId || !NodeIds.isValid(src)) return
            link = ServerLink(src, s)
            s.link = link
            if (!registerLink(link)) {
                s.link = null
                try {
                    gattServer?.cancelConnection(s.device)
                } catch (_: Exception) {
                }
                return
            }
        }
        link.touchRx(); emit(TransportEvent.Frame(link.peerId, channel, data))
    }

    private companion object {
        const val TAG = "BleTransport"
        const val MAX_LINKS = 5
        const val DESIRED_MTU = 247
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val HIGHER_ID_DELAY_MS = 12_000L
        const val MAX_FAILED_SENDS = 2
        const val FRAME_CAP = 244              // bytes per GATT write/notify, incl. 4-byte fragment header
        const val RX_SILENCE_MS = 75_000L      // peers send HELLO at least every 20 s
    }
}
