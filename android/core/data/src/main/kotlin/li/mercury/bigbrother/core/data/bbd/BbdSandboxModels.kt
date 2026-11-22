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
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp

internal data class BbdSandboxReleaseResult(val failedCount: Int)

internal data class BbdSandboxGlobal(
    val items: Map<SandboxItem, SandboxMode>,
    val auto: Boolean,
)

internal data class BbdSandboxedApp(
    val uid: Int,
    val packageName: String,
    val enabled: Boolean,
    val created: Long,
    val settings: Map<SandboxItem, SandboxMode>,
    val items: Map<SandboxItem, SandboxMode>,
    val unmet: Map<SandboxItem, Set<SandboxPart>>,
    val appOpsChecking: Boolean,
    val networkRules: Int,
    val recordingSince: Long?,
)

internal fun BbdSandboxedApp.asExternalModel(): SandboxedApp = SandboxedApp(
    uid = uid,
    packageName = packageName,
    enabled = enabled,
    created = created,
    settings = settings,
    items = items,
    unmet = unmet,
    appOpsChecking = appOpsChecking,
    networkRules = networkRules,
    recordingSince = recordingSince,
)

internal data class BbdSandboxStatus(
    val gate: Boolean,
    val appOps: Boolean,
    val androidIdHook: Boolean,
    val appListHook: Boolean,
    val uriGrantsHook: Boolean,
    val adIdHook: Boolean,
    val newAppsPending: Int,
    val newAppsHeld: Boolean,
    val network: Boolean,
    val netRecord: Boolean,
    val dnsNames: Boolean,
    val traffic: Boolean,
    val newAppsFailed: Int = 0,
)

internal fun BbdSandboxStatus.asExternalModel(): SandboxStatus = SandboxStatus(
    gate = gate,
    appOps = appOps,
    androidIdHook = androidIdHook,
    appListHook = appListHook,
    uriGrantsHook = uriGrantsHook,
    adIdHook = adIdHook,
    newAppsPending = newAppsPending,
    newAppsFailed = newAppsFailed,
    newAppsHeld = newAppsHeld,
    network = network,
    netRecord = netRecord,
    dnsNames = dnsNames,
    traffic = traffic,
)
