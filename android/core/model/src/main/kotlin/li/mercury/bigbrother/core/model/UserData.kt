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

/** The user's settings, kept across launches. */
data class UserData(
    /** The period the overview and the app list cover. */
    val timeRange: TimeRange = TimeRange.DAY,
    /**
     * Whether apps that are part of the system image are listed. Off by
     * default: system components read shared storage routinely and would
     * dominate every list.
     */
    val showSystemApps: Boolean = false,
    /** Whether colors follow the wallpaper rather than the app's own scheme. */
    val useDynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
