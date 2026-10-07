package com.meshchat.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** What a backup restores: the identity key pair (so the old Node ID comes back) and the local profile. */
class IdentityBackupData(
    val privatePkcs8: ByteArray,
    val publicX509: ByteArray,
    val name: String,
    val dobEpochDay: Long,
    val showDob: Boolean,
) {
    val nodeId: String get() = NodeIds.fromPublicKey(publicX509)
}

enum class RestoreError { BAD_FILE, WRONG_PASSPHRASE, CORRUPT }

class RestoreResult(val data: IdentityBackupData?, val error: RestoreError?)

/**
 * Passphrase-protected identity backup, a single JSON file:
 *
 *   { "app":"MeshChat", "version":1, "nodeId":"7F3A92B1", "kdf":"PBKDF2WithHmacSHA256", "iterations":210000,
 *     "salt":"<b64>", "iv":"<b64>", "data":"<b64 AES-256-GCM of the inner JSON>" }
 *
 * The private key is never written in the clear: without the passphrase the file is useless, so it is safe to keep
 * on a cloud drive or another phone. Only the Node ID is readable (to tell backups apart).
 * The inner JSON holds the PKCS#8 private key, the X.509 public key, the name and the date of birth.
 */
object IdentityBackup {
    const val MIN_PASSPHRASE = 6
    private const val ITERATIONS = 210_000
    private val rng = SecureRandom()

    fun export(id: Identity, name: String, dobEpochDay: Long, showDob: Boolean, passphrase: CharArray, iterations: Int = ITERATIONS): String {
        require(passphrase.size >= MIN_PASSPHRASE) { "passphrase too short" }
        val inner = Json.write(
            mapOf(
                "priv" to b64(id.privateBytes), "pub" to b64(id.publicBytes),
                "name" to name, "dob" to dobEpochDay, "showDob" to showDob,
            ),
        )
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(passphrase, salt, iterations), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(id.nodeId.toByteArray())
        val ct = c.doFinal(inner.toByteArray(Charsets.UTF_8))
        return Json.write(
            linkedMapOf(
                "app" to "MeshChat", "version" to 1, "nodeId" to id.nodeId,
                "kdf" to "PBKDF2WithHmacSHA256", "iterations" to iterations,
                "salt" to b64(salt), "iv" to b64(iv), "data" to b64(ct),
            ),
        )
    }

    fun restore(json: String, passphrase: CharArray): RestoreResult {
        val m = Json.read(json) ?: return fail(RestoreError.BAD_FILE)
        if (m["app"] != "MeshChat" || (m["version"] as? Long) != 1L) return fail(RestoreError.BAD_FILE)
        val nodeId = m["nodeId"] as? String ?: return fail(RestoreError.BAD_FILE)
        val iterations = (m["iterations"] as? Long)?.toInt() ?: return fail(RestoreError.BAD_FILE)
        if (iterations !in 1000..2_000_000) return fail(RestoreError.BAD_FILE)
        val salt = unb64(m["salt"]) ?: return fail(RestoreError.BAD_FILE)
        val iv = unb64(m["iv"]) ?: return fail(RestoreError.BAD_FILE)
        val data = unb64(m["data"]) ?: return fail(RestoreError.BAD_FILE)
        if (iv.size != 12) return fail(RestoreError.BAD_FILE)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(passphrase, salt, iterations), "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(nodeId.toByteArray())
            c.doFinal(data)
        } catch (e: Exception) {
            return fail(RestoreError.WRONG_PASSPHRASE)           // GCM tag mismatch: wrong passphrase or tampered file
        }
        val inner = Json.read(String(plain, Charsets.UTF_8)) ?: return fail(RestoreError.CORRUPT)
        val priv = unb64(inner["priv"]) ?: return fail(RestoreError.CORRUPT)
        val pub = unb64(inner["pub"]) ?: return fail(RestoreError.CORRUPT)
        val name = inner["name"] as? String ?: return fail(RestoreError.CORRUPT)
        val dob = inner["dob"] as? Long ?: return fail(RestoreError.CORRUPT)
        val show = inner["showDob"] as? Boolean ?: false
        // The key pair must really be usable and match the Node ID in the header.
        val ok = runCatching { Identity.restore(priv, pub).nodeId == nodeId }.getOrDefault(false)
        if (!ok) return fail(RestoreError.CORRUPT)
        return RestoreResult(IdentityBackupData(priv, pub, name, dob, show), null)
    }

    /** Node ID of a backup file without decrypting it (null if it is not a MeshChat backup). */
    fun peekNodeId(json: String): String? = (Json.read(json)?.get("nodeId") as? String)?.takeIf { NodeIds.isValid(it) }

    private fun fail(e: RestoreError) = RestoreResult(null, e)

    private fun derive(pass: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, iterations, 256)).encoded

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun unb64(v: Any?): ByteArray? = (v as? String)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

/** Minimal JSON for flat objects of strings, integers and booleans (org.json is not available in JVM unit tests). */
object Json {
    fun write(m: Map<String, Any>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in m) {
            if (!first) append(',')
            first = false
            str(k); append(':')
            when (v) {
                is String -> str(v)
                is Boolean -> append(v)
                is Int -> append(v)
                is Long -> append(v)
                else -> throw IllegalArgumentException("unsupported ${v::class}")
            }
        }
        append('}')
    }

    private fun StringBuilder.str(s: String) {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch.code < 0x20 -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    fun read(text: String): Map<String, Any>? = try {
        Parser(text.trim()).obj()
    } catch (e: Exception) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun obj(): Map<String, Any> {
            ws(); expect('{'); ws()
            val m = linkedMapOf<String, Any>()
            if (peek() == '}') { i++; end(); return m }
            while (true) {
                ws()
                val k = string()
                ws(); expect(':'); ws()
                m[k] = value()
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw IllegalStateException("bad json")
                }
            }
            end()
            return m
        }

        private fun value(): Any = when (val c = peek()) {
            '"' -> string()
            't' -> { lit("true"); true }
            'f' -> { lit("false"); false }
            else -> if (c == '-' || c.isDigit()) number() else throw IllegalStateException("bad json")
        }

        private fun number(): Long {
            val st = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(st, i).toLong()
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalStateException("bad escape $e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun lit(w: String) { if (!s.startsWith(w, i)) throw IllegalStateException("bad json"); i += w.length }
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char = if (i < s.length) s[i] else throw IllegalStateException("eof")
        private fun next(): Char = peek().also { i++ }
        private fun expect(c: Char) { if (next() != c) throw IllegalStateException("expected $c") }
        private fun end() { ws(); if (i != s.length) throw IllegalStateException("trailing data") }
    }
}
