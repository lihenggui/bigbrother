package li.mercury.bigbrother.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The Material 3 Expressive loading indicator, centered in the space given. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BigBrotherLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}
