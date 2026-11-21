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

package li.mercury.bigbrother.feature.overview.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.data.repository.ModuleUpdate
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.failureBody
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.failureTitle
import li.mercury.bigbrother.core.ui.R as UiR

/** Status that needs the user, shown only when something is wrong. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RecorderStatusCards(state: OverviewUiState, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    // A staged update comes first: starting now would run the old module.
    if (state.modulePendingReboot) {
        RecorderStatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.module_pending_reboot),
            body = null,
            error = false,
        )
        return
    }

    val update = state.moduleUpdate
    val updateFailure = (update as? ModuleUpdate.Failed)?.failure
    val stopped = state.recording == false
    if (update == ModuleUpdate.Updating || !stopped || (updateFailure != LoadFailure.Operation.DAEMON_NOT_REPORTING && updateFailure != state.daemonStartError)) {
        ModuleUpdateCard(update, onUpdateModule, onStartDaemon)
    }
    if (stopped && update != ModuleUpdate.Updating) {
        val error = state.daemonStartError
        RecorderStatusCard(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.status_stopped),
            body = when {
                state.daemonStarting -> stringResource(R.string.daemon_starting)
                error == null && updateFailure == LoadFailure.Operation.DAEMON_NOT_REPORTING -> failureReason(updateFailure)
                error == null -> stringResource(R.string.daemon_stopped)
                else -> failureReason(error)
            },
            error = true,
            // A disabled module is turned back on in the root manager, not here.
            action = stringResource(R.string.start_daemon)
                .takeUnless { state.daemonStarting || error == LoadFailure.Operation.MODULE_DISABLED },
            busy = state.daemonStarting,
            onAction = onStartDaemon,
        )
    }
}

/** Bringing the module up to date with the app: while it happens, and when it did not work. */
@Composable
internal fun ModuleUpdateCard(update: ModuleUpdate, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    when (update) {
        ModuleUpdate.Idle -> Unit

        ModuleUpdate.Updating -> RecorderStatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.updating_module),
            body = null,
            error = false,
            busy = true,
        )

        is ModuleUpdate.Failed -> ModuleUpdateFailureCard(update.failure, onUpdateModule, onStartDaemon)
    }
}

@Composable
private fun ModuleUpdateFailureCard(failure: LoadFailure, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    when (failure) {
        LoadFailure.Operation.MODULE_DISABLED -> RecorderStatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = failureReason(failure),
            body = null,
            error = false,
        )

        LoadFailure.Operation.DAEMON_NOT_REPORTING -> RecorderStatusCard(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.status_stopped),
            body = failureReason(failure),
            error = true,
            action = stringResource(R.string.start_daemon),
            onAction = onStartDaemon,
        )

        else -> RecorderStatusCard(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.update_failed_title),
            body = failureReason(failure),
            error = true,
            action = stringResource(UiR.string.retry),
            onAction = onUpdateModule,
        )
    }
}

/**
 * A persistent notice at the top of the overview. A fault uses the error
 * colors; something that only needs attention, the tertiary ones.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecorderStatusCard(
    icon: ImageVector,
    title: String,
    body: String?,
    error: Boolean,
    action: String? = null,
    busy: Boolean = false,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else colors.tertiaryContainer,
            contentColor = if (error) colors.onErrorContainer else colors.onTertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    LoadingIndicator(Modifier.size(32.dp))
                } else if (action != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.padding(top = 8.dp),
                        colors = if (error) {
                            ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
                        },
                    ) {
                        Text(action)
                    }
                }
            }
        }
    }
}

/** Shown when the module is absent: offers to flash the copy bundled in this APK. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModuleSetupMessage(setup: ModuleSetup, onInstall: () -> Unit, onRetry: () -> Unit) {
    when (setup) {
        ModuleSetup.Idle -> Message(
            title = failureTitle(LoadFailure.ModuleMissing),
            body = failureBody(LoadFailure.ModuleMissing),
            actionLabel = stringResource(R.string.install_module),
            onAction = onInstall,
        )

        ModuleSetup.Installing -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator()
            Text(stringResource(R.string.installing_module))
        }

        ModuleSetup.NeedsReboot -> Message(
            title = stringResource(R.string.reboot_title),
            body = stringResource(R.string.reboot_body),
            actionLabel = stringResource(UiR.string.retry),
            onAction = onRetry,
        )

        is ModuleSetup.Failed -> Message(
            title = stringResource(R.string.install_failed_title),
            body = failureReason(setup.failure),
            actionLabel = stringResource(UiR.string.retry),
            onAction = onInstall,
        )
    }
}
