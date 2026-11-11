package ua.org.electros.app.service

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BootReceiverTest {

    @Test
    fun shouldHandleAction_acceptsBootAndPackageReplacedOnly() {
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_BOOT_COMPLETED)).isTrue()
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_MY_PACKAGE_REPLACED)).isTrue()
        assertThat(BootReceiver.shouldHandleAction(Intent.ACTION_POWER_CONNECTED)).isFalse()
        assertThat(BootReceiver.shouldHandleAction(null)).isFalse()
    }
}
