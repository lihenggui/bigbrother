package li.mercury.bigbrother.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_PROP
import li.mercury.bigbrother.core.data.root.ModulePaths.PENDING_UPDATE_DIR
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted

/** The root module (KernelSU, Magisk or APatch) that does the recording. */
interface ModuleRepository {
    /** Whether the module's files are in place, i.e. it is installed and active. */
    suspend fun isActive(): Boolean

    /** Whether the installed module is older than the one bundled in this app. */
    suspend fun isOutdated(): Boolean

    /** Whether a module update has been installed but needs a restart to take effect. */
    suspend fun isUpdatePending(): Boolean

    /**
     * Flashes the module bundled in this app's own APK, which carries the
     * module files, and returns the installer's output.
     *
     * @throws RecordsException if root is missing or the installer fails.
     */
    suspend fun installBundled(): String
}

internal class RootModuleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
) : ModuleRepository {

    override suspend fun isActive(): Boolean = try {
        rootShell.run("[ -x $BBD ]").isSuccess
    } catch (_: RecordsException.NoRoot) {
        false
    }

    override suspend fun isOutdated(): Boolean {
        val installed = try {
            rootShell.run("sed -n 's/^versionCode=//p' $MODULE_PROP")
                .output.lineSequence().firstOrNull()?.trim()?.toLongOrNull()
        } catch (_: RecordsException.NoRoot) {
            null
        } ?: return false
        // The app and the bundled module share one version (module.prop).
        val bundled = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        return installed < bundled
    }

    override suspend fun isUpdatePending(): Boolean = try {
        rootShell.run("[ -d $PENDING_UPDATE_DIR ]").isSuccess
    } catch (_: RecordsException.NoRoot) {
        false
    }

    override suspend fun installBundled(): String {
        val result = rootShell.run(installCommand(context.applicationInfo.sourceDir))
        if (!result.isSuccess) throw RecordsException.CommandFailed(result.output)
        return result.output
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
