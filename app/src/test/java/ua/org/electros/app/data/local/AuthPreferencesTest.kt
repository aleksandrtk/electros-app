package ua.org.electros.app.data.local

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.GeneralSecurityException

class AuthPreferencesTest {

    @Test
    fun openOrRecover_deletesLegacyPlaintextAndRecreatesEncryptedOnKeystoreLoss() {
        val deleted = mutableListOf<String>()
        var masterKeyDeleted = 0
        var attempts = 0
        val dummyPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, _, _ -> null } as SharedPreferences

        val result = EncryptedPrefsFactory.openOrRecover(
            fileName = "auth_prefs",
            legacyPlaintextNames = listOf("auth_prefs_fallback"),
            deletePrefs = { name -> deleted += name; true },
            deleteMasterKey = { masterKeyDeleted++ },
            openEncrypted = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("Keystore key missing after restore")
                dummyPrefs
            }
        )

        assertThat(result).isSameInstanceAs(dummyPrefs)
        assertThat(attempts).isEqualTo(2)
        assertThat(masterKeyDeleted).isEqualTo(0)
        assertThat(deleted).containsExactly("auth_prefs_fallback", "auth_prefs").inOrder()
    }

    @Test
    fun openOrRecover_deletesMasterKeyAndRecoversWhenPrefsDeleteRetryStillFails() {
        val steps = mutableListOf<String>()
        var attempts = 0
        val dummyPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, _, _ -> null } as SharedPreferences

        val result = EncryptedPrefsFactory.openOrRecover(
            fileName = "auth_prefs",
            legacyPlaintextNames = emptyList(),
            deletePrefs = { name -> steps += "prefs:$name"; true },
            deleteMasterKey = { steps += "master_key" },
            openEncrypted = {
                attempts++
                if (attempts <= 2) throw GeneralSecurityException("Corrupted keystore entry")
                dummyPrefs
            }
        )

        assertThat(result).isSameInstanceAs(dummyPrefs)
        assertThat(attempts).isEqualTo(3)
        assertThat(steps).containsExactly("prefs:auth_prefs", "master_key", "prefs:auth_prefs").inOrder()
    }

    @Test
    fun openOrRecover_propagatesFailureIfRecreateAlsoFails() {
        var masterKeyDeleted = 0
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedPrefsFactory.openOrRecover(
                fileName = "auth_prefs",
                legacyPlaintextNames = emptyList(),
                deletePrefs = { true },
                deleteMasterKey = { masterKeyDeleted++ },
                openEncrypted = { throw GeneralSecurityException("Hardware keystore unavailable") }
            )
        }
        assertThat(masterKeyDeleted).isEqualTo(1)
    }
}
