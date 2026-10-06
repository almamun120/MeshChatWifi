package com.meshchat.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest

/** One file in a transfer. [id] is its index in the manifest. */
class FileMeta(val id: Int, val name: String, val size: Long, val mime: String)

/** Something to send (on Android: a content:// Uri). */
interface FileSource {
    val meta: FileMeta
    fun open(): InputStream
}

/** A file being received. [commit] publishes it (returns a location to show the user), [abort] deletes the partial file. */
interface SinkFile {
    val out: OutputStream
    fun commit(): String?
    fun abort()
}

interface FileSink {
    fun create(meta: FileMeta): SinkFile
}

interface TransferListener {
    fun onManifest(files: List<FileMeta>) {}
    /** [totalDone] = bytes of the whole transfer moved so far. */
    fun onProgress(fileIndex: Int, totalDone: Long) {}
    fun onFileDone(fileIndex: Int, ok: Boolean, location: String?) {}
}

enum class TransferStatus { OK, PARTIAL, FAILED, ABORTED }

class TransferResult(val status: TransferStatus, val fileOk: List<Boolean>)

/** Names come from another phone: never trust them as paths. */
object FileNames {
    private val bad = "<>:\"|?*\\/".toSet()

    fun sanitize(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it.code >= 0x20 && it.code != 0x7F && it !in bad }.trim().trimEnd('.', ' ').trimStart('.')
        if (n.isEmpty()) return "file"
        if (n.length > 120) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
            n = n.substring(0, 120 - ext.length) + ext
        }
        return n
    }
}

object TransferWire {
    const val T_MANIFEST = 1
    const val T_READY = 2
    const val T_FILE_START = 3
    const val T_DATA = 4
    const val T_FILE_END = 5
    const val T_DONE = 6
    const val T_ABORT = 7

    const val CHUNK = 128 * 1024
    const val MAX_FILES = 2000

    fun encodeManifest(files: List<FileMeta>): ByteArray {
        var size = 2
        val names = files.map { it.name.toByteArray(Charsets.UTF_8).let { b -> if (b.size > 240) Payloads.clampUtf8(it.name, 240) else b } }
        val mimes = files.map { Payloads.clampUtf8(it.mime, 100) }
        for (i in files.indices) size += 2 + 8 + 2 + names[i].size + 1 + mimes[i].size
        val b = ByteBuffer.allocate(size).putShort(files.size.toShort())
        for (i in files.indices) {
            b.putShort(files[i].id.toShort()).putLong(files[i].size)
            b.putShort(names[i].size.toShort()).put(names[i])
            b.put(mimes[i].size.toByte()).put(mimes[i])
        }
        return b.array()
    }

    fun decodeManifest(p: ByteArray): List<FileMeta>? {
        return try {
            val b = ByteBuffer.wrap(p)
            val n = b.getShort().toInt() and 0xFFFF
            if (n !in 1..MAX_FILES) return null
            val out = ArrayList<FileMeta>(n)
            for (i in 0 until n) {
                val id = b.getShort().toInt() and 0xFFFF
                val size = b.getLong()
                val nl = b.getShort().toInt() and 0xFFFF
                if (id != i || size < 0 || nl > 300 || b.remaining() < nl + 1) return null
                val name = String(ByteArray(nl).also { b.get(it) }, Charsets.UTF_8)
                val ml = b.get().toInt() and 0xFF
                if (b.remaining() < ml) return null
                val mime = String(ByteArray(ml).also { b.get(it) }, Charsets.UTF_8)
                out += FileMeta(id, FileNames.sanitize(name), size, mime)
            }
            if (b.hasRemaining()) null else out
        } catch (e: Exception) {
            null
        }
    }
}

/** Sends [files] over an authenticated [SecureStream]. Blocking; cancel it by closing the stream. */
class FileSender(private val files: List<FileSource>, private val listener: TransferListener = object : TransferListener {}) {

    fun run(stream: SecureStream): TransferResult {
        val metas = files.map { it.meta }
        val results = BooleanArray(files.size)
        try {
            stream.send(TransferWire.T_MANIFEST, TransferWire.encodeManifest(metas))
            val ready = stream.receive() ?: return fail(results, TransferStatus.ABORTED)
            if (ready.type != TransferWire.T_READY) return fail(results, TransferStatus.ABORTED)

            var done = 0L
            val buf = ByteArray(TransferWire.CHUNK)
            for ((i, f) in files.withIndex()) {
                stream.send(TransferWire.T_FILE_START, ByteBuffer.allocate(2).putShort(i.toShort()).array())
                val md = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                f.open().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (sent + n > f.meta.size) throw IOException("file grew while sending")
                        md.update(buf, 0, n)
                        stream.send(TransferWire.T_DATA, buf, 0, n)
                        sent += n
                        done += n
                        listener.onProgress(i, done)
                    }
                }
                if (sent != f.meta.size) throw IOException("file shrank while sending")
                stream.send(TransferWire.T_FILE_END, ByteBuffer.allocate(2 + 32).putShort(i.toShort()).put(md.digest()).array())
            }
            val doneMsg = stream.receive() ?: return fail(results, TransferStatus.ABORTED)
            if (doneMsg.type != TransferWire.T_DONE || doneMsg.payload.size != files.size) return fail(results, TransferStatus.ABORTED)
            for (i in files.indices) {
                results[i] = doneMsg.payload[i].toInt() == 1
                listener.onFileDone(i, results[i], null)
            }
            val okCount = results.count { it }
            val status = when (okCount) {
                files.size -> TransferStatus.OK
                0 -> TransferStatus.FAILED
                else -> TransferStatus.PARTIAL
            }
            return TransferResult(status, results.toList())
        } catch (e: IOException) {
            return fail(results, TransferStatus.ABORTED)
        }
    }

    private fun fail(r: BooleanArray, s: TransferStatus) = TransferResult(s, r.toList())
}

