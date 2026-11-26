package no.lionflow.app.data

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
 * Small encrypted key/value store. Values are encrypted with AES-256-GCM using a key
 * that lives in the Android Keystore (hardware-backed where the phone supports it),
 * so tokens, the OTP state and the security code never sit on disk in plain text.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("lf_secure", Context.MODE_PRIVATE)
    private val alias = "lionflow_master_key"

    /** Which account's keys are used. "" is the first account (keys kept as before); others are stored as "<id>@<name>". */
    @Volatile var profile: String = ""
    private fun k(name: String, prof: String = profile) = if (prof.isEmpty()) name else "$prof@$name"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * [now] writes to disk before returning. For values that can't be had again if the app is killed a moment later:
     * the carmaker hands out a new refresh token at every renewal and the old one dies at once.
     */
    @Synchronized
    fun put(name: String, value: String?, prof: String = profile, now: Boolean = false) {
        val e = prefs.edit()
        if (value == null) {
            e.remove(k(name, prof))
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val enc = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + enc.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(enc, 0, out, iv.size, enc.size)
            e.putString(k(name, prof), Base64.encodeToString(out, Base64.NO_WRAP))
        }
        if (now) e.commit() else e.apply()
    }

    @Synchronized
    fun get(name: String, prof: String = profile): String? {
        val raw = prefs.getString(k(name, prof), null) ?: return null
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val body = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        // Only the active account's keys.
        val e = prefs.edit()
        prefs.all.keys.forEach { key ->
            val mine = if (profile.isEmpty()) !key.contains("@") else key.startsWith("$profile@")
            if (mine) e.remove(key)
        }
        e.apply()
    }
}
