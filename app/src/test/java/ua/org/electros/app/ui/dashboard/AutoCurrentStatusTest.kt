package ua.org.electros.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCurrentStatusTest {
    private fun status(voltage: Int?, amps: Int?, charging: Boolean = true, enabled: Boolean = true, online: Boolean = true, high: Int = 205) =
        resolveAutoCurrentStatus(enabled, online, voltage, charging, amps, lowThreshold = 190, highThreshold = high, maxAmperage = 12)

    @Test fun `off when disabled`() = assertEquals(AutoCurrentStatus.Off, status(230, 12, enabled = false))
    @Test fun `no data without voltage`() = assertEquals(AutoCurrentStatus.NoData, status(null, 12))
    @Test fun `no data when offline`() = assertEquals(AutoCurrentStatus.NoData, status(230, 12, online = false))
    @Test fun `low below threshold`() = assertEquals(AutoCurrentStatus.Low(185), status(185, 6))

    @Test fun `held in the deadband waits for the first recovery stage`() =
        assertEquals(AutoCurrentStatus.Waiting(200), status(195, 6))

    @Test fun `at 10 A between the stages waits for the high threshold`() =
        assertEquals(AutoCurrentStatus.Waiting(205), status(202, 10))

    @Test fun `below 10 A after the first stage is recovering, not waiting`() =
        assertEquals(AutoCurrentStatus.Ok, status(202, 8))

    @Test fun `ok at full current in the deadband`() = assertEquals(AutoCurrentStatus.Ok, status(195, 12))
    @Test fun `ok when not charging`() = assertEquals(AutoCurrentStatus.Ok, status(195, 6, charging = false))
    @Test fun `ok above the high threshold`() = assertEquals(AutoCurrentStatus.Ok, status(210, 6))

    @Test fun `restore at 200 V starts the first stage at 195 V`() =
        assertEquals(AutoCurrentStatus.Waiting(195), status(193, 6, high = 200))

    @Test fun `too narrow a gap leaves only the high threshold`() =
        assertEquals(AutoCurrentStatus.Waiting(195), status(192, 6, high = 195))
}
