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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.PackageDetails
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.ltrContent
import java.time.ZoneId

/**
 * What the app info section lists: what the package is, in terms a user
 * can check against the store or the system's app info. The target SDK is
 * kept: an app targeting an old one is exempt from the storage limits.
 */
@Composable
internal fun infoRows(app: AppSummary, details: PackageDetails?): List<Pair<Int, String>> {
    val version = details?.versionName?.let {
        stringResource(R.string.info_version_value, it, details.versionCode)
    }
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(app, details, version, locale, zone, is24Hour) {
        buildList {
            if (app.packageName.isNotEmpty()) add(R.string.info_package to app.packageName)
            if (details != null) {
                add(R.string.info_version to (version ?: details.versionCode.toString()))
                add(R.string.info_target_sdk to details.targetSdk.toString())
                add(R.string.info_first_install to exactDateTime(details.firstInstallTime / MILLIS_PER_SECOND, locale, zone, is24Hour))
                add(R.string.info_last_update to exactDateTime(details.lastUpdateTime / MILLIS_PER_SECOND, locale, zone, is24Hour))
            }
        }
    }
}

/** One fact about the package. Tapping copies its value, as in the system's own app info. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InfoRow(label: Int, value: String, index: Int, count: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val copied = stringResource(R.string.info_copied)
    ListItem(
        onClick = { copy(context, value, copied) },
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = { Text(value, style = LocalTextStyle.current.ltrContent(label == R.string.info_package)) },
    ) {
        Text(stringResource(label))
    }
}

private fun copy(context: Context, value: String, confirmation: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(null, value))
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}

/** The system's app info page for [packageName], where its permissions can be revoked. */
internal fun appSettingsIntent(packageName: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val MILLIS_PER_SECOND = 1000L
