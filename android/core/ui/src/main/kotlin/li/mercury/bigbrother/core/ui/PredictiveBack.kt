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

package li.mercury.bigbrother.core.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Previews a local destination using the system gesture while preserving the committed state. */
@Composable
fun <T : Any> rememberPredictiveBackTransition(current: T, previous: T?, onBack: () -> Unit): Transition<T> {
    val state = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(state, label = "predictive back")
    val scope = rememberCoroutineScope()
    val latestCurrent = rememberUpdatedState(current)

    LaunchedEffect(current) { state.animateTo(current) }

    PredictiveBackHandler(enabled = previous != null) { events ->
        try {
            events.collect { if (previous != null) state.seekTo(it.progress, previous) }
        } catch (cancelled: CancellationException) {
            scope.launch { state.animateTo(latestCurrent.value) }
            throw cancelled
        }
        if (previous != null) onBack()
    }
    return transition
}

/** A modal can reveal the UI underneath without changing its dismissal state during a gesture. */
@Composable
fun rememberPredictiveDismissProgress(onDismiss: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    PredictiveBackHandler { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(0f, settle) }
            throw cancelled
        }
        progress.animateTo(1f, settle)
        onDismiss()
    }
    return progress.asState()
}
