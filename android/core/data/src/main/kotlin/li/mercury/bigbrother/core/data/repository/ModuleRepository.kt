package li.mercury.bigbrother.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.ModulePaths.KSUD
import li.mercury.bigbrother.core.data.root.ModulePaths.MODULE_PROP
import li.mercury.bigbrother.core.data.root.ModulePaths.PENDING_UPDATE_DIR
import li.mercury.bigbrother.core.data.root.RootShell

/** The KernelSU module that does the recording. */
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
        val apkPath = context.applicationInfo.sourceDir
        // Single quotes keep the shell from interpreting the path; install
        // paths never contain a single quote themselves.
        val result = rootShell.run("$KSUD module install '$apkPath' 2>&1")
        if (!result.isSuccess) throw RecordsException.CommandFailed(result.output)
        return result.output
    }
}
