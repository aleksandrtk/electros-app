package ua.org.electros.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ua.org.electros.app.ElectrosApplication
import ua.org.electros.app.ota.OtaViewModel
import ua.org.electros.app.ui.dashboard.DashboardViewModel
import ua.org.electros.app.ui.history.HistoryViewModel
import ua.org.electros.app.ui.limiter.LimiterViewModel
import ua.org.electros.app.ui.login.LoginViewModel
import ua.org.electros.app.ui.settings.SettingsViewModel

class AppViewModelFactory(
    private val app: ElectrosApplication
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        DashboardViewModel::class.java -> DashboardViewModel(app.repository, app.authPreferences)
        LimiterViewModel::class.java -> LimiterViewModel(app.repository, app.authPreferences)
        HistoryViewModel::class.java -> HistoryViewModel(app.repository)
        SettingsViewModel::class.java -> SettingsViewModel(app.authPreferences, app.repository)
        LoginViewModel::class.java -> LoginViewModel(app.repository, app.authPreferences)
        OtaViewModel::class.java -> OtaViewModel()
        else -> error("Unknown ViewModel class: ${modelClass.name}")
    } as T
}

