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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.Pop
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * Everything one app accessed, on one page: its files first, then each kind
 * of private data, then what the app is. Rows open the detail: the file
 * browser, or the record of one kind of data.
 */
@Composable
fun AppDetailScreen(
    focusCategory: String?,
    openRecord: String?,
    onBack: () -> Unit,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
    onOpenJail: (packageName: String) -> Unit,
    viewModel: AppDetailViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val action = uiState.jailAction
    val openJail by rememberUpdatedState(onOpenJail)
    LaunchedEffect(action) {
        if (action is JailAction.Open) {
            viewModel.dismissJailAction()
            openJail(action.packageName)
        }
    }
    AppDetailScreen(
        state = uiState,
        focusCategory = focusCategory,
        openRecord = openRecord,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenFiles = onOpenFiles,
        onOpenRecord = onOpenRecord,
        onRequestJail = viewModel::requestJail,
        onConfirmIsolation = viewModel::confirmIsolation,
        onDismissJailAction = viewModel::dismissJailAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppDetailScreen(
    state: AppDetailUiState,
    focusCategory: String?,
    openRecord: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
    onRequestJail: () -> Unit,
    onConfirmIsolation: () -> Unit,
    onDismissJailAction: () -> Unit,
) {
    val name = appDisplayName(state.app, state.appInfo)
    JailActionDialog(state.jailAction, name, onConfirmIsolation, onDismissJailAction)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Grouped list items are surface-colored and sit on a tonal background,
    // which is what separates the groups.
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            AppDetailTopBar(state, name, background, scrollBehavior, onBack, onRequestJail)
        },
    ) { padding ->
        FadeThrough(state, kind = { ContentKind.of(it.failure, it.records) }) { shown ->
            val records = shown.records
            when {
                shown.failure != null -> LoadFailureMessage(shown.failure, onRetry, Modifier.padding(padding))

                records == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

                else -> AppDetailList(
                    records = records,
                    state = shown,
                    padding = padding,
                    focusCategory = focusCategory,
                    openRecord = openRecord,
                    onOpenFiles = onOpenFiles,
                    onOpenRecord = onOpenRecord,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppDetailTopBar(
    state: AppDetailUiState,
    name: String,
    background: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onRequestJail: () -> Unit,
) {
    val context = LocalContext.current
    val app = state.app
    MediumFlexibleTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(state.appInfo, name, size = 40.dp)
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        // The records cover the range chosen on the list; it is
        // stated here so the numbers below read correctly.
        subtitle = {
            val range = stringResource(state.range.label)
            Text(
                text = if (app.packageName.isEmpty()) {
                    range
                } else {
                    stringResource(R.string.header_subtitle, app.packageName, range)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // Beside the list, the list itself is the way back.
        navigationIcon = {
            if (!LocalShowsDetailBesideList.current) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(UiR.string.back),
                    )
                }
            }
        },
        actions = {
            // Both depend on what loads after the page opens.
            Pop(state.canOpenJail) {
                IconButton(onClick = onRequestJail, enabled = state.jailAction == null) {
                    Icon(CageIcons.Outlined, contentDescription = stringResource(R.string.open_jail))
                }
            }
            Pop(state.packageDetails != null) {
                IconButton(onClick = { context.startActivity(appSettingsIntent(app.packageName)) }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.info_open_settings),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = background,
            scrolledContainerColor = background,
        ),
        scrollBehavior = scrollBehavior,
    )
}
