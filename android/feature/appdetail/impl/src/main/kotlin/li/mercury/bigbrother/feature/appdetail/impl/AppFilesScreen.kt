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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.OpenFileResult
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
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
    val scope = rememberCoroutineScope()
    AppFilesScreen(
        state = uiState,
        uid = uid,
        initialLocation = initialLocation,
        onBack = onBack,
        onRetry = viewModel::retry,
        onFileDetails = viewModel::showFileDetails,
        onDismissFileDetails = viewModel::dismissFileDetails,
        onOpenFile = { path ->
            scope.launch {
                val result = if (viewModel.ensureFileAccess()) openFile(context, path) else OpenFileResult.NO_ACCESS
                val message = when (result) {
                    OpenFileResult.OPENED -> null
                    OpenFileResult.MISSING -> R.string.open_file_missing
                    OpenFileResult.NO_VIEWER -> R.string.open_file_no_viewer
                    OpenFileResult.NO_ACCESS -> R.string.open_file_no_access
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        },
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
    onFileDetails: (path: String) -> Unit,
    onDismissFileDetails: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    state.fileDetails?.let { details ->
        FileDetailsSheet(details, onOpen = { onOpenFile(details.path) }, onDismiss = onDismissFileDetails)
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf(initialLocation ?: Location.ROOT) }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)
    val filtering = searching && query.isNotBlank()
    val shown = remember(state.accesses, query, filtering) {
        val needle = query.trim()
        state.accesses?.filter { !filtering || it.path.contains(needle, ignoreCase = true) }
    }
    val background = MaterialTheme.colorScheme.surfaceContainer
    // Beside the app's page this is a pane of its own, closed rather than left.
    val beside = LocalShowsDetailBesideList.current
    Scaffold(
        containerColor = background,
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.search_paths),
                    onClose = closeSearch,
                )
            } else {
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
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(UiR.string.search))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = background,
                        scrolledContainerColor = background,
                    ),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                state.failure != null -> LoadFailureMessage(state.failure, onRetry)

                shown == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())

                else -> FilesPage(
                    accesses = shown,
                    filtering = filtering,
                    truncated = state.truncated,
                    location = location,
                    // Back goes up a folder, once search is closed.
                    handleBack = !searching,
                    onNavigate = { location = it },
                    onFileDetails = onFileDetails,
                )
            }
        }
    }
}
