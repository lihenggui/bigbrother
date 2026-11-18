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
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_DIR
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_PROP
import li.mercury.bigbrother.core.data.root.ModulePaths.PENDING_UPDATE_DIR
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

/** The root module (KernelSU, Magisk or APatch) that does the recording. */
interface ModuleRepository {
    /** Whether the module's files are in place, i.e. it is installed and active. */
    suspend fun isActive(): Boolean

    /** How the installed module compares with the one bundled in this app. */
    suspend fun compareWithBundled(): InstalledModule

    /**
     * Brings the installed module up to date with the one bundled in this
     * app, without a reboot: replaces its files and restarts the daemon.
     * Returns false, changing nothing, when the module is disabled or marked
     * for removal.
     *
     * @throws RecordsException if root is missing or a file can't be replaced.
     */
    suspend fun updateInPlace(): Boolean

    /** The installed module's version name, or null if it is not installed or root is missing. */
    suspend fun installedVersion(): String?

    /** Whether a module update has been installed but needs a restart to take effect. */
    suspend fun isUpdatePending(): Boolean

    /**
     * Flashes the module bundled in this app's own APK, which carries the
     * module files, and returns the installer's output.
     *
     * @throws RecordsException if root is missing or the installer fails.
     */
    suspend fun installBundled(): String

    /**
     * Starts the daemon through the module's own service script, as at boot.
     * Returns false, without starting it, when the module is disabled or
     * marked for removal: by the user in the root manager, or by the
     * module's own boot guard after a boot that did not finish.
     *
     * @throws RecordsException if root is missing or the script fails.
     */
    suspend fun startDaemon(): Boolean
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
 * app's. Without both digests, a module of the same version can't be told
 * apart, and is taken to be current.
 */
internal fun compareModules(
    installedCode: Long,
    installedDigest: String?,
    bundledCode: Long,
    bundledDigest: String?,
): InstalledModule = when {
    installedCode < bundledCode -> InstalledModule.OUTDATED
    installedCode > bundledCode -> InstalledModule.NEWER
    installedDigest == null || bundledDigest == null -> InstalledModule.CURRENT
    installedDigest.equals(bundledDigest, ignoreCase = true) -> InstalledModule.CURRENT
    else -> InstalledModule.OUTDATED
}

internal class RootModuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ModuleRepository {

    override suspend fun isActive(): Boolean = try {
        rootShell.run("[ -x $BBD ]").isSuccess
    } catch (_: RecordsException.NoRoot) {
        false
    }

    override suspend fun compareWithBundled(): InstalledModule {
        val installed = try {
            rootShell.run("sed -n 's/^versionCode=//p' $MODULE_PROP; sha256sum $BBD 2>/dev/null").output.lines()
        } catch (_: RecordsException.NoRoot) {
            return InstalledModule.UNKNOWN
        }
        val code = installed.firstOrNull()?.trim()?.toLongOrNull() ?: return InstalledModule.UNKNOWN
        // sha256sum prints the digest, then the file name.
        val digest = installed.getOrNull(1)?.substringBefore(' ')?.trim()?.ifEmpty { null }
        return compareModules(code, digest, bundledVersionCode(), bundledDaemonDigest())
    }

    override suspend fun updateInPlace(): Boolean {
        if (rootShell.run("[ -e $MODULE_DIR/disable ] || [ -e $MODULE_DIR/remove ]").isSuccess) {
            return false
        }
        val staged = withContext(ioDispatcher) { stageBundledModule() }
            ?: throw RecordsException.CommandFailed("This app carries no daemon for ${Build.SUPPORTED_ABIS.first()}")
        try {
            // The script comes from this app: the module does not carry it.
            // Run by its own shell, its exit leaves the shared root shell open.
            val result = rootShell.run(
                "sh ${staged.resolve(SYNC_SCRIPT).path.shellQuoted()} " +
                    "${staged.path.shellQuoted()} ${context.applicationInfo.sourceDir.shellQuoted()} 2>&1",
                label = "module update",
            )
            if (!result.isSuccess) {
                throw RecordsException.CommandFailed(result.output.ifBlank { "Module update failed (${result.code})" })
            }
        } finally {
            withContext(ioDispatcher) { staged.deleteRecursively() }
        }
        return true
    }

