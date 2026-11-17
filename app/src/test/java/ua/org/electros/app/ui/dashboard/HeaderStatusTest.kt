package ua.org.electros.app.ui.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.R
import ua.org.electros.app.ui.UiText

class HeaderStatusTest {

    @Test
    fun chargingForbidden_isNotReportedAsCharging() {
        assertThat(formatHeaderStatus("Заборона заряджання", isOnline = true)).isEqualTo(UiText.Res(R.string.status_forbidden))
    }

    @Test
    fun chargeError_isNotReportedAsCharging() {
        assertThat(formatHeaderStatus("Помилка заряджання", isOnline = true)).isEqualTo(UiText.Res(R.string.status_charge_error))
    }

    @Test
    fun charging_isReportedAsCharging() {
        assertThat(formatHeaderStatus("Заряджання", isOnline = true)).isEqualTo(UiText.Res(R.string.status_charging))
    }

    @Test
    fun connectedIdle_andOffline() {
        assertThat(formatHeaderStatus("Підключено. Немає заряду", isOnline = true)).isEqualTo(UiText.Res(R.string.status_idle))
        assertThat(formatHeaderStatus("Заряджання", isOnline = false)).isEqualTo(UiText.Res(R.string.status_offline))
    }

    @Test
    fun unknownStatus_isShownAsIs() {
        assertThat(formatHeaderStatus("Новий стан", isOnline = true)).isEqualTo(UiText.Raw("НОВИЙ СТАН"))
    }
}
