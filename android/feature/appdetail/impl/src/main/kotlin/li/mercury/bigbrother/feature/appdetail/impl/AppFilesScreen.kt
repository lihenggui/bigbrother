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

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.OpenFileResult
import li.mercury.bigbrother.core.ui.PredictiveSearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.openFile
import li.mercury.bigbrother.core.ui.R as UiR

/** One app's files on shared storage and in the media library, browsed as folders. */
@Composable
fun AppFilesScreen(
    uid: Int,
    initialLocation: String?,
    onBack: () -> Unit,
    viewModel: AppFilesViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fileAccessResult = uiState.fileAccessResult
    val failureText = (fileAccessResult as? FileAccessResult.Failed)?.let { failureReason(it.failure) }
    val fileMissing = stringResource(R.string.open_file_missing)
    val noFileViewer = stringResource(R.string.open_file_no_viewer)
    LaunchedEffect(fileAccessResult) {
        val result = when (fileAccessResult) {
            is FileAccessResult.Granted -> openFile(context, fileAccessResult.path)
            is FileAccessResult.Failed -> null
            null -> return@LaunchedEffect
        }
        val message = when (result) {
            OpenFileResult.OPENED -> null
            OpenFileResult.MISSING -> fileMissing
            OpenFileResult.NO_VIEWER -> noFileViewer
            null -> failureText
        }
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.onFileAccessHandled(fileAccessResult)
    }
    AppFilesScreen(
        state = uiState,
        uid = uid,
        initialLocation = initialLocation,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenSearch = viewModel::openSearch,
        onCloseSearch = viewModel::closeSearch,
        onQueryChange = viewModel::search,
        onReadOnly = viewModel::showReadOnly,
        onFileDetails = viewModel::showFileDetails,
        onDismissFileDetails = viewModel::dismissFileDetails,
        onOpenFile = viewModel::ensureFileAccess,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppFilesScreen(
    state: AppFilesUiState,
    uid: Int,
    initialLocation: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onReadOnly: (Boolean) -> Unit,
    onFileDetails: (path: String) -> Unit,
    onDismissFileDetails: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    // The sheet opens once the history is in. Its stops follow its height, so a long history arriving while it
    // opened would carry it to full height and drop it back to half.
    state.fileDetails?.takeIf { it.periods != null || it.failure != null }?.let { details ->
        FileDetailsSheet(details, onOpen = { onOpenFile(details.path) }, onDismiss = onDismissFileDetails)
    }
    var location by rememberSaveable { mutableStateOf(initialLocation ?: Location.ROOT) }
    val background = MaterialTheme.colorScheme.surfaceContainer
    // Beside the app's page this is a pane of its own, closed rather than left.
    val beside = LocalShowsDetailBesideList.current
    Scaffold(
        containerColor = background,
        topBar = {
            AppFilesTopBar(state, uid, background, beside, onBack, onOpenSearch, onCloseSearch, onQueryChange)
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Box(
            Modifier.padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
            ),
        ) {
            FadeThrough(state, kind = { ContentKind.of(it.failure, it.browser) }) { shown ->
                val browser = shown.browser
                when {
                    shown.failure != null -> LoadFailureMessage(
                        shown.failure,
                        onRetry,
                        Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    browser == null -> BigBrotherLoadingIndicator(
                        Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
                    )

                    else -> FilesPage(
                        browser = browser,
                        padding = padding,
                        truncated = shown.truncated,
                        location = location,
                        // Back goes up a folder, once search is closed.
                        handleBack = !shown.searching,
                        onNavigate = { location = it },
                        onReadOnly = onReadOnly,
                        onFileDetails = onFileDetails,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppFilesTopBar(
    state: AppFilesUiState,
    uid: Int,
    background: Color,
    beside: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    PredictiveSearchTopBar(
        searching = state.searching,
        query = state.query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.search_paths),
        onClose = onCloseSearch,
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.files_title), maxLines = 1) },
            subtitle = {
                val app = AppSummary(uid, state.packageName, emptyMap(), 0)
                Text(appDisplayName(app, state.appInfo), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        if (beside) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(if (beside) R.string.close else UiR.string.back),
                    )
                }
            },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(UiR.string.search))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = background,
                scrolledContainerColor = background,
            ),
        )
    }
}
