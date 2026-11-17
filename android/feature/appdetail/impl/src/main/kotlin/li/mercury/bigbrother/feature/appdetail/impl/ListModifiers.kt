package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Padding for a list: the usual margins, and room for the navigation bar. */
@Composable
internal fun listContentPadding(): PaddingValues {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + bottom)
}

/** Animates a list row's appearance, movement and removal with the theme's motion. */
internal fun Modifier.animatedRow(scope: LazyItemScope, motion: MotionScheme): Modifier = with(scope) {
    animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec(),
    )
}
