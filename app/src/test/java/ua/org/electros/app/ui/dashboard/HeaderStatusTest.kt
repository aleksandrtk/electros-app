package ua.org.electros.app.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeaderStatusTest {

    @Test
    fun chargingForbidden_isNotReportedAsCharging() {
        assertThat(formatHeaderStatus("Заборона заряджання", isOnline = true)).isEqualTo("CHARGING FORBIDDEN")
    }

    @Test
    fun chargeError_isNotReportedAsCharging() {
        assertThat(formatHeaderStatus("Помилка заряджання", isOnline = true)).isEqualTo("CHARGE ERROR")
    }

    @Test
    fun charging_isReportedAsCharging() {
        assertThat(formatHeaderStatus("Заряджання", isOnline = true)).isEqualTo("CHARGING")
    }

    @Test
    fun connectedIdle_andOffline() {
        assertThat(formatHeaderStatus("Підключено. Немає заряду", isOnline = true)).isEqualTo("CONNECTED • IDLE")
        assertThat(formatHeaderStatus("Заряджання", isOnline = false)).isEqualTo("OFFLINE")
    }
}
