package li.mercury.bigbrother.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Why a recorded file could not be shown. */
enum class OpenFileResult {
    OPENED,

    /** The file has been deleted or moved, or belongs to another Android user. */
    MISSING,

    /** No installed app can display this kind of file. */
    NO_VIEWER,

    /** This app could not obtain access to shared storage. */
    NO_ACCESS,
}

/**
 * Show the file at [path] in whichever app handles its type, the way a file
 * manager does: the viewer receives a content URI with temporary read access
 * to that one file. The caller must hold all-files access.
 */
fun openFile(context: Context, path: String): OpenFileResult {
    val file = File(path)
    if (!file.exists()) return OpenFileResult.MISSING
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (_: IllegalArgumentException) {
        // Outside the directories the provider is allowed to share.
        return OpenFileResult.MISSING
    }
    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        OpenFileResult.OPENED
    } catch (_: ActivityNotFoundException) {
        OpenFileResult.NO_VIEWER
    }
}
