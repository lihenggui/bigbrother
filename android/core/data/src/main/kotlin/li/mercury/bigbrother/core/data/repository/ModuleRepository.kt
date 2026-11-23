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

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.ModulePaths.DIRECT_DIR
import li.mercury.bigbrother.core.data.root.ModulePaths.LOCATE
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_DIR
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_PROP
import li.mercury.bigbrother.core.data.root.ModulePaths.PENDING_UPDATE_DIR
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.LoadFailure
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * The root module that does the recording: flashed in a root manager
 * (KernelSU, Magisk or APatch), or installed by this app with root alone.
 */
interface ModuleRepository {
    /** Whether the module's files are in place, i.e. it is installed and active. */
    suspend fun isActive(): Boolean

    /** How the installed module compares with the one bundled in this app. */
    suspend fun compareWithBundled(): InstalledModule

    /**
     * Brings the installed module up to date with the one bundled in this
     * app, without a reboot: replaces its files and restarts the daemon.
     * @throws RecordsException if the module is disabled or cannot be updated.
     */
    suspend fun updateInPlace()

    /** The installed module's version name. Throws [RecordsException] if it cannot be read. */
    suspend fun installedVersion(): String

    /** Whether a module update has been installed but needs a restart to take effect. */
    suspend fun isUpdatePending(): Boolean

    /**
     * Installs the module bundled in this app's own APK with root alone, and
     * starts it. A root manager is not involved and does not list it.
     *
     * @throws RecordsException if root is missing or the installer fails.
     */
    suspend fun installBundled()

    /** Whether this app installed the module, and so is what removes it. */
    suspend fun isInstalledByApp(): Boolean

    /**
     * Removes a module this app installed: gives the apps in Jail their own
     * state back, stops the daemon and deletes the records.
     * @throws RecordsException if the module could not be removed; it is then still installed.
     */
    suspend fun remove()

    /**
     * Starts the daemon through the module's own service script, as at boot.
     * With [turnOn], a module this app installed that turned itself off
     * after a failed boot is turned on again; without it, it stays off.
     * @throws RecordsException if the module is disabled or the daemon cannot be started.
     */
    suspend fun startDaemon(turnOn: Boolean = true)
}

/** How the installed module compares with the one bundled in this app. */
enum class InstalledModule {
    /** The one this app carries: nothing to do. */
    CURRENT,

    /**
     * Older than this app's, or the same version built differently (a
     * development build): this app's replaces it.
     */
    OUTDATED,

    /**
     * Newer than this app's. Left alone: this app's daemon may not read its
     * database.
     */
    NEWER,

    /** Not installed, or not readable without root. */
    UNKNOWN,
}

/**
 * Compares an installed module's version code and daemon digest with this
 * app's. Matching versions also require matching payloads.
 */
internal fun compareModules(
    installedCode: Long,
    installedDigest: String?,
    bundledCode: Long,
    bundledDigest: String?,
): InstalledModule = when {
    installedCode < bundledCode -> InstalledModule.OUTDATED
    installedCode > bundledCode -> InstalledModule.NEWER
    bundledDigest == null -> InstalledModule.UNKNOWN
    installedDigest == null -> InstalledModule.OUTDATED
    installedDigest.equals(bundledDigest, ignoreCase = true) -> InstalledModule.CURRENT
    else -> InstalledModule.OUTDATED
}

internal class RootModuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ModuleRepository {

    override suspend fun isActive(): Boolean = rootShell.run("$LOCATE; [ -x $BBD ]").isSuccess

