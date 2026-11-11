package ua.org.electros.app.service

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.entity.ChargeSessionEntity
import ua.org.electros.app.data.local.entity.SessionStatus

class BootReceiverTest {

    @Test
    fun shouldHandleAction_acceptsBootAndPackageReplacedOnly() {
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_BOOT_COMPLETED)).isTrue()
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_MY_PACKAGE_REPLACED)).isTrue()
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_POWER_CONNECTED)).isFalse()
        assertThat(BootReceiver.shouldHandleAction(null)).isFalse()
    }

    @Test
    fun shouldResumeOnBoot_skipsEmptyChargingAndOutageSessions() {
        val emptyCharging = ChargeSessionEntity(
            id = "s-empty",
            targetKwh = 10.0,
            amperageSetting = 16,
            status = SessionStatus.CHARGING,
            startedAt = 1_000L
        )
        val emptyOutage = emptyCharging.copy(id = "s-outage", status = SessionStatus.OUTAGE_DETECTED)
        val activeCharging = emptyCharging.copy(id = "s-active", lastTickAt = 5_000L)
        val stopPending = emptyCharging.copy(id = "s-stop", status = SessionStatus.STOP_PENDING)
        val cancelled = emptyCharging.copy(id = "s-done", status = SessionStatus.CANCELLED)

        assertThat(BootReceiver.shouldResumeOnBoot(emptyCharging)).isFalse()
        assertThat(BootReceiver.shouldResumeOnBoot(emptyOutage)).isFalse()
        assertThat(BootReceiver.shouldResumeOnBoot(activeCharging)).isTrue()
        assertThat(BootReceiver.shouldResumeOnBoot(stopPending)).isTrue()
        assertThat(BootReceiver.shouldResumeOnBoot(cancelled)).isFalse()
    }
}
