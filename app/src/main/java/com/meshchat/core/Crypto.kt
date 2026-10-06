package com.meshchat.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A node's long-term identity: one P-256 key pair used for ECDSA signatures and ECDH key agreement.
 * Node ID is derived from the public key, so it is persistent as long as the key is.
 */
class Identity(val privateKey: PrivateKey, val publicKey: PublicKey) {
    val publicBytes: ByteArray = publicKey.encoded            // X.509 SubjectPublicKeyInfo (91 bytes)
    val privateBytes: ByteArray get() = privateKey.encoded    // PKCS#8
    val nodeId: String = NodeIds.fromPublicKey(publicBytes)

    companion object {
        fun generate(): Identity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp: KeyPair = kpg.generateKeyPair()
            return Identity(kp.private, kp.public)
        }

        fun restore(privatePkcs8: ByteArray, publicX509: ByteArray): Identity {
            val kf = KeyFactory.getInstance("EC")
            return Identity(
                kf.generatePrivate(PKCS8EncodedKeySpec(privatePkcs8)),
                kf.generatePublic(X509EncodedKeySpec(publicX509)),
            )
        }
    }
}

/**
 * Crypto primitives.
 *
 * Private chat: static-static ECDH(P-256) -> HKDF-SHA256 -> AES-256-GCM, AAD = src|dst|msgId.
 * Relays only ever see ciphertext. NOTE: static keys give no forward secrecy (documented limitation).
 */
object CryptoService {
    private val rng = SecureRandom()
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    fun sign(id: Identity, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(id.privateKey)
        s.update(data)
        return s.sign()
    }

    fun verify(publicX509: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicX509))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub)
        s.update(data)
        s.verify(sig)
    } catch (e: Exception) {
        false
    }

    fun aad(src: String, dst: String, msgId: ByteArray): ByteArray =
        NodeIds.toBytes(src) + NodeIds.toBytes(dst) + msgId

    fun encrypt(me: Identity, peerPublicX509: ByteArray, peerId: String, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = sessionKey(me, peerPublicX509, peerId)
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plaintext)
    }

    /** Returns plaintext, or null if authentication fails (wrong key / tampering). */
    fun decrypt(me: Identity, peerPublicX509: ByteArray, peerId: String, aad: ByteArray, blob: ByteArray): ByteArray? {
        if (blob.size < NONCE_LEN + TAG_BITS / 8) return null
        return try {
            val key = sessionKey(me, peerPublicX509, peerId)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, NONCE_LEN)),
            )
            c.updateAAD(aad)
            c.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 32-byte AES key for ONE Wi-Fi session (a call or a file transfer). Same static-static ECDH as private chat, but the
     * HKDF info also contains the session id, so every session gets a fresh, independent key. Both sides derive the same
     * key; it never travels over any radio. It authenticates the peer on the Wi-Fi link (see SecureStream).
     */
    fun callKey(me: Identity, peerPublicX509: ByteArray, peerId: String, callId: ByteArray): ByteArray {
        val peerPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicX509))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(me.privateKey)
        ka.doPhase(peerPub, true)
        val secret = ka.generateSecret()
        val ids = listOf(me.nodeId, peerId).sorted()
        val info = ("meshchat-v1-session|${ids[0]}|${ids[1]}|").toByteArray(Charsets.UTF_8) + callId
        return hkdf(secret, "meshchat-session-salt-v1".toByteArray(Charsets.UTF_8), info)
    }

    private fun sessionKey(me: Identity, peerPublicX509: ByteArray, peerId: String): ByteArray {
        val peerPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicX509))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(me.privateKey)
        ka.doPhase(peerPub, true)
        val secret = ka.generateSecret()
        val ids = listOf(me.nodeId, peerId).sorted()
        val info = "meshchat-v1-e2e|${ids[0]}|${ids[1]}".toByteArray(Charsets.UTF_8)
        return hkdf(secret, "meshchat-salt-v1".toByteArray(Charsets.UTF_8), info)
    }

    /** HKDF-SHA256, single output block (32 bytes). */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(0x01))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }
}
