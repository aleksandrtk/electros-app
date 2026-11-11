package ua.org.electros.app.data.local

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.GeneralSecurityException

class AuthPreferencesTest {

    @Test
    fun shiftScheduleHour_wrapsAroundMidnight() {
        assertThat(AuthPreferences.shiftScheduleHour("23:00", 1)).isEqualTo("00:00")
        assertThat(AuthPreferences.shiftScheduleHour("00:00", -1)).isEqualTo("23:00")
        assertThat(AuthPreferences.shiftScheduleHour("02:00", 1)).isEqualTo("03:00")
        assertThat(AuthPreferences.shiftScheduleHour("16:00", -1)).isEqualTo("15:00")
        assertThat(AuthPreferences.shiftScheduleHour("bad", 2)).isEqualTo("02:00")
    }

    @Test
    fun openOrRecover_deletesLegacyPlaintextAndRecreatesEncryptedOnKeystoreLoss() {
        val deleted = mutableListOf<String>()
        var attempts = 0
        val dummyPrefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, _, _ -> null } as SharedPreferences

        val result = EncryptedPrefsFactory.openOrRecover(
            fileName = "auth_prefs",
            legacyPlaintextNames = listOf("auth_prefs_fallback"),
            deletePrefs = { name -> deleted += name; true },
            openEncrypted = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("Keystore key missing after restore")
                dummyPrefs
            }
        )

        assertThat(result).isSameInstanceAs(dummyPrefs)
        assertThat(attempts).isEqualTo(2)
        assertThat(deleted).containsExactly("auth_prefs_fallback", "auth_prefs").inOrder()
    }

    @Test
    fun openOrRecover_propagatesFailureIfRecreateAlsoFails() {
        assertThrows(GeneralSecurityException::class.java) {
            EncryptedPrefsFactory.openOrRecover(
                fileName = "auth_prefs",
                legacyPlaintextNames = emptyList(),
                deletePrefs = { true },
                openEncrypted = { throw GeneralSecurityException("Hardware keystore unavailable") }
            )
        }
    }
}

