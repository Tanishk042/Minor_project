package org.forestear.node.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal key/value seam so token storage is unit-testable without Android.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Encrypts/decrypts the backend token. Implementations report their [mode] so
 * the UI can state honestly whether encryption is active.
 */
interface TokenCipher {
    val mode: String
    /** Returns an opaque blob, or null when encryption is unavailable. */
    fun encrypt(plaintext: String): String?
    fun decrypt(blob: String): String?
}

/**
 * AES/GCM key held by the Android Keystore (hardware-backed where the device
 * provides it). No key material is stored in SharedPreferences.
 */
class KeystoreTokenCipher : TokenCipher {
    override val mode: String = "android-keystore-aes-gcm"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "forestear_backend_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Plaintext fallback used only when the Keystore path fails. Never silent. */
class PlaintextTokenCipher : TokenCipher {
    override val mode: String = MODE_PLAINTEXT_FALLBACK
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(blob: String): String = blob
}

const val MODE_KEYSTORE = "android-keystore-aes-gcm"
const val MODE_PLAINTEXT_FALLBACK = "plaintext-fallback"

/** SharedPreferences adapter for [KeyValueStore]. */
class PrefsKeyValueStore(
    private val prefs: android.content.SharedPreferences
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * Stores the backend token encrypted when possible.
 *
 * - `get()` migrates a legacy plaintext token on first read.
 * - `set()` writes ciphertext; if encryption fails it falls back to plaintext
 *   and records [MODE_PLAINTEXT_FALLBACK], which the privacy screen surfaces.
 */
class TokenStore(
    private val kv: KeyValueStore,
    private val cipher: TokenCipher
) {
    val storageMode: String
        get() = kv.getString(KEY_MODE) ?: "unset"

    fun get(): String {
        kv.getString(KEY_ENC)?.let { blob ->
            cipher.decrypt(blob)?.let { return it }
            // Ciphertext exists but cannot be read (key invalidated, restore on
            // a new device). Drop it rather than looping on a dead blob.
            kv.remove(KEY_ENC)
        }
        val legacy = kv.getString(KEY_PLAIN)
        if (!legacy.isNullOrBlank()) {
            set(legacy)
            return legacy
        }
        return ""
    }

    fun set(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            kv.remove(KEY_ENC)
            kv.remove(KEY_PLAIN)
            return
        }
        val blob = cipher.encrypt(trimmed)
        if (blob != null) {
            kv.putString(KEY_ENC, blob)
            kv.remove(KEY_PLAIN)
            kv.putString(KEY_MODE, cipher.mode)
        } else {
            kv.putString(KEY_PLAIN, trimmed)
            kv.remove(KEY_ENC)
            kv.putString(KEY_MODE, MODE_PLAINTEXT_FALLBACK)
        }
    }

    /** True when the stored token is encrypted at rest. */
    fun isEncrypted(): Boolean =
        kv.getString(KEY_ENC) != null && storageMode == MODE_KEYSTORE

    companion object {
        const val KEY_ENC = "token_enc"
        const val KEY_PLAIN = "token"
        const val KEY_MODE = "token_storage_mode"
    }
}
