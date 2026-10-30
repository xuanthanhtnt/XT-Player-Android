package com.xuanthanhtnt.xtplayer.core

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
 * Passwords, API keys and tokens (the Keychain of the iOS app): AES-GCM with a key that never leaves the
 * Android Keystore. Falls back to plain storage on the rare device whose Keystore is broken, so credentials
 * still work there.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    @Synchronized
    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }.getOrNull()

    fun set(account: String, value: String?) {
        val editor = prefs.edit()
        if (value.isNullOrEmpty()) {
            editor.remove(account)
        } else {
            val sealed = key()?.let { key ->
                runCatching {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                    "e:" + Base64.encodeToString(payload, Base64.NO_WRAP)
                }.getOrNull()
            }
            editor.putString(account, sealed ?: "p:$value")
        }
        editor.apply()
    }

    fun get(account: String): String? {
        val raw = prefs.getString(account, null) ?: return null
        return when {
            raw.startsWith("p:") -> raw.substring(2)
            raw.startsWith("e:") -> runCatching {
                val payload = Base64.decode(raw.substring(2), Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, IV_SIZE))
                String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8)
            }.getOrNull()
            else -> null
        }
    }

    private companion object {
        const val ALIAS = "xtplayer.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