    override suspend fun compareWithBundled(): InstalledModule {
        val files = (MODULE_FILES + "bin/bbd" + "helper.apk").joinToString(" ") { "$MODULE_DIR/$it" }
        // A module this app installed that turned itself off is not replaced
        // behind the user's back: it is compared once it is started again.
        val installed = rootShell.run(
            "$LOCATE; if [ $MODULE_DIR != $DIRECT_DIR ] || [ ! -e $MODULE_DIR/disable ]; then " +
                "sed -n 's/^versionCode=//p' $MODULE_PROP; sha256sum $files 2>/dev/null; fi",
        ).output.lines()
        val code = installed.firstOrNull()?.trim()?.toLongOrNull() ?: return InstalledModule.UNKNOWN
        // sha256sum prints the digest, then the file name.
        val digest = installed.drop(1).map { it.substringBefore(' ') }.filter { it.matches(Regex("[a-fA-F0-9]{64}")) }
            .takeIf { it.size == MODULE_FILES.size + 2 }?.joinToString(" ")
        return compareModules(code, digest, bundledVersionCode(), bundledPayloadDigest())
    }

    override suspend fun updateInPlace() {
        checkModuleEnabled()
        val entry = daemonEntry ?: throw RecordsException.CommandFailed(LoadFailure.Operation.UNSUPPORTED_ARCHITECTURE)
        val apk = context.applicationInfo.sourceDir.shellQuoted()
        // Both the script and payload come from the immutable installed APK.
        val result = rootShell.run(
            "(umask 077; d=\$(mktemp -d /data/adb/.bb-update.XXXXXX) || exit 1; " +
                "trap 'rm -rf \"\$d\"' EXIT; unzip -p $apk sync.sh >\"\$d/sync.sh\" && " +
                "sh \"\$d/sync.sh\" \"\$d\" $apk ${entry.shellQuoted()}) 2>&1",
            label = "module update",
        )
        if (!result.isSuccess) throw RecordsException.CommandFailed(moduleInstallerFailure(result.output, LoadFailure.Operation.MODULE_UPDATE_FAILED))
    }

