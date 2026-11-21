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
import li.mercury.bigbrother.core.data.bbd.BbdDataSource
import li.mercury.bigbrother.core.data.bbd.asExternalModel
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.model.SandboxedApp
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

@Singleton
internal class RootSandboxRepository @Inject constructor(
    private val bbd: BbdDataSource,
    @Dispatcher(DEFAULT) private val defaultDispatcher: CoroutineDispatcher,
) : SandboxRepository {
    private val _apps = MutableStateFlow<List<SandboxedApp>?>(null)
    override val apps: StateFlow<List<SandboxedApp>?> = _apps.asStateFlow()

    private val _global = MutableStateFlow<Map<SandboxItem, SandboxMode>?>(null)
    override val global: StateFlow<Map<SandboxItem, SandboxMode>?> = _global.asStateFlow()

    private val _autoIsolate = MutableStateFlow<Boolean?>(null)
    override val autoIsolate: StateFlow<Boolean?> = _autoIsolate.asStateFlow()

    override suspend fun refresh() = withContext(defaultDispatcher) {
        _apps.value = bbd.listSandboxApps().map { it.asExternalModel() }
        val settings = bbd.getSandboxGlobal()
        _global.value = settings.items
        _autoIsolate.value = settings.auto
    }

    override suspend fun getStatus(): SandboxStatus = bbd.getSandboxStatus().asExternalModel()

    override suspend fun isolate(packageName: String, userId: Int) {
        bbd.enableSandbox(packageName, userId)
        refreshAfter("enable")
    }

    override suspend fun release(packageName: String, userId: Int) {
        bbd.disableSandbox(packageName, userId)
        refreshAfter("disable")
    }

    override suspend fun releaseAll(): Int {
        val answer = try {
            bbd.disableAllSandboxes()
        } catch (e: RecordsException.BadResponse) {
            // The command ran and may have released apps even though the count is unreadable.
            refreshAfter("disable --all")
            throw e
        }
        refreshAfter("disable --all")
        return answer.failedCount
    }

    override suspend fun setItems(packageName: String, userId: Int, changes: Map<SandboxItem, SandboxMode?>) {
        if (changes.isEmpty()) return
        bbd.setSandboxItems(packageName, userId, changes)
        refreshAfter("set")
    }

    override suspend fun setGlobal(changes: Map<SandboxItem, SandboxMode>) {
        if (changes.isEmpty()) return
        bbd.setSandboxGlobal(changes)
        refreshAfter("global")
    }

    override suspend fun setAutoIsolate(on: Boolean) {
        bbd.setSandboxAuto(on)
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
            Timber.w("bbd sandbox %s made, lists not read again: %s", command, e.toLoadFailure())
        }
    }
}