    private fun bundledVersionCode(): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** The daemon this app carries for this device, as its path inside the APK. */
    private val daemonEntry: String? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { ARCH_BY_ABI[it] }?.let { "bin/$it/bbd" }

    /** The SHA-256 of [daemonEntry], read once: the APK does not change while the app runs. */
    private var bundledDigest: String? = null

    private suspend fun bundledDaemonDigest(): String? = bundledDigest ?: withContext(ioDispatcher) {
        val entry = daemonEntry ?: return@withContext null
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val file = zip.getEntry(entry) ?: return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.also { bundledDigest = it }

    /**
     * Copies the module files this app carries into its cache, where root
     * can read them: the scripts and module.prop, the daemon for this device
     * as `bbd`, and the script that installs them. Null if one is missing.
     */
    private fun stageBundledModule(): File? {
        val daemon = daemonEntry ?: return null
        val dir = File(context.cacheDir, "module-update")
        dir.deleteRecursively()
        dir.mkdirs()
        ZipFile(context.applicationInfo.sourceDir).use { zip ->
            for (name in MODULE_FILES + SYNC_SCRIPT + daemon) {
                val entry = zip.getEntry(name) ?: return null.also { dir.deleteRecursively() }
                val target = File(dir, if (name == daemon) "bbd" else name)
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        return dir
    }

    private companion object {
        /** Module files installed besides the daemon; the list module/customize.sh extracts. */
        val MODULE_FILES = listOf("module.prop", "service.sh", "supervise.sh", "stop.sh", "uninstall.sh", "action.sh")

        /** Installs staged module files over the installed ones (module/sync.sh). */
        const val SYNC_SCRIPT = "sync.sh"

        /** Android ABI -> the architecture name module installers use, as in bin/<arch>/bbd. */
        val ARCH_BY_ABI = mapOf("arm64-v8a" to "arm64", "x86_64" to "x64")

        const val BUFFER_SIZE = 64 * 1024
    }

    override suspend fun installedVersion(): String? = try {
        rootShell.run("sed -n 's/^version=//p' $MODULE_PROP")
            .output.lineSequence().firstOrNull()?.trim()?.ifEmpty { null }
    } catch (_: RecordsException.NoRoot) {
        null
    }

    override suspend fun isUpdatePending(): Boolean = try {
        rootShell.run("[ -d $PENDING_UPDATE_DIR ]").isSuccess
    } catch (_: RecordsException.NoRoot) {
        false
    }

    override suspend fun installBundled(): String {
        val result = rootShell.run(installCommand(context.applicationInfo.sourceDir), label = "module install")
        if (!result.isSuccess) throw RecordsException.CommandFailed(result.output)
        return result.output
    }

    override suspend fun startDaemon(): Boolean {
        if (rootShell.run("[ -e $MODULE_DIR/disable ] || [ -e $MODULE_DIR/remove ]").isSuccess) {
            return false
        }
        // The script leaves the supervisor running in the background; with
        // its output detached, the shell returns once it has been started.
        // No `exit` here: the root shell is shared and must stay open.
        val script = "$MODULE_DIR/service.sh"
        val result = rootShell.run(
            "if [ -f $script ]; then sh $script </dev/null >/dev/null 2>&1; " +
                "else echo '$script not found'; false; fi",
            label = "daemon start",
        )
        if (!result.isSuccess) throw RecordsException.CommandFailed(result.output)
        return true
    }
}

/**
 * A shell command that flashes the module in [apkPath] with the installer of
 * whichever root manager is present.
 *
 * Magisk is tested first because `magisk -V` asks the running daemon, so it
 * only succeeds while Magisk is the active root. The daemons of KernelSU and
 * APatch are found by their files, which can outlive a switch to another
 * manager; KernelSU wins if both exist.
 */
internal fun installCommand(apkPath: String): String {
    val apk = apkPath.shellQuoted()
    return """
        if magisk -V >/dev/null 2>&1; then
            magisk --install-module $apk 2>&1
        elif [ -x /data/adb/ksud ]; then
            /data/adb/ksud module install $apk 2>&1
        elif [ -x /data/adb/apd ]; then
            /data/adb/apd module install $apk 2>&1
        else
            echo "No module installer found (KernelSU, Magisk or APatch)."
            exit 1
        fi
    """.trimIndent()
}
