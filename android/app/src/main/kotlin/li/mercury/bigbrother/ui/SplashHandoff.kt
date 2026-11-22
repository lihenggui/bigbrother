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

package li.mercury.bigbrother.ui

import android.window.SplashScreenView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.core.splashscreen.SplashScreenViewProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The system splash screen's icon, handed to the first-launch guide, whose opening starts where the icon was. */
@Stable
internal class SplashHandoff {
    private var iconBounds: Rect? by mutableStateOf(null)
    private var exited by mutableStateOf(false)

    /** Takes the splash screen's place at once; the guide draws the icon at the same bounds on its next frame. */
    fun take(provider: SplashScreenViewProvider, guideShown: Boolean) {
        val icon = (provider.view as? SplashScreenView)?.iconView
        if (guideShown && icon != null && icon.width > 0) {
            val location = IntArray(2)
            icon.getLocationInWindow(location)
            iconBounds = Rect(location[0].toFloat(), location[1].toFloat(), location[0] + icon.width.toFloat(), location[1] + icon.height.toFloat())
        }
        exited = true
        provider.remove()
    }

    /** The icon's bounds in the window, in pixels, or null when the splash screen ended without one. */
    suspend fun awaitIconBounds(): Rect? = withTimeoutOrNull(EXIT_TIMEOUT_MS) {
        snapshotFlow { exited }.first { it }
        iconBounds
    }

    /** The bounds once known, for drawing. */
    fun iconBoundsOrNull(): Rect? = iconBounds

    private companion object {
        // The splash screen exits on the guide's first frame; past this, it is not coming.
        const val EXIT_TIMEOUT_MS = 1_000L
    }
}
