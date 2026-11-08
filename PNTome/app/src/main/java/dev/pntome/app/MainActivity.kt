package dev.pntome.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.pntome.app.ui.navigation.PNTomeApp
import dev.pntome.app.ui.theme.PNTomeTheme

/**
 * Compose host activity.
 *
 * Annotated with [AndroidEntryPoint] so Hilt can inject dependencies into this activity and
 * the ViewModels hosted beneath it. Sets up the themed Compose content and the bottom-nav
 * shell ([PNTomeApp]).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PNTomeTheme {
                PNTomeApp()
            }
        }
    }
}
