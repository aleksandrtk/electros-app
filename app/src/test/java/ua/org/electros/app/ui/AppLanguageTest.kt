package ua.org.electros.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLanguageTest {

    @Test
    fun ukrainianOrRussianPhone_startsInUkrainian() {
        assertThat(AppLanguage.defaultFor("uk")).isEqualTo(AppLanguage.UKRAINIAN)
        assertThat(AppLanguage.defaultFor("ru")).isEqualTo(AppLanguage.UKRAINIAN)
    }

    @Test
    fun anyOtherPhoneLanguage_startsInEnglish() {
        assertThat(AppLanguage.defaultFor("zh")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.defaultFor("en")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.defaultFor("")).isEqualTo(AppLanguage.ENGLISH)
    }
}
