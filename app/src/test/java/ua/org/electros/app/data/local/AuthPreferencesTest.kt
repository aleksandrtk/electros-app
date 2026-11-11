package ua.org.electros.app.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthPreferencesTest {

    @Test
    fun shiftScheduleHour_wrapsAroundMidnight() {
        assertThat(AuthPreferences.shiftScheduleHour("23:00", 1)).isEqualTo("00:00")
        assertThat(AuthPreferences.shiftScheduleHour("00:00", -1)).isEqualTo("23:00")
        assertThat(AuthPreferences.shiftScheduleHour("02:00", 1)).isEqualTo("03:00")
        assertThat(AuthPreferences.shiftScheduleHour("16:00", -1)).isEqualTo("15:00")
        assertThat(AuthPreferences.shiftScheduleHour("bad", 2)).isEqualTo("02:00")
    }
}

