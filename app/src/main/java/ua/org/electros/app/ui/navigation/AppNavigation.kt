package ua.org.electros.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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

enum class NavTab(val title: String, val iconRes: Int) {
    CHARGING("Заряд", R.drawable.ic_nav_bolt),
    LIMITER("Ліміт", R.drawable.ic_nav_tune),
    HISTORY("Історія", R.drawable.ic_nav_bar_chart),
    SETTINGS("Налаштування", R.drawable.ic_nav_settings)
}

@Composable
fun AppNavigation(application: ElectrosApplication) {
    val otaViewModel = remember { OtaViewModel() }
    val showUpdateDialog by otaViewModel.showUpdateDialog.collectAsState()

    LaunchedEffect(Unit) {
        otaViewModel.checkForUpdates(manual = false)
    }

    if (showUpdateDialog) {
        UpdateDialog(viewModel = otaViewModel)
    }

    var isLoggedIn by remember {
        mutableStateOf(application.authPreferences.hasValidCredentials())
    }

    if (!isLoggedIn) {
        val loginViewModel = remember {
            LoginViewModel(application.repository, application.authPreferences)
        }
        LoginScreen(
            viewModel = loginViewModel,
            onLoginSuccess = { isLoggedIn = true }
        )
    } else {
        var currentTab by remember { mutableStateOf(NavTab.CHARGING) }

        val dashboardViewModel = remember {
            DashboardViewModel(application.repository, application.authPreferences)
        }
        val limiterViewModel = remember {
            LimiterViewModel(application.repository, application.authPreferences)
        }
        val historyViewModel = remember {
            HistoryViewModel(application.repository)
        }
        val settingsViewModel = remember {
            SettingsViewModel(application.authPreferences)
        }

        Scaffold(
            containerColor = StudioChassisDark,
            bottomBar = {
                NavigationBar(
                    containerColor = StudioPanelDark,
                    tonalElevation = 0.dp
                ) {
                    NavTab.values().forEach { tab ->
                        val isSelected = currentTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    painter = painterResource(id = tab.iconRes),
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text(text = tab.title, style = MaterialTheme.typography.labelSmall) },
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
            when (currentTab) {
                NavTab.CHARGING -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                NavTab.LIMITER -> LimiterScreen(
                    viewModel = limiterViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                NavTab.HISTORY -> HistoryScreen(
                    viewModel = historyViewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                NavTab.SETTINGS -> SettingsScreen(
                    viewModel = settingsViewModel,
                    otaViewModel = otaViewModel,
                    onLogout = { isLoggedIn = false },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
