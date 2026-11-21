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

package li.mercury.bigbrother.core.model

import android.content.pm.PackageInfo

/**
 * Display name of an installed app, and its package, from which its icon is
 * loaded where it is shown.
 */
class AppInfo(
    val label: String,
    val packageInfo: PackageInfo,
    /** Part of the system image, as opposed to installed by the user. */
    val isSystem: Boolean,
)

/** Show the package name when the package manager cannot supply the app label. */
fun AppInfo?.labelOrPackageName(packageName: String): String = this?.label ?: packageName
