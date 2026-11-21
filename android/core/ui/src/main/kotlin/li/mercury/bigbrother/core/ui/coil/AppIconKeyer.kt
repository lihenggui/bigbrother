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

package li.mercury.bigbrother.core.ui.coil

import coil3.key.Keyer
import coil3.request.Options
import li.mercury.bigbrother.core.model.AppIconSource

/**
 * Caches an app's icon by its package, user and last update, so that an
 * update that changes the icon is not shown with the old one.
 */
class AppIconKeyer : Keyer<AppIconSource> {
    override fun key(data: AppIconSource, options: Options): String = "app-icon:${data.packageName}:${data.userId}:${data.lastUpdateTime}"
}