/** Receives files from an authenticated [SecureStream] into [sink]. Blocking; cancel it by closing the stream. */
class FileReceiver(
    private val sink: FileSink,
    /** What the user accepted on the BLE offer; a manifest bigger than that is refused. */
    private val expected: OfferInfo?,
    private val listener: TransferListener = object : TransferListener {},
) {
    fun run(stream: SecureStream): TransferResult {
        var current: SinkFile? = null
        val ok = ArrayList<Boolean>()
        try {
            val m = stream.receive() ?: return TransferResult(TransferStatus.ABORTED, ok)
            if (m.type != TransferWire.T_MANIFEST) return abort(stream, ok)
            val files = TransferWire.decodeManifest(m.payload) ?: return abort(stream, ok)
            val total = files.sumOf { it.size }
            if (expected != null && (files.size != expected.count || total > expected.totalBytes)) return abort(stream, ok)
            listener.onManifest(files)
            stream.send(TransferWire.T_READY)

            var done = 0L
            for ((i, meta) in files.withIndex()) {
                val start = stream.receive() ?: return TransferResult(TransferStatus.ABORTED, ok)
                if (start.type == TransferWire.T_ABORT) return TransferResult(TransferStatus.ABORTED, ok)
                if (start.type != TransferWire.T_FILE_START || start.payload.size != 2 ||
                    (ByteBuffer.wrap(start.payload).short.toInt() and 0xFFFF) != i
                ) return abort(stream, ok)

                val sf = sink.create(meta)
                current = sf
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                var good = false
                var cut = false
                loop@ while (true) {
                    val msg = stream.receive()
                    if (msg == null) {          // the other side vanished mid-file
                        cut = true
                        break@loop
                    }
                    when (msg.type) {
                        TransferWire.T_DATA -> {
                            got += msg.payload.size
                            if (got > meta.size) break@loop
                            md.update(msg.payload)
                            sf.out.write(msg.payload)
                            done += msg.payload.size
                            listener.onProgress(i, done)
                        }
                        TransferWire.T_FILE_END -> {
                            val hash = msg.payload
                            good = hash.size == 34 && got == meta.size &&
                                MessageDigest.isEqual(md.digest(), hash.copyOfRange(2, 34))
                            break@loop
                        }
                        else -> break@loop
                    }
                }
                if (cut) {
                    current = null
                    sf.abort()
                    return TransferResult(TransferStatus.ABORTED, ok)
                }
                sf.out.flush()
                current = null
                val loc = if (good) sf.commit() else {
                    sf.abort()
                    null
                }
                ok += good
                listener.onFileDone(i, good, loc)
                if (!good && got != meta.size) return abort(stream, ok)      // stream was cut or protocol broken: stop
            }
            stream.send(TransferWire.T_DONE, ByteArray(files.size) { if (ok[it]) 1 else 0 })
            val okCount = ok.count { it }
            val status = when (okCount) {
                files.size -> TransferStatus.OK
                0 -> TransferStatus.FAILED
                else -> TransferStatus.PARTIAL
            }
            return TransferResult(status, ok)
        } catch (e: IOException) {
            runCatching { current?.abort() }
            return TransferResult(TransferStatus.ABORTED, ok)
        }
    }

    private fun abort(stream: SecureStream, ok: List<Boolean>): TransferResult {
        runCatching { stream.send(TransferWire.T_ABORT) }
        return TransferResult(TransferStatus.FAILED, ok)
    }
}

/** What a Wi-Fi session needs to open its secure TCP channel. */
class StreamSession(val callId: ByteArray, val key: ByteArray, val isHost: Boolean)

/** TCP helpers shared by file transfer and the WebRTC signalling channel. */
object TcpSessions {
    fun listen(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        receiveBufferSize = 1 shl 20
        bind(InetSocketAddress(port))
    }

    /** Host side: wait for the guest, wrap the socket. Null on timeout / error. */
    fun accept(server: ServerSocket, s: StreamSession, timeoutMs: Int): SecureStream? = try {
        server.soTimeout = timeoutMs
        wrap(server.accept(), s)
    } catch (e: IOException) {
        null
    }

    /** Guest side: connect to the host (retrying while the Wi-Fi interface comes up). Null on timeout / error. */
    fun connect(host: InetAddress, port: Int, s: StreamSession, timeoutMs: Long): SecureStream? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            try {
                val sock = Socket()
                sock.sendBufferSize = 1 shl 20
                sock.connect(InetSocketAddress(host, port), 2_000)
                return wrap(sock, s)
            } catch (e: IOException) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    private fun wrap(sock: Socket, s: StreamSession): SecureStream {
        sock.tcpNoDelay = false
        sock.keepAlive = true
        return SecureStream(sock.getInputStream().buffered(1 shl 16), sock.getOutputStream().buffered(1 shl 16), s.key, s.callId, s.isHost, Closeable { runCatching { sock.close() } })
    }
}
