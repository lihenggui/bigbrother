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

package li.mercury.bigbrother.catalog

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** `UserHandle.PER_USER_RANGE`. */
private const val PER_USER_RANGE = 100_000

internal fun userOf(uid: Int): Int = uid / PER_USER_RANGE

/** `PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES`. */
private const val MATCH_STATIC_SHARED_AND_SDK_LIBRARIES = 0x04000000

/** `PackageManager.MATCH_UNINSTALLED_PACKAGES`. */
private const val MATCH_UNINSTALLED_PACKAGES = 0x00002000

private const val USER_DE_ROOT = "/data/user_de"

internal data class Installation(val packageName: String, val token: String) {
    fun matches(uid: Int): Boolean {
        val directory = "$USER_DE_ROOT/${userOf(uid)}/$packageName"
        val stat = Os.lstat(directory)
        return OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid &&
            Os.getxattr(directory, "trusted.big_brother.install").joinToString("") { "%02x".format(it) } == token
    }

    /** Absence of the binding can retire a recovery entry once the user's directory is available. */
    fun isCurrent(uid: Int): Boolean = try {
        matches(uid)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENODATA ||
            (e.errno == OsConstants.ENOENT && File("$USER_DE_ROOT/${userOf(uid)}").isDirectory && packageName !in Framework.packagesOf(uid))
        ) {
            false
        } else {
            throw e
        }
    }
}

/**
 * Whether [packageName], one of [uid]'s packages, is a library rather
 * than an app, by the flags the package manager keeps for it.
 */
@SuppressLint("PrivateApi")
internal fun isLibrary(packageName: String, uid: Int): Boolean {
    val manager = Framework.packageManager()
    val managerType = Class.forName("android.content.pm.IPackageManager")

    // Libraries are left out of what the package manager answers unless
    // asked for.
    val flags = MATCH_STATIC_SHARED_AND_SDK_LIBRARIES or MATCH_UNINSTALLED_PACKAGES
    val userId = userOf(uid)
    // The flags are a long from Android 13 on.
    val int = Int::class.javaPrimitiveType!!
    val withLong = managerType.optionalMethod("getApplicationInfo", String::class.java, Long::class.javaPrimitiveType!!, int)
    val info = if (withLong != null) {
        withLong.invoke(manager, packageName, flags.toLong(), userId)
    } else {
        managerType.getMethod("getApplicationInfo", String::class.java, int, int).invoke(manager, packageName, flags, userId)
    } as ApplicationInfo? ?: error("no such package")
    check(info.uid == uid) { "not a package of the uid" }
    return info.isFlagged("isStaticSharedLibrary") || info.isFlagged("isSdkLibrary")
}

/** Whether [method], a flag this Android version may not have, is set. */
private fun ApplicationInfo.isFlagged(method: String): Boolean = javaClass.optionalMethod(method)?.invoke(this) as Boolean? ?: false
