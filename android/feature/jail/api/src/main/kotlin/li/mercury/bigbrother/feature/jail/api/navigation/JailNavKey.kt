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

package li.mercury.bigbrother.feature.jail.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.mercury.bigbrother.core.navigation.Navigator

/** The jail: the apps put in the sandbox, and adding more. */
@Serializable
data object JailNavKey : NavKey

/** What the sandbox keeps from one app in the jail. */
@Serializable
data class JailAppNavKey(
    val uid: Int,
    val packageName: String,
) : NavKey

/** The global settings of the jail. */
@Serializable
data object JailSettingsNavKey : NavKey

/** Where an app in the jail connected to, and what is blocked for it. */
@Serializable
data class JailNetworkNavKey(
    val uid: Int,
    val packageName: String,
) : NavKey

/**
 * One destination of an app in the jail.
 *
 * @param target the domain or address, as the daemon writes it (`domain:example.com`, `ip:203.0.113.7`).
 * @param rangeHours the time range of the activity it was opened from, as `TimeRange.hours`.
 */
@Serializable
data class JailDestinationNavKey(
    val uid: Int,
    val packageName: String,
    val target: String,
    val rangeHours: Int,
) : NavKey

/** Opens an app's network activity above its page. */
fun Navigator.navigateToJailNetwork(uid: Int, packageName: String) {
    state.currentSubStack.removeAll { it is JailNetworkNavKey || it is JailDestinationNavKey }
    navigate(JailNetworkNavKey(uid, packageName))
}

/** Opens a destination beside, or over, the network activity, in place of one opened before. */
fun Navigator.navigateToJailDestination(uid: Int, packageName: String, target: String, rangeHours: Int) {
    state.currentSubStack.removeAll { it is JailDestinationNavKey }
    navigate(JailDestinationNavKey(uid, packageName, target, rangeHours))
}

/**
 * Opens an app in the jail in place of everything opened beside the list,
 * the records of an app opened from there included: back then leads to
 * the list, not to an app chosen before.
 */
fun Navigator.navigateToJailApp(uid: Int, packageName: String) {
    state.currentSubStack.removeAll { it != JailNavKey }
    navigate(JailAppNavKey(uid, packageName))
}

/**
 * Closes an app's page once its app is no longer in the jail. Only that
 * page goes: back could take away a screen opened in the meantime.
 */
fun Navigator.closeJailApp(uid: Int, packageName: String) {
    state.subStacks[JailNavKey]?.remove(JailAppNavKey(uid, packageName))
}

/** Opens the global settings, in place of everything opened beside the list. */
fun Navigator.navigateToJailSettings() {
    state.currentSubStack.removeAll { it != JailNavKey }
    navigate(JailSettingsNavKey)
}
