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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.DEFAULT
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.bbd
import li.mercury.bigbrother.core.data.root.parseBbdOutput
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The jail: the apps put in the sandbox, what it keeps from each, the global
 * settings, and whether newly installed apps are put in. The daemon keeps all
 * of it; every change goes through it, and what it then reports is what
 * [apps], [global] and [autoIsolate] hold.
 *
 * A change throws only if the daemon did not make it. If the lists cannot be
 * read again afterwards, they stay as they were until the next [refresh].
 */
interface SandboxRepository {
    /** The apps in the sandbox, and those that were and kept their settings; null until read. */
    val apps: StateFlow<List<SandboxedApp>?>

    /** Every item's mode for an app without its own; null until read. */
    val global: StateFlow<Map<SandboxItem, SandboxMode>?>

    /** Whether apps installed from now on are isolated, with the global settings; null until read. */
    val autoIsolate: StateFlow<Boolean?>

    /** Reads [apps], [global] and [autoIsolate] again. */
    suspend fun refresh()

    suspend fun getStatus(): SandboxStatus

    /** Puts an app, with every package that shares its uid, in the sandbox. */
    suspend fun isolate(packageName: String, userId: Int)

    /** Takes an app out of the sandbox; it keeps its settings. */
    suspend fun release(packageName: String, userId: Int)

    /** Takes every app out of the sandbox; returns how many are in it still. */
    suspend fun releaseAll(): Int

    /** Sets items of an app to a mode, or with null back to the global setting. */
    suspend fun setItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>)

    suspend fun setGlobal(changes: Map<SandboxItem, SandboxMode>)

    /** Turns the isolation of newly installed apps on or off; turning it on leaves installed apps as they are. */
    suspend fun setAutoIsolate(on: Boolean)
}

/** Runs `bbd sandbox ... --json` as root. */
@Singleton
internal class RootSandboxRepository @Inject constructor(
    private val rootShell: RootShell,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : SandboxRepository {
    private val _apps = MutableStateFlow<List<SandboxedApp>?>(null)
    override val apps: StateFlow<List<SandboxedApp>?> = _apps.asStateFlow()

    private val _global = MutableStateFlow<Map<SandboxItem, SandboxMode>?>(null)
    override val global: StateFlow<Map<SandboxItem, SandboxMode>?> = _global.asStateFlow()

    private val _autoIsolate = MutableStateFlow<Boolean?>(null)
    override val autoIsolate: StateFlow<Boolean?> = _autoIsolate.asStateFlow()

    override suspend fun refresh() = defaultDispatcher.parseBbdOutput {
        val rows = JSONObject(sandbox("list")).getJSONArray("apps")
        _apps.value = List(rows.length()) { rows.getJSONObject(it).toSandboxedApp() }
        val global = JSONObject(sandbox("global"))
        val modes = global.getJSONObject("items").toModes()
        val auto = global.getBoolean("auto")
        _global.value = modes
        _autoIsolate.value = auto
    }

    override suspend fun getStatus(): SandboxStatus = defaultDispatcher.parseBbdOutput {
        val status = JSONObject(sandbox("status"))
        val hook = status.getJSONObject("hook")
        SandboxStatus(
            gate = status.getBoolean("gate"),
            appOps = status.getBoolean("appops"),
            androidIdHook = hook.getBoolean("android_id"),
            appListHook = hook.getBoolean("app_list"),
            uriGrantsHook = hook.getBoolean("uri_grants"),
            adIdHook = hook.getBoolean("ad_id"),
            newAppsPending = status.getInt("new_apps_pending"),
            newAppsHeld = status.getBoolean("new_apps_held"),
            network = status.getBoolean("network"),
            netRecord = status.getBoolean("net_record"),
            dnsNames = status.getBoolean("dns_names"),
            traffic = status.getBoolean("traffic"),
        )
    }

    // Package names come from the package manager, and are quoted all the
    // same; everything else interpolated is a number or an enum constant.
    override suspend fun isolate(packageName: String, userId: Int) {
        sandbox("enable ${packageName.shellQuoted()} --user $userId")
        refreshAfter("enable")
    }

    override suspend fun release(packageName: String, userId: Int) {
        sandbox("disable ${packageName.shellQuoted()} --user $userId")
        refreshAfter("disable")
    }

    override suspend fun releaseAll(): Int {
        val answer = sandbox("disable --all")
        refreshAfter("disable --all")
        // The daemon gives up on an app it cannot take out in time, and
        // names it rather than fail the whole command.
        return withContext(defaultDispatcher) {
            try {
                JSONObject(answer).getJSONArray("failed").length()
            } catch (e: JSONException) {
                throw RecordsException.CommandFailed(e.message.orEmpty())
            }
        }
    }

    override suspend fun setItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>) {
        if (changes.isEmpty()) return
        val settings = changes.entries.joinToString(" ") { (item, mode) -> "${item.id}=${mode?.id ?: GLOBAL}" }
        sandbox("set ${packageName.shellQuoted()} $settings --user $userId")
        refreshAfter("set")
    }

    override suspend fun setGlobal(changes: Map<SandboxItem, SandboxMode>) {
        if (changes.isEmpty()) return
        sandbox("global " + changes.entries.joinToString(" ") { (item, mode) -> "${item.id}=${mode.id}" })
        refreshAfter("global")
    }

    override suspend fun setAutoIsolate(on: Boolean) {
        sandbox("auto " + if (on) "on" else "off")
        _autoIsolate.value = on
        refreshAfter("auto")
    }

    /**
     * Reads the lists again after [command] was made. The change stands even
     * if they cannot be read: failing it here would have the caller report
     * as not made what the daemon did.
     */
    private suspend fun refreshAfter(command: String) {
        try {
            refresh()
        } catch (e: RecordsException) {
            // The reason is left out: the daemon's error can name packages.
            Timber.w("bbd sandbox %s made, lists not read again: %s", command, e.javaClass.simpleName)
        }
    }

    private fun JSONObject.toSandboxedApp(): SandboxedApp = SandboxedApp(
        uid = getInt("uid"),
        packageName = getString("package"),
        enabled = getBoolean("enabled"),
        created = getLong("created"),
        settings = getJSONObject("settings").toModes(),
        items = getJSONObject("items").toModes(),
        unmet = optJSONObject("unmet")?.toParts().orEmpty(),
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

    private suspend fun sandbox(args: String): String = rootShell.bbd("sandbox", args)

    private companion object {
        const val GLOBAL = "global"
    }
}
