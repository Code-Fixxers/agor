package live.agor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import live.agor.app.ui.AgorRootScreen
import live.agor.app.ui.theme.AgorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AgorApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AgorTheme {
                    AgorRootScreen()
                }
            }
        }
    }
}
