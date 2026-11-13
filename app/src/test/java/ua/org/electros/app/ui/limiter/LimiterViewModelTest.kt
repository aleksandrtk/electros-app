package ua.org.electros.app.ui.limiter

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ua.org.electros.app.data.local.AppSettings

class LimiterViewModelTest {

    @Test
    fun clampLowThreshold_enforcesMinimumHysteresisBelowHigh() {
        // High is 205V -> low cannot exceed 195V
        assertThat(AppSettings.clampLowThreshold(requestedLow = 200, currentHigh = 205))
            .isEqualTo(195)
        // High is 198V -> low cannot exceed 188V
        assertThat(AppSettings.clampLowThreshold(requestedLow = 195, currentHigh = 198))
            .isEqualTo(188)
        // Lower bound still respected
        assertThat(AppSettings.clampLowThreshold(requestedLow = 150, currentHigh = 205))
            .isEqualTo(160)
    }

    @Test
    fun clampHighThreshold_enforcesMinimumHysteresisAboveLow() {
        // Low is 195V -> high cannot drop below 205V
        assertThat(AppSettings.clampHighThreshold(requestedHigh = 198, currentLow = 195))
            .isEqualTo(205)
        // Low is 180V -> minHigh floor of 195V applies
        assertThat(AppSettings.clampHighThreshold(requestedHigh = 190, currentLow = 180))
            .isEqualTo(195)
        // Upper bound still respected
        assertThat(AppSettings.clampHighThreshold(requestedHigh = 240, currentLow = 195))
            .isEqualTo(230)
    }

    @Test
    fun normalizeThresholds_repairsInvertedStoredThresholds() {
        val (low, high) = AppSettings.normalizeThresholds(rawLow = 200, rawHigh = 195)
        assertThat(high - low).isAtLeast(AppSettings.MIN_HYSTERESIS_V)
        assertThat(low).isIn(160..200)
        assertThat(high).isIn(195..230)
    }
}

