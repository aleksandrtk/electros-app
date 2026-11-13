package ua.org.electros.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class JvmAesGcmCipher(
        private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    ) : SecretCipher {
        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(AndroidKeystoreCipher.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(1 + iv.size + cipherBytes.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(cipherBytes, 0, packed, 1 + iv.size, cipherBytes.size)
            return Base64.getEncoder().encodeToString(packed)
        }

        override fun decryptOrNull(encoded: String): String? = runCatching {
            val raw = Base64.getDecoder().decode(encoded)
            val ivLen = raw[0].toInt() and 0xFF
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val body = raw.copyOfRange(1 + ivLen, raw.size)
            val cipher = Cipher.getInstance(AndroidKeystoreCipher.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(AndroidKeystoreCipher.GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    @Test
    fun credentials_encryptOnDisk_persistAcrossInstances_andClearCleanly() = runBlocking {
        val file = tempFolder.newFile("auth_test.preferences_pb")
        val cipher = JvmAesGcmCipher()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { file }
            val prefs1 = AuthPreferences(ds1, cipher, scope1)
            assertThat(prefs1.hasValidCredentials()).isFalse()

            prefs1.deviceSerial = "  a1234  "
            prefs1.internetPassword = "  s3cr3t!  "
            prefs1.awaitIdle()

            assertThat(prefs1.deviceSerial).isEqualTo("a1234")
            assertThat(prefs1.internetPassword).isEqualTo("s3cr3t!")
            assertThat(prefs1.hasValidCredentials()).isTrue()

            // Stored DataStore entries must be encrypted ciphertext, never raw plaintext
            val rawPrefs = ds1.data.first()
            assertThat(rawPrefs[AuthPreferences.KEY_ENC_DEVICE_SERIAL]).isNotEqualTo("a1234")
            assertThat(rawPrefs[AuthPreferences.KEY_ENC_INTERNET_PASSWORD]).isNotEqualTo("s3cr3t!")
        } finally {
            scope1.coroutineContext.job.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { file }
            val prefs2 = AuthPreferences(ds2, cipher, scope2)

            assertThat(prefs2.deviceSerial).isEqualTo("a1234")
            assertThat(prefs2.internetPassword).isEqualTo("s3cr3t!")
            assertThat(prefs2.hasValidCredentials()).isTrue()

            prefs2.clearCredentials()
            prefs2.awaitIdle()
            assertThat(prefs2.hasValidCredentials()).isFalse()
            assertThat(prefs2.deviceSerial).isEmpty()
            assertThat(prefs2.internetPassword).isEmpty()
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun keyRotationOrKeystoreReset_gracefullyReturnsEmptyAndAllowsResaving() = runBlocking {
        val file = tempFolder.newFile("auth_keyloss.preferences_pb")
        val oldKeyCipher = JvmAesGcmCipher()
        val newKeyCipher = JvmAesGcmCipher()

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { file }
            val prefs1 = AuthPreferences(ds1, oldKeyCipher, scope1)
            prefs1.deviceSerial = "a9999"
            prefs1.internetPassword = "pass1"
            prefs1.awaitIdle()
        } finally {
            scope1.coroutineContext.job.cancelAndJoin()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { file }
            val recoveredPrefs = AuthPreferences(ds2, newKeyCipher, scope2)

            // Cannot decrypt with rotated key -> returns empty without crashing
            assertThat(recoveredPrefs.hasValidCredentials()).isFalse()
            assertThat(recoveredPrefs.deviceSerial).isEmpty()

            // User can immediately save new credentials under the new key
            recoveredPrefs.deviceSerial = "b7777"
            recoveredPrefs.internetPassword = "pass2"
            recoveredPrefs.awaitIdle()
            assertThat(recoveredPrefs.hasValidCredentials()).isTrue()
            assertThat(recoveredPrefs.deviceSerial).isEqualTo("b7777")
        } finally {
            scope2.coroutineContext.job.cancelAndJoin()
        }
    }
}
