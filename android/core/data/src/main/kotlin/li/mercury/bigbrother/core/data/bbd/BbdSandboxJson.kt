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

package li.mercury.bigbrother.core.data.bbd

import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import org.json.JSONObject

internal fun JSONObject.toBbdSandboxGlobal(): BbdSandboxGlobal = BbdSandboxGlobal(
    items = getJSONObject("items").toModes(),
    auto = getBoolean("auto"),
)

internal fun JSONObject.toBbdSandboxStatus(): BbdSandboxStatus {
    val hook = getJSONObject("hook")
    return BbdSandboxStatus(
        gate = getBoolean("gate"),
        appOps = getBoolean("appops"),
        androidIdHook = hook.getBoolean("android_id"),
        appListHook = hook.getBoolean("app_list"),
        uriGrantsHook = hook.getBoolean("uri_grants"),
        adIdHook = hook.getBoolean("ad_id"),
        newAppsPending = getInt("new_apps_pending"),
        newAppsHeld = getBoolean("new_apps_held"),
        network = getBoolean("network"),
        netRecord = getBoolean("net_record"),
        dnsNames = getBoolean("dns_names"),
        traffic = getBoolean("traffic"),
    )
}

internal fun JSONObject.toBbdSandboxedApp(): BbdSandboxedApp = BbdSandboxedApp(
    uid = getInt("uid"),
    packageName = getString("package"),
    enabled = getBoolean("enabled"),
    created = getLong("created"),
    settings = getJSONObject("settings").toModes(),
    items = getJSONObject("items").toModes(),
    unmet = optJSONObject("unmet")?.toParts().orEmpty(),
    appOpsChecking = optBoolean("appops_checking"),
    networkRules = getInt("network_rules"),
    recordingSince = if (isNull("recording_since")) null else getLong("recording_since"),
)

/** Items and the parts of each not in place; what this build doesn't know is left out. */
private fun JSONObject.toParts(): Map<SandboxItem, Set<SandboxPart>> = keys().asSequence().mapNotNull { key ->
    val item = SandboxItem.fromId(key) ?: return@mapNotNull null
    val names = getJSONArray(key)
    item to List(names.length()) { names.getString(it) }.mapNotNullTo(mutableSetOf(), SandboxPart::fromId)
}.toMap()

/** Items and modes by name; what this build doesn't know is left out. */
private fun JSONObject.toModes(): Map<SandboxItem, SandboxMode> = keys().asSequence().mapNotNull { key ->
    val item = SandboxItem.fromId(key) ?: return@mapNotNull null
    val mode = SandboxMode.fromId(optString(key)) ?: return@mapNotNull null
    item to mode
}.toMap()
