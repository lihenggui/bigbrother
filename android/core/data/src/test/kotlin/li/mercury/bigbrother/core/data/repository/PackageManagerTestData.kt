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

package li.mercury.bigbrother.core.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

internal fun testPackage(
    packageName: String,
    uid: Int,
    label: String,
    flags: Int = 0,
): PackageInfo = PackageInfo().apply {
    this.packageName = packageName
    applicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        this.uid = uid
        this.flags = flags
        nonLocalizedLabel = label
        targetSdkVersion = 35
        minSdkVersion = 28
    }
}
