package ua.org.electros.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ua.org.electros.app.ui.navigation.AppNavigation
import ua.org.electros.app.ui.theme.ElectrosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ElectrosApplication
        setContent {
            ElectrosTheme {
                AppNavigation(application = app)
            }
        }
    }
}

