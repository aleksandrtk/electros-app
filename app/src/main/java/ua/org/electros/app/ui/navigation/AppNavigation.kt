package ua.org.electros.app.ui.navigation

import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.org.electros.app.ElectrosApplication
import ua.org.electros.app.R
import ua.org.electros.app.ota.OtaViewModel
import ua.org.electros.app.ui.dashboard.DashboardScreen
import ua.org.electros.app.ui.dashboard.DashboardViewModel
import ua.org.electros.app.ui.history.HistoryScreen
import ua.org.electros.app.ui.history.HistoryViewModel
import ua.org.electros.app.ui.limiter.LimiterScreen
import ua.org.electros.app.ui.limiter.LimiterViewModel
import ua.org.electros.app.ui.login.LoginScreen
import ua.org.electros.app.ui.login.LoginViewModel
import ua.org.electros.app.ui.ota.UpdateDialog
import ua.org.electros.app.ui.settings.SettingsScreen
import ua.org.electros.app.ui.settings.SettingsViewModel
import ua.org.electros.app.ui.theme.*

enum class NavTab(@androidx.annotation.StringRes val titleRes: Int, val iconRes: Int) {
    CHARGING(R.string.tab_charge, R.drawable.ic_nav_bolt),
    LIMITER(R.string.tab_limits, R.drawable.ic_nav_tune),
    HISTORY(R.string.tab_history, R.drawable.ic_nav_bar_chart),
    SETTINGS(R.string.tab_settings, R.drawable.ic_nav_settings)
}

@Composable
fun AppNavigation(application: ElectrosApplication) {
    val isWood by remember(application) {
        application.settingsStore.settingsFlow.map { it.isWoodTheme }
    }.collectAsState(initial = application.settingsStore.currentSettings().isWoodTheme)
    // One composition shape for both looks, so switching keeps the open tab and other state.
    MaterialTheme(
        colorScheme = if (isWood) WoodColorScheme else MaterialTheme.colorScheme,
        typography = MaterialTheme.typography
    ) {
        CompositionLocalProvider(LocalScreenPalette provides if (isWood) WoodScreenPalette else ClassicScreenPalette) {
            AppContent(application, isWood)
        }
    }
}

@Composable
private fun AppContent(application: ElectrosApplication, isWood: Boolean) {
    val factory = remember(application) { AppViewModelFactory(application) }
    val otaViewModel: OtaViewModel = viewModel(factory = factory)
    val showUpdateDialog by otaViewModel.showUpdateDialog.collectAsState()

    LaunchedEffect(Unit) {
        otaViewModel.checkForUpdates(manual = false)
    }

    if (showUpdateDialog) {
        UpdateDialog(viewModel = otaViewModel)
    }

    var isLoggedIn by rememberSaveable {
        mutableStateOf(application.authPreferences.hasValidCredentials())
    }

    if (!isLoggedIn) {
        val loginViewModel: LoginViewModel = viewModel(factory = factory)
        LaunchedEffect(Unit) {
            loginViewModel.reloadFromPreferences()
        }
        LoginScreen(
            viewModel = loginViewModel,
            onLoginSuccess = { isLoggedIn = true }
        )
    } else {
        var currentTab by rememberSaveable { mutableStateOf(NavTab.CHARGING) }
        BackHandler(enabled = currentTab != NavTab.CHARGING) {
            currentTab = NavTab.CHARGING
        }

        val dashboardViewModel: DashboardViewModel = viewModel(factory = factory)
        val limiterViewModel: LimiterViewModel = viewModel(factory = factory)
        val historyViewModel: HistoryViewModel = viewModel(factory = factory)
        val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

        Scaffold(
            // In the wood looks the walnut runs under the floating dock as well.
            containerColor = if (isWood) Color.Transparent else StudioChassisDark,
            modifier = if (isWood) Modifier.screenBackground() else Modifier,
            bottomBar = {
                if (isWood) {
                    WoodNavBar(current = currentTab, onSelect = { currentTab = it })
                } else NavigationBar(
                    containerColor = StudioPanelDark,
                    tonalElevation = 0.dp
                ) {
                    NavTab.entries.forEach { tab ->
                        val isSelected = currentTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    painter = painterResource(id = tab.iconRes),
                                    contentDescription = stringResource(tab.titleRes),
                                    modifier = Modifier.size(26.dp)
                                )
                            },
                            label = { Text(text = stringResource(tab.titleRes), style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = AmberNixie,
                                indicatorColor = AmberNixie,
                                unselectedIconColor = TextSecondaryDark,
                                unselectedTextColor = TextSecondaryDark
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).then(if (isWood) Modifier.fadeIntoDock() else Modifier)) {
            when (currentTab) {
                NavTab.CHARGING -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    modifier = Modifier
                )
                NavTab.LIMITER -> LimiterScreen(
                    viewModel = limiterViewModel,
                    modifier = Modifier
                )
                NavTab.HISTORY -> HistoryScreen(
                    viewModel = historyViewModel,
                    modifier = Modifier
                )
                NavTab.SETTINGS -> SettingsScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier,
                    otaViewModel = otaViewModel,
                    onLogout = { isLoggedIn = false }
                )
            }
            }
        }
    }
}
