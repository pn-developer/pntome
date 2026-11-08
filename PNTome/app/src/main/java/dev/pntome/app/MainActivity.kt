package dev.gsound.a

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.gsound.a.ui.navigation.GsoundApp
import dev.gsound.a.ui.theme.GsoundATheme

/**
 * Compose host activity.
 *
 * Annotated with [AndroidEntryPoint] so Hilt can inject dependencies into this activity and
 * the ViewModels hosted beneath it. Sets up the themed Compose content and the bottom-nav
 * shell ([GsoundApp]).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GsoundATheme {
                GsoundApp()
            }
        }
    }
}
