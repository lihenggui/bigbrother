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

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.data.repository.ModuleUpdate
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.Reveal
import li.mercury.bigbrother.core.ui.RevealNotNull
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.failureTitle
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * Status that needs the user, shown only when something is wrong. Cards grow
 * in and out, so that what is below them moves rather than jumps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RecorderStatusCards(state: OverviewUiState, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    // A staged update comes first: starting now would run the old module.
    val pending = state.modulePendingReboot
    val update = state.moduleUpdate
    val updateFailure = (update as? ModuleUpdate.Failed)?.failure
    val stopped = state.recording == false
    val updateShown = !pending && update != ModuleUpdate.Idle &&
        (update == ModuleUpdate.Updating || !stopped || (updateFailure != LoadFailure.Operation.DAEMON_NOT_REPORTING && updateFailure != state.daemonStartError))
    Column {
        Reveal(pending) {
            RecorderStatusCard(
                icon = Icons.Outlined.SystemUpdate,
                title = stringResource(R.string.module_pending_reboot),
                body = stringResource(R.string.module_pending_reboot_body),
                error = false,
            )
        }
        RevealNotNull(update.takeIf { updateShown }) { ModuleUpdateCard(it, onUpdateModule, onStartDaemon) }
        Reveal(!pending && stopped && update != ModuleUpdate.Updating) {
            StoppedCard(state, updateFailure, onStartDaemon)
        }
    }
}

/** The recorder is not running: why, and starting it again where that can help. */
@Composable
private fun StoppedCard(state: OverviewUiState, updateFailure: LoadFailure?, onStartDaemon: () -> Unit) {
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

/** Bringing the module up to date with the app: while it happens, and when it did not work. */
@Composable
internal fun ModuleUpdateCard(update: ModuleUpdate, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    FadeThrough(update, kind = { it }) { shown ->
        when (shown) {
            ModuleUpdate.Idle -> Unit

            ModuleUpdate.Updating -> RecorderStatusCard(
                icon = Icons.Outlined.SystemUpdate,
                title = stringResource(R.string.updating_module),
                body = null,
                error = false,
                busy = true,
            )

            is ModuleUpdate.Failed -> ModuleUpdateFailureCard(shown.failure, onUpdateModule, onStartDaemon)
        }
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
    // A card that turns into a fault, or out of one, changes color gradually.
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(if (error) colors.errorContainer else colors.tertiaryContainer, spec, label = "container")
    val content by animateColorAsState(if (error) colors.onErrorContainer else colors.onTertiaryContainer, spec, label = "content")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                Reveal(busy || action != null) {
                    // Starting shows progress where the button was.
                    FadeThrough(busy to action, kind = { (working, _) -> working }) { (working, label) ->
                        if (working) {
                            LoadingIndicator(Modifier.size(32.dp))
                        } else if (label != null) {
                            StatusAction(label, error, onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusAction(label: String, error: Boolean, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onAction,
        modifier = Modifier.padding(top = 8.dp),
        colors = if (error) {
            ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
        } else {
            ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
        },
    ) {
        Text(label)
    }
}

/**
 * Shown when the module is absent: offers to install the copy bundled in
 * this APK. A module flashed in a root manager but not yet in effect
 * ([pending]) takes over at the next restart; installing now works as well.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModuleSetupMessage(setup: ModuleSetup, pending: Boolean, onInstall: () -> Unit) {
    FadeThrough(setup, kind = { it }) { shown -> ModuleSetupStep(shown, pending, onInstall) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleSetupStep(setup: ModuleSetup, pending: Boolean, onInstall: () -> Unit) {
    when (setup) {
        ModuleSetup.Idle -> Message(
            title = failureTitle(LoadFailure.ModuleMissing),
            body = stringResource(if (pending) R.string.setup_pending_body else R.string.setup_body),
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

        is ModuleSetup.Failed -> Message(
            title = stringResource(R.string.install_failed_title),
            body = failureReason(setup.failure),
            actionLabel = stringResource(UiR.string.retry),
            onAction = onInstall,
        )
    }
}
