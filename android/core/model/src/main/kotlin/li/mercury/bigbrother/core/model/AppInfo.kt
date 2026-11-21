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

/** Display name and icon source of an installed app. */
class AppInfo(
    val label: String,
    val iconSource: AppIconSource,
    /** Part of the system image, as opposed to installed by the user. */
    val isSystem: Boolean,
) {
    val uid: Int get() = iconSource.uid
}

/** Identifies the installation and version from which to load an app's icon. */
data class AppIconSource(
    val packageName: String,
    val uid: Int,
    val lastUpdateTime: Long,
) {
    val userId: Int get() = userIdOf(uid)
}

/** Show the package name when the package manager cannot supply the app label. */
fun AppInfo?.labelOrPackageName(packageName: String): String = this?.label ?: packageName
