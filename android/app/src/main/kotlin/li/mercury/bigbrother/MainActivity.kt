package li.mercury.bigbrother

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.ui.BigBrotherApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BigBrotherTheme {
                BigBrotherApp()
            }
        }
    }
}
