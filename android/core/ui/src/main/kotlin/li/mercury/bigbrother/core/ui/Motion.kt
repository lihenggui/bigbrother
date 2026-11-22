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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import li.mercury.bigbrother.core.model.LoadFailure

/** What a screen or a part of it shows: the [FadeThrough] kind of most screens. */
enum class ContentKind {
    Loading,
    Failure,
    Empty,
    Content,
    ;

    companion object {
        /** A screen that shows [failure] if there is one, else the loader until [loaded] is there. */
        fun of(failure: LoadFailure?, loaded: Any?): ContentKind = when {
            failure != null -> Failure
            loaded == null -> Loading
            else -> Content
        }
    }
}

/**
 * Shows [state], fading through to new content when its [kind] changes, such
 * as from loading to loaded: the old content leaves quickly and the new one
 * arrives. A new state of the same kind updates the content in place, so a
 * refresh does not fade the whole screen. A change of size follows the
 * theme's spatial motion.
 *
 * [content] must draw the state it is given, not one captured from outside:
 * the leaving content keeps showing the state it had.
 */
@Composable
fun <S> FadeThrough(
    state: S,
    kind: (S) -> Any?,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "fadeThrough",
    content: @Composable (S) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using
                SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() }
        },
        contentAlignment = contentAlignment,
        label = label,
        contentKey = kind,
    ) { shown -> content(shown) }
}

/**
 * Animates a list item's appearance, movement and removal with the theme's
 * motion. Every keyed item of a list takes it, headers and messages too, so
 * that nothing in the list jumps while the rest moves.
 */
fun Modifier.animatedRow(scope: LazyItemScope, motion: MotionScheme): Modifier = with(scope) {
    animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec(),
    )
}
