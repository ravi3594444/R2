package ai.wakey.android.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys encrypted at rest with an AES-256-GCM key that never leaves the Android Keystore.
 *
 * Values are never logged. Callers read them only at the moment a request is built, so
 * providers can later be pointed at a backend token service without changing call sites.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("wakey_secrets", Context.MODE_PRIVATE)

    fun get(kind: SecretKind): String? {
        val blob = prefs.getString(kind.prefKey, null) ?: return null
        return try {
            decrypt(blob)
        } catch (_: Exception) {
            // Key invalidated (e.g. Keystore reset). Treat as missing rather than crash.
            prefs.edit().remove(kind.prefKey).apply()
            null
        }
    }

    fun has(kind: SecretKind): Boolean = !get(kind).isNullOrBlank()

    fun set(kind: SecretKind, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(kind.prefKey).apply()
        } else {
            prefs.edit().putString(kind.prefKey, encrypt(trimmed)).apply()
        }
    }

    /** Last four characters, for showing which key is saved without revealing it. */
    fun hint(kind: SecretKind): String? = get(kind)?.takeIf { it.length >= 8 }?.let { "••••" + it.takeLast(4) }

    /** Copies build-time seed keys (debug builds only) into encrypted storage once. */
    fun seedIfEmpty(kind: SecretKind, seed: String) {
        val seededFlag = "seeded_" + kind.prefKey
        if (seed.isBlank() || prefs.getBoolean(seededFlag, false)) return
        if (!has(kind)) set(kind, seed)
        prefs.edit().putBoolean(seededFlag, true).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (ivPart, bodyPart) = blob.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(bodyPart, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "wakey_api_keys_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
