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
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.LoadFailure
import javax.inject.Inject

/** This app's own access to shared storage, needed to open recorded files. */
interface FileAccessRepository {
    /**
     * Makes sure the app holds all-files access, granting it through root if
     * necessary. Throws [RecordsException] if the grant cannot be obtained.
     */
    suspend fun ensureAllFilesAccess()
}

internal class RootFileAccessRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
) : FileAccessRepository {

    override suspend fun ensureAllFilesAccess() {
        if (Environment.isExternalStorageManager()) return
        // The app already runs with root, so it grants itself the permission
        // instead of sending the user to the settings screen.
        rootShell.run(
            "appops set ${context.packageName.shellQuoted()} MANAGE_EXTERNAL_STORAGE allow",
            label = "grant all-files access",
        )
        if (!Environment.isExternalStorageManager()) throw RecordsException.CommandFailed(LoadFailure.Operation.FILE_ACCESS_DENIED)
    }
}
