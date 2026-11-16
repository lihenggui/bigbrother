package li.mercury.bigbrother.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary

/** The name to show for an app: its label, else its package, else a generic name. */
@Composable
fun appDisplayName(app: AppSummary, info: AppInfo?): String = when {
    info != null -> info.label
    app.packageName.isNotEmpty() -> app.packageName
    else -> stringResource(R.string.isolated_process)
}

/** App icon, or a tonal tile with the name's first letter when there is none. */
@Composable
fun AppIcon(info: AppInfo?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val icon = info?.icon
    if (icon != null) {
        val bitmap = remember(icon) { icon.asImageBitmap() }
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
