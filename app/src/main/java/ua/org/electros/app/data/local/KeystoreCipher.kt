package ua.org.electros.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plaintext: String): String
    fun decryptOrNull(encoded: String): String?
}

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher for Android 17+ (API 37).
 * Encodes payloads as Base64([1-byte IV length][IV][ciphertext + 128-bit GCM auth tag]).
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        return try {
            encryptWithKey(getOrCreateSecretKey(), plaintext)
        } catch (_: GeneralSecurityException) {
            deleteKeyEntry()
            encryptWithKey(getOrCreateSecretKey(), plaintext)
        }
    }

    override fun decryptOrNull(encoded: String): String? {
        if (encoded.isBlank()) return null
        return try {
            val raw = Base64.getDecoder().decode(encoded)
            if (raw.size < 2) return null
            val ivLength = raw[0].toInt() and 0xFF
            if (ivLength !in 12..16 || raw.size <= 1 + ivLength + GCM_TAG_BYTES) return null

            val iv = raw.copyOfRange(1, 1 + ivLength)
            val cipherBytes = raw.copyOfRange(1 + ivLength, raw.size)
            val key = getExistingSecretKey() ?: return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptWithKey(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + cipherBytes.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(cipherBytes, 0, packed, 1 + iv.size, cipherBytes.size)
        return Base64.getEncoder().encodeToString(packed)
    }

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getExistingSecretKey(): SecretKey? {
        val ks = openKeyStore()
        return (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    @Synchronized
    private fun deleteKeyEntry() {
        runCatching {
            val ks = openKeyStore()
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        internal const val TRANSFORMATION = "AES/GCM/NoPadding"
        internal const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val DEFAULT_KEY_ALIAS = "electros_auth_aes256_gcm_v1"
    }
}
