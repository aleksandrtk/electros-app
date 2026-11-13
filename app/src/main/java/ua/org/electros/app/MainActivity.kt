package ua.org.electros.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ua.org.electros.app.ui.navigation.AppNavigation
import ua.org.electros.app.ui.theme.ElectrosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val app = application as ElectrosApplication
        setContent {
            ElectrosTheme {
                AppNavigation(application = app)
            }
        }
    }
}

