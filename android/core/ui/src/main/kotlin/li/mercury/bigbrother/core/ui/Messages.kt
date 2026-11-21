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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/** A centered title and explanation, with an optional action, for empty and error states that fill the screen. */
@Composable
fun Message(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    InlineMessage(
        title = title,
        body = body,
        modifier = modifier
            .fillMaxSize()
            // Scrollable so pull-to-refresh works on an otherwise empty screen.
            .verticalScroll(rememberScrollState()),
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

/**
 * A [Message] as tall as its text, for content that already scrolls: a
 * scrolling container inside another one would be given unbounded height,
 * which Compose rejects.
 */
@Composable
fun InlineMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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

/** The title shared by full-screen failures and messages about failed changes. */
@Composable
fun failureTitle(failure: LoadFailure): String = stringResource(
    when (failure) {
        LoadFailure.NoRoot -> R.string.no_root_title
        LoadFailure.ModuleMissing -> R.string.no_module_title
        LoadFailure.ModuleOutdated -> R.string.old_module_title
        is LoadFailure.Other -> R.string.error_title
    },
)

/** Explains a [LoadFailure] and offers to retry. */
@Composable
fun LoadFailureMessage(failure: LoadFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val body = when (failure) {
        LoadFailure.NoRoot -> stringResource(R.string.no_root_body)
        LoadFailure.ModuleMissing -> stringResource(R.string.no_module_body)
        LoadFailure.ModuleOutdated -> stringResource(R.string.old_module_body)
        is LoadFailure.Other -> failure.message
    }
    Message(
        title = failureTitle(failure),
        body = body,
        modifier = modifier,
        actionLabel = stringResource(R.string.retry),
        onAction = onRetry,
    )
}
