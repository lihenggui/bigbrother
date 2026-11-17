package li.mercury.bigbrother

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.ui.BigBrotherApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val userData by viewModel.userData.collectAsStateWithLifecycle()
            val recordingStopped by viewModel.recordingStopped.collectAsStateWithLifecycle()
            // The settings are read within milliseconds; waiting for them
            // avoids a first frame in the wrong colors.
            val settings = userData ?: return@setContent
            BigBrotherTheme(useDynamicColor = settings.useDynamicColor) {
                BigBrotherApp(recordingStopped = recordingStopped)
            }
        }
    }
}
