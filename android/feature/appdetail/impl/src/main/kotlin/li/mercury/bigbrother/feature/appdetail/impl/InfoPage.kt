package li.mercury.bigbrother.feature.appdetail.impl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.label

/**
 * The app info tab: what the package is, and a way to act on it in the
 * system's own settings, where its permissions can be revoked.
 */
@Composable
internal fun InfoPage(
    app: AppSummary,
    details: PackageDetails?,
    range: TimeRange,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val rows = buildList {
        if (app.packageName.isNotEmpty()) add(R.string.info_package to app.packageName)
        add(R.string.info_uid to app.uid.toString())
        if (details != null) {
            val version = details.versionName?.let {
                stringResource(R.string.info_version_value, it, details.versionCode)
            } ?: details.versionCode.toString()
            add(R.string.info_version to version)
            add(R.string.info_target_sdk to details.targetSdk.toString())
            add(R.string.info_min_sdk to details.minSdk.toString())
            add(R.string.info_first_install to exactDateTime(details.firstInstallTime / MILLIS_PER_SECOND))
            add(R.string.info_last_update to exactDateTime(details.lastUpdateTime / MILLIS_PER_SECOND))
        }
        add(R.string.info_records to stringResource(range.label))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        // Actions first, as in the system's app info. An installed package
        // can be managed and launched; an isolated process or an
        // uninstalled app cannot.
        if (details != null) {
            item(key = "actions") {
                val launch = remember(app.packageName) {
                    context.packageManager.getLaunchIntentForPackage(app.packageName)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { context.startActivity(appSettingsIntent(app.packageName)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.info_open_settings))
                    }
                    if (launch != null) {
                        OutlinedButton(
                            onClick = { context.startActivity(launch) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.info_launch))
                        }
                    }
                }
            }
        }
        itemsIndexed(rows, key = { _, (label, _) -> label }) { index, (label, value) ->
            val copied = stringResource(R.string.info_copied)
            ListItem(
                // Tapping a row copies its value, as in the system's own app info.
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(null, value))
                    // Android 13 and later confirm a copy themselves.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                },
                shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                colors = ListItemDefaults.segmentedColors(),
                supportingContent = { Text(value) },
            ) {
                Text(stringResource(label))
            }
        }

    }
}

/** The system's app info page for [packageName]. */
private fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val MILLIS_PER_SECOND = 1000L
