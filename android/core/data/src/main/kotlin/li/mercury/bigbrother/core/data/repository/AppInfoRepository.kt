package li.mercury.bigbrother.core.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.model.AppInfo

/** Names and icons of installed apps. */
interface AppInfoRepository {
    /** Returns null for packages that are not installed. */
    suspend fun getAppInfo(packageName: String): AppInfo?

    /** [getAppInfo] for several packages; those not installed are omitted. */
    suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo>
}

/** Backed by the package manager, with results cached for the process lifetime. */
@Singleton
internal class PackageManagerAppInfoRepository @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : AppInfoRepository {
    private val packageManager = context.packageManager
    private val iconSizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()
    private val cache = ConcurrentHashMap<String, AppInfo>()

    override suspend fun getAppInfo(packageName: String): AppInfo? {
        cache[packageName]?.let { return it }
        return withContext(ioDispatcher) {
            try {
                val info = packageManager.getApplicationInfo(packageName, 0)
                AppInfo(
                    label = packageManager.getApplicationLabel(info).toString(),
                    icon = packageManager.getApplicationIcon(info).toBitmap(iconSizePx, iconSizePx),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                ).also { cache[packageName] = it }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> =
        packageNames.mapNotNull { name -> getAppInfo(name)?.let { name to it } }.toMap()

    private companion object {
        /** Large enough for the detail screen header; list rows scale it down. */
        const val ICON_SIZE_DP = 56
    }
}
