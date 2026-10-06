package com.meshchat.core

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureStreamException(msg: String) : IOException(msg)

/**
 * Authenticated, encrypted message stream over any ordered byte stream (a TCP socket on the Wi-Fi Direct link).
 *
 * Wire frame: length(4, of the ciphertext) | AES-256-GCM( type(1) | payload ).
 * The nonce is never sent: it is direction(1) 000 counter(8), and each side counts frames itself. So a replayed,
 * dropped, reordered or modified frame fails authentication, and the two directions can never reuse a nonce.
 * Successfully decrypting the very first frame proves the peer holds the session key, i.e. that it is the person the
 * BLE signalling was exchanged with (the key comes from the ECDH of both identities).
 */
class SecureStream(
    input: InputStream,
    output: OutputStream,
    private val key: ByteArray,
    private val callId: ByteArray,
    private val isHost: Boolean,
    private val onClose: Closeable? = null,
) : Closeable {

    class Message(val type: Int, val payload: ByteArray)

    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)
    private val sendLock = Any()
    private var sendCounter = 0L
    private var recvCounter = 0L
    @Volatile private var closed = false

    private fun nonce(senderIsHost: Boolean, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(if (senderIsHost) 1 else 2).put(0).put(0).put(0).putLong(counter).array()

    fun send(type: Int, payload: ByteArray = EMPTY, off: Int = 0, len: Int = payload.size) {
        require(type in 0..255 && len <= MAX_PAYLOAD)
        synchronized(sendLock) {
            val plain = ByteArray(1 + len)
            plain[0] = type.toByte()
            System.arraycopy(payload, off, plain, 1, len)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(isHost, sendCounter++)))
            c.updateAAD(callId)
            val ct = c.doFinal(plain)
            dout.writeInt(ct.size)
            dout.write(ct)
            dout.flush()
        }
    }

    /** Next message, or null when the other side closed cleanly. Throws on a bad/forged frame or a broken link. */
    fun receive(): Message? {
        val n = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (n < 17 || n > MAX_PAYLOAD + 17) throw SecureStreamException("bad frame length")
        val ct = ByteArray(n)
        din.readFully(ct)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(!isHost, recvCounter++)))
            c.updateAAD(callId)
            c.doFinal(ct)
        } catch (e: Exception) {
            throw SecureStreamException("authentication failed")
        }
        return Message(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { onClose?.close() }
    }

    companion object {
        const val MAX_PAYLOAD = 1 shl 20
        private val EMPTY = ByteArray(0)
    }
}
