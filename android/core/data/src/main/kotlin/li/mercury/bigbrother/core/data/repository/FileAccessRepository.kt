package li.mercury.bigbrother.core.data.repository

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.RootShell

/** This app's own access to shared storage, needed to open recorded files. */
interface FileAccessRepository {
    /**
     * Makes sure the app holds all-files access, granting it through root if
     * necessary. Returns whether the app has it afterwards.
     */
    suspend fun ensureAllFilesAccess(): Boolean
}

internal class RootFileAccessRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
) : FileAccessRepository {

    override suspend fun ensureAllFilesAccess(): Boolean {
        if (Environment.isExternalStorageManager()) return true
        // The app already runs with root, so it grants itself the permission
        // instead of sending the user to the settings screen.
        try {
            rootShell.run("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } catch (_: RecordsException) {
            return false
        }
        return Environment.isExternalStorageManager()
    }
}
