package ua.org.electros.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.org.electros.app.ElectrosApplication
import ua.org.electros.app.ui.dashboard.DashboardScreen
import ua.org.electros.app.ui.dashboard.DashboardViewModel
import ua.org.electros.app.ui.history.HistoryScreen
import ua.org.electros.app.ui.history.HistoryViewModel
import ua.org.electros.app.ui.limiter.LimiterScreen
import ua.org.electros.app.ui.limiter.LimiterViewModel
import ua.org.electros.app.ui.settings.SettingsScreen
import ua.org.electros.app.ui.settings.SettingsViewModel
import ua.org.electros.app.ui.theme.*

enum class NavTab(val title: String) {
    CHARGING("Заряд"),
    LIMITER("Ліміт"),
    HISTORY("Історія"),
    SETTINGS("Опції")
}

@Composable
fun AppNavigation(application: ElectrosApplication) {
    var currentTab by remember { mutableStateOf(NavTab.CHARGING) }

    val dashboardViewModel = remember { DashboardViewModel(application.repository) }
    val limiterViewModel = remember { LimiterViewModel(application.repository, application.authPreferences) }
    val historyViewModel = remember { HistoryViewModel(application.repository) }
    val settingsViewModel = remember { SettingsViewModel(application.authPreferences) }

    Scaffold(
        containerColor = SurfaceDark,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceContainerDark,
                tonalElevation = 0.dp
            ) {
                NavTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Text(
                                text = when (tab) {
                                    NavTab.CHARGING -> "⚡"
                                    NavTab.LIMITER -> "🎯"
                                    NavTab.HISTORY -> "📊"
                                    NavTab.SETTINGS -> "⚙️"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        label = { Text(text = tab.title, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OnPrimaryContainerDark,
                            selectedTextColor = PrimaryNeonGreen,
                            indicatorColor = PrimaryContainerDark,
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
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

