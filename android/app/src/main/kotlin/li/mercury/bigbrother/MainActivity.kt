/*
 * Copyright (C) 2026 Mercury Li
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package li.mercury.bigbrother

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.ui.BigBrotherApp
import li.mercury.bigbrother.ui.FirstLaunchContent
import li.mercury.bigbrother.ui.SplashHandoff

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        viewModel.dismissGuide()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value == MainActivityUiState.Loading }
        super.onCreate(savedInstanceState)
        val splash = handOffSplashScreen(splashScreen, savedInstanceState)
        // Activity uses the first style again when the device configuration changes.
        val barStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { resources ->
            val mode = (viewModel.uiState.value as? MainActivityUiState.Ready)?.themeMode ?: ThemeMode.SYSTEM
            val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            mode.isDark(systemDark)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            when (val state = uiState) {
                MainActivityUiState.Loading -> Unit

                is MainActivityUiState.Ready -> {
                    val darkTheme = state.themeMode.isDark(isSystemInDarkTheme())
                    LaunchedEffect(darkTheme) {
                        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                    }
                    BigBrotherTheme(useDynamicColor = state.useDynamicColor, darkTheme = darkTheme) {
                        FirstLaunchContent(showGuide = state.showGuide, onDismiss = viewModel::dismissGuide, splash = splash) {
                            BigBrotherApp(recordingStopped = state.recordingStopped)
                        }
                    }
                }
            }
        }
    }

    /**
     * The system asks when the activity resumes whether it takes over the splash screen's exit, before the guide is
     * decided. Only a launch that may show the guide takes it over, so that every other launch keeps the system's exit.
     */
    private fun handOffSplashScreen(splashScreen: SplashScreen, savedInstanceState: Bundle?): SplashHandoff? {
        if (savedInstanceState != null || !viewModel.mayShowGuide) return null
        val handoff = SplashHandoff()
        splashScreen.setOnExitAnimationListener { provider ->
            val state = viewModel.uiState.value
            handoff.take(provider, guideShown = state is MainActivityUiState.Ready && state.showGuide)
        }
        return handoff
    }
}

private fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
