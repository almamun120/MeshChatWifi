package com.meshchat.core

import java.security.MessageDigest

/** Hex helpers (upper-case, no separators). */
object Hex {
    private val chars = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = chars[v ushr 4]
            out[i * 2 + 1] = chars[v and 0x0F]
        }
        return String(out)
    }

    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}

/**
 * Node ID = first 4 bytes of SHA-256(public key X.509 bytes), as 8 hex chars.
 * It never depends on a Bluetooth MAC, an IP address or GPS.
 */
object NodeIds {
    const val BROADCAST = "FFFFFFFF"

    fun fromPublicKey(x509: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(x509)
        return Hex.encode(h.copyOf(4))
    }

    fun toBytes(id: String): ByteArray = Hex.decode(id)

    fun isValid(id: String): Boolean = id.length == 8 && id.all { Character.digit(it, 16) >= 0 }

    /** "7F3A92B1" -> "MC-7F3A92B1" */
    fun display(id: String): String = "MC-$id"
}
