package li.mercury.bigbrother.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.LoadFailure

/** A centered title and explanation, with an optional action, for empty and error states. */
@Composable
fun Message(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Scrollable so pull-to-refresh works on an otherwise empty screen.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (body.isNotEmpty()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Explains a [LoadFailure] and offers to retry. */
@Composable
fun LoadFailureMessage(failure: LoadFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val (title, body) = when (failure) {
        LoadFailure.NoRoot ->
            stringResource(R.string.no_root_title) to stringResource(R.string.no_root_body)
        LoadFailure.ModuleMissing ->
            stringResource(R.string.no_module_title) to stringResource(R.string.no_module_body)
        is LoadFailure.Other -> stringResource(R.string.error_title) to failure.message
    }
    Message(
        title = title,
        body = body,
        modifier = modifier,
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
    )
}