    private suspend fun bundledVersionCode(): Long = withContext(ioDispatcher) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }

    /** The daemon this app carries for this device, as its path inside the APK. */
    private val daemonEntry: String? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { ARCH_BY_ABI[it] }?.let { "bin/$it/bbd" }

    /** Digests in installed-file order, cached for the lifetime of this APK. */
    private var bundledDigest: String? = null

    private suspend fun bundledPayloadDigest(): String? = bundledDigest ?: withContext(ioDispatcher) {
        val entry = daemonEntry ?: return@withContext null
        try {
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val digests = (MODULE_FILES + entry).map { name ->
                    val file = zip.getEntry(name) ?: return@withContext null
                    zip.getInputStream(file).use(::digest)
                }
                (digests + File(context.applicationInfo.sourceDir).inputStream().use(::digest)).joinToString(" ")
            }
        } catch (_: IOException) {
            throw RecordsException.CommandFailed(LoadFailure.Operation.MODULE_ARCHIVE_INVALID)
        }
    }.also { bundledDigest = it }

    private fun digest(input: java.io.InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Module files installed besides the daemon; the list module/customize.sh extracts. */
        val MODULE_FILES = listOf("module.prop", "service.sh", "supervise.sh", "stop.sh", "uninstall.sh", "action.sh", "direct.sh")

        /** Android ABI -> the architecture name module installers use, as in bin/<arch>/bbd. */
        val ARCH_BY_ABI = mapOf("arm64-v8a" to "arm64", "x86_64" to "x64")

        const val BUFFER_SIZE = 64 * 1024
        const val MODULE_VERSION_MISSING = "module-version-missing"
    }

    override suspend fun installedVersion(): String {
        val result = rootShell.run(
            "$LOCATE; if [ -e $MODULE_PROP ]; then sed -n 's/^version=//p' $MODULE_PROP; " +
                "else echo $MODULE_VERSION_MISSING >&2; false; fi",
            label = "read module version",
        )
        if (result.error == MODULE_VERSION_MISSING) throw RecordsException.ModuleMissing()
        if (!result.isSuccess) throw RecordsException.CommandFailed(LoadFailure.Operation.MODULE_VERSION_UNREADABLE)
        return result.output.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw RecordsException.CommandFailed(LoadFailure.Operation.MODULE_VERSION_UNREADABLE)
    }

    override suspend fun isUpdatePending(): Boolean = rootShell.run("[ -d $PENDING_UPDATE_DIR ]").isSuccess

    override suspend fun installBundled() {
        val apk = context.applicationInfo.sourceDir.shellQuoted()
        // Both the script and payload come from the immutable installed APK.
        val result = rootShell.run(
            "(umask 077; d=\$(mktemp -d /data/adb/.bb-install.XXXXXX) || exit 1; " +
                "trap 'rm -rf \"\$d\"' EXIT; unzip -p $apk direct.sh >\"\$d/direct.sh\" && " +
                "sh \"\$d/direct.sh\" install $apk) 2>&1",
            label = "module install",
        )
        if (!result.isSuccess) throw RecordsException.CommandFailed(moduleInstallerFailure(result.output, LoadFailure.Operation.MODULE_INSTALL_FAILED))
    }

    override suspend fun isInstalledByApp(): Boolean = rootShell.run("$LOCATE; [ $MODULE_DIR = $DIRECT_DIR ] && [ -f $DIRECT_DIR/direct.sh ]").isSuccess

    override suspend fun remove() {
        val result = rootShell.run(
            "$LOCATE; [ $MODULE_DIR = $DIRECT_DIR ] && sh $DIRECT_DIR/direct.sh remove </dev/null >/dev/null 2>&1",
            label = "module remove",
        )
        if (!result.isSuccess) throw RecordsException.CommandFailed(LoadFailure.Operation.MODULE_REMOVE_FAILED)
    }

    override suspend fun startDaemon(turnOn: Boolean) {
        // No root manager turns this one on again after its boot guard.
        if (turnOn) rootShell.run("$LOCATE; [ $MODULE_DIR != $DIRECT_DIR ] || rm -f $DIRECT_DIR/disable")
        checkModuleEnabled()
        // The script leaves the supervisor running in the background; with
        // its output detached, the shell returns once it has been started.
        // No `exit` here: the root shell is shared and must stay open.
        val script = "$MODULE_DIR/service.sh"
        val result = rootShell.run(
            "$LOCATE; if [ -f $script ]; then sh $script </dev/null >/dev/null 2>&1; else false; fi",
            label = "daemon start",
        )
        if (!result.isSuccess) throw RecordsException.CommandFailed(LoadFailure.Operation.DAEMON_START_FAILED)
    }

    private suspend fun checkModuleEnabled() {
        if (rootShell.run("$LOCATE; [ -e $MODULE_DIR/disable ] || [ -e $MODULE_DIR/remove ]").isSuccess) {
            throw RecordsException.CommandFailed(LoadFailure.Operation.MODULE_DISABLED)
        }
    }
}

/** Match messages from the bundled installer and daemon without exposing their output. */
internal fun moduleInstallerFailure(output: String, fallback: LoadFailure.Operation): LoadFailure.Operation {
    for (line in output.lineSequence()) {
        val message = line.trim().removePrefix("!").trim().removePrefix("Error: ")
        when {
            message.startsWith("Unsupported architecture:") -> return LoadFailure.Operation.UNSUPPORTED_ARCHITECTURE
            message.startsWith("Kernel has no BTF ") -> return LoadFailure.Operation.KERNEL_BTF_MISSING
            message.startsWith("Kernel ") && message.endsWith("is too old; 5.10 or newer required") -> return LoadFailure.Operation.KERNEL_TOO_OLD
            message.startsWith("Failed to extract ") || message.endsWith(" is missing from the archive") -> return LoadFailure.Operation.MODULE_ARCHIVE_INVALID
            message.startsWith("unsupported database schema ") -> return LoadFailure.Operation.DATABASE_INCOMPATIBLE
            message == "Module updated; daemon startup was not confirmed" -> return LoadFailure.Operation.DAEMON_NOT_REPORTING
        }
    }
    return fallback
}
