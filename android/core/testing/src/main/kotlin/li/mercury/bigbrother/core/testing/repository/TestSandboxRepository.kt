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

package li.mercury.bigbrother.core.testing.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.repository.SandboxRepository
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp

/**
 * A sandbox kept in memory, worked out the way the daemon does it: an app's
 * own settings over the global ones, every item on unless set otherwise. As
 * in the app, a change that was made does not fail when the lists cannot be
 * read again; they stay as they were.
 */
class TestSandboxRepository(
    apps: List<SandboxedApp> = emptyList(),
    global: Map<SandboxItem, SandboxMode> = emptyMap(),
    var status: SandboxStatus = SandboxStatus(gate = true, appOps = true, androidIdHook = true, appListHook = true),
    autoIsolate: Boolean = false,
) : SandboxRepository {
    /** Thrown by every call while set. */
    var failure: RecordsException? = null

    /** Thrown by [refresh] while set. */
    var refreshFailure: RecordsException? = null

    /** Packages that cannot be put in the sandbox, with what trying throws. */
    var failsToIsolate: Map<String, RecordsException> = emptyMap()

    /** Packages that cannot be taken out of the sandbox, with what trying throws. */
    var failsToRelease: Map<String, RecordsException> = emptyMap()

    /** How many apps releasing every app leaves in the sandbox. */
    var releaseAllLeaves: Int = 0

    /** The packages put in and taken out, in order. */
    val isolated = mutableListOf<String>()
    val released = mutableListOf<String>()

    private val globalModes = global.toMutableMap()
    private val stored = apps.toMutableList()
    private var autoIsolateOn = autoIsolate

    private val _apps = MutableStateFlow<List<SandboxedApp>?>(null)
    override val apps: StateFlow<List<SandboxedApp>?> = _apps

    private val _global = MutableStateFlow<Map<SandboxItem, SandboxMode>?>(null)
    override val global: StateFlow<Map<SandboxItem, SandboxMode>?> = _global

    private val _autoIsolate = MutableStateFlow<Boolean?>(null)
    override val autoIsolate: StateFlow<Boolean?> = _autoIsolate

    override suspend fun refresh() {
        check()
        refreshFailure?.let { throw it }
        _apps.value = stored.map { it.copy(items = applies(it.settings)) }
        _global.value = applies(emptyMap())
        _autoIsolate.value = autoIsolateOn
    }

    override suspend fun getStatus(): SandboxStatus {
        check()
        return status
    }

    override suspend fun isolate(packageName: String, userId: Int) {
        check()
        failsToIsolate[packageName]?.let { throw it }
        isolated += packageName
        val index = stored.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            stored[index] = stored[index].copy(enabled = true)
        } else {
            stored += SandboxedApp(10_000 + stored.size, packageName, true, 0, emptyMap(), emptyMap())
        }
        refreshAfterChange()
    }

    override suspend fun release(packageName: String, userId: Int) {
        check()
        failsToRelease[packageName]?.let { throw it }
        released += packageName
        stored.replaceAll { if (it.packageName == packageName) it.copy(enabled = false) else it }
        refreshAfterChange()
    }

    override suspend fun releaseAll(): Int {
        check()
        val kept = stored.filter { it.enabled }.take(releaseAllLeaves).toSet()
        stored.replaceAll { if (it in kept) it else it.copy(enabled = false) }
        refreshAfterChange()
        return kept.size
    }

    override suspend fun setItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>) {
        check()
        stored.replaceAll { app ->
            if (app.packageName != packageName) return@replaceAll app
            val settings = app.settings.toMutableMap()
            changes.forEach { (item, mode) -> if (mode == null) settings.remove(item) else settings[item] = mode }
            app.copy(settings = settings)
        }
        refreshAfterChange()
    }

    override suspend fun setGlobal(changes: Map<SandboxItem, SandboxMode>) {
        check()
        globalModes += changes
        refreshAfterChange()
    }

    override suspend fun setAutoIsolate(on: Boolean) {
        check()
        autoIsolateOn = on
        refreshAfterChange()
    }

    private fun applies(own: Map<SandboxItem, SandboxMode>): Map<SandboxItem, SandboxMode> = SandboxItem.entries.associateWith { own[it] ?: globalModes[it] ?: SandboxMode.ON }

    private suspend fun refreshAfterChange() {
        try {
            refresh()
        } catch (_: RecordsException) {
            // Made all the same.
        }
    }

    private fun check() {
        failure?.let { throw it }
    }
}
