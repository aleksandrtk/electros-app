package ua.org.electros.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ua.org.electros.app.data.local.SettingsStore
import ua.org.electros.app.data.repository.ChargeRepository
import java.time.Clock
import java.time.YearMonth

class HistoryViewModel(
    repository: ChargeRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    private val period = MutableStateFlow(HistoryCalculator.currentMonth(clock))

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.getAllSessions(),
        repository.observeAllSlices(),
        settingsStore.settingsFlow,
        period
    ) { sessions, slices, settings, selected ->
        HistoryCalculator.build(sessions, slices, settings.electricityTariffUah, selected, clock)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryCalculator.build(
            emptyList(), emptyList(), settingsStore.currentSettings().electricityTariffUah, period.value, clock
        )
    )

    fun showMonthView() {
        val current = period.value
        if (current is HistoryPeriod.Year) {
            val now = YearMonth.now(clock)
            period.value = HistoryPeriod.Month(
                if (current.year == now.year) now else YearMonth.of(current.year, 12)
            )
        }
    }

    fun showYearView() {
        val current = period.value
        if (current is HistoryPeriod.Month) period.value = HistoryPeriod.Year(current.month.year)
    }

    fun openMonth(month: YearMonth) {
        period.value = HistoryPeriod.Month(month)
    }

    fun goBack() {
        if (uiState.value.canGoBack) period.value = HistoryCalculator.previous(period.value)
    }

    fun goForward() {
        if (uiState.value.canGoForward) period.value = HistoryCalculator.next(period.value)
    }

    fun setTariff(uahPerKwh: Double) = settingsStore.setElectricityTariff(uahPerKwh)
}
