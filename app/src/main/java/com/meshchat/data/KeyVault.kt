package com.meshchat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.meshchat.core.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the node's identity key pair.
 *
 * The private key (PKCS#8) is encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available) and the ciphertext is stored in app-private SharedPreferences.
 *
 * Reinstall / "clear data" => the preferences are gone => a NEW key pair => a NEW Node ID.
 * That is deliberate: there is no recovery path an attacker could abuse. If you want continuity across
 * devices, add an explicit, user-passphrase-protected export (not implemented in v1).
 */
class KeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mc_identity", Context.MODE_PRIVATE)

    @Synchronized
    fun loadOrCreate(): Identity {
        val pub = prefs.getString(KEY_PUB, null)
        val enc = prefs.getString(KEY_PRIV, null)
        if (pub != null && enc != null) {
            try {
                val priv = decrypt(Base64.decode(enc, Base64.NO_WRAP))
                return Identity.restore(priv, Base64.decode(pub, Base64.NO_WRAP))
            } catch (e: Exception) {
                // Keystore wiped (e.g. lock-screen change on some devices): the old key is unrecoverable.
                Log.w(TAG, "Stored identity unusable, generating a new one", e)
            }
        }
        val id = Identity.generate()
        prefs.edit()
            .putString(KEY_PUB, Base64.encodeToString(id.publicBytes, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(encrypt(id.privateBytes), Base64.NO_WRAP))
            .commit()
        return id
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(data)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val TAG = "KeyVault"
        const val ALIAS = "meshchat_identity_wrap"
        const val KEY_PUB = "pub"
        const val KEY_PRIV = "priv_enc"
    }
}
