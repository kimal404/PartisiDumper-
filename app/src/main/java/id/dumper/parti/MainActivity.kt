package id.dumper.parti

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import id.dumper.parti.ui.screens.about.AboutScreen
import id.dumper.parti.ui.screens.home.HomeScreen
import id.dumper.parti.ui.theme.PartiDumperTheme
import id.dumper.parti.util.LocaleHelper

enum class Screen {
    HOME,
    ABOUT
}

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            PartiDumperTheme {
                var currentScreen by remember { mutableStateOf(Screen.HOME) }

                Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                    when (screen) {
                        Screen.HOME -> {
                            HomeScreen(
                                onNavigateToAbout = { currentScreen = Screen.ABOUT }
                            )
                        }
                        Screen.ABOUT -> {
                            BackHandler {
                                currentScreen = Screen.HOME
                            }
                            AboutScreen(
                                onBackClick = { currentScreen = Screen.HOME }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
            }
        }
    }
}
