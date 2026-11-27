package ua.org.electros.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCurrentStatusTest {
    // Defaults: lowering below 190 V; stages 193 V -> 8 A, 198 V -> 10 A, 203 V -> max 12 A.
    private fun status(voltage: Int?, amps: Int?, charging: Boolean = true, enabled: Boolean = true, online: Boolean = true, high: Int = 203) =
        resolveAutoCurrentStatus(enabled, online, voltage, charging, amps, lowThreshold = 190, highThreshold = high, maxAmperage = 12)

    @Test fun `station undervoltage cut-off shows low even above the threshold`() =
        assertEquals(
            AutoCurrentStatus.Low(193),
            resolveAutoCurrentStatus(true, true, 193, false, 10, 190, 203, 12, stationLowVoltageStop = true)
        )

    @Test fun `off when disabled`() = assertEquals(AutoCurrentStatus.Off, status(230, 12, enabled = false))
    @Test fun `no data without voltage`() = assertEquals(AutoCurrentStatus.NoData, status(null, 12))
    @Test fun `no data when offline`() = assertEquals(AutoCurrentStatus.NoData, status(230, 12, online = false))
    @Test fun `low below threshold`() = assertEquals(AutoCurrentStatus.Low(185), status(185, 6))

    @Test fun `held below the first stage waits for 193 V`() =
        assertEquals(AutoCurrentStatus.Waiting(193), status(191, 6))

    @Test fun `at 8 A in the first stage waits for 198 V`() =
        assertEquals(AutoCurrentStatus.Waiting(198), status(195, 8))

    @Test fun `at 10 A in the second stage waits for 203 V`() =
        assertEquals(AutoCurrentStatus.Waiting(203), status(200, 10))

    @Test fun `below the stage ceiling is recovering, not waiting`() {
        assertEquals(AutoCurrentStatus.Ok, status(194, 6))
        assertEquals(AutoCurrentStatus.Ok, status(199, 8))
        assertEquals(AutoCurrentStatus.Ok, status(204, 10))
    }

    @Test fun `ok at full current`() = assertEquals(AutoCurrentStatus.Ok, status(191, 12))
    @Test fun `ok when not charging`() = assertEquals(AutoCurrentStatus.Ok, status(191, 6, charging = false))

    @Test fun `stages follow the restore threshold`() =
        assertEquals(AutoCurrentStatus.Waiting(200), status(197, 8, high = 205))
}
