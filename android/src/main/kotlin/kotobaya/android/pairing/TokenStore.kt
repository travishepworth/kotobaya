package kotobaya.android.pairing

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device token slot. The token is encrypted with AES-256-GCM under a non-exportable
 * Android Keystore key; only IV+ciphertext reach the preferences file. The token is never logged.
 * [key] is injectable so tests can run without a Keystore.
 */
class TokenStore(
    private val prefs: SharedPreferences,
    private val key: () -> SecretKey,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("kotobaya-token", Context.MODE_PRIVATE),
        ::keystoreKey,
    )

    var token: String?
        get() {
            val stored = prefs.getString(K_TOKEN, null) ?: return null
            return try {
                val raw = Base64.decode(stored, Base64.NO_WRAP)
                val iv = raw.copyOfRange(0, IV_BYTES)
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
            } catch (_: Exception) {
                // Key lost (e.g. restored to another phone) or data corrupt: treat as unpaired.
                null
            }
        }
        set(v) {
            if (v == null) {
                prefs.edit().remove(K_TOKEN).apply()
                return
            }
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(K_TOKEN, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }

    private companion object {
        const val K_TOKEN = "device_token_enc"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val ALIAS = "kotobaya-device-token"

        fun keystoreKey(): SecretKey {
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
    }
}
