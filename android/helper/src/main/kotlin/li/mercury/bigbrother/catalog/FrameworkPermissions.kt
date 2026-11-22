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

@file:Suppress("ktlint:standard:filename")

package li.mercury.bigbrother.catalog

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.IBinder

/**
 * The runtime permissions of apps, through the system's permission
 * service ([service], an `IPermissionManager`) and package manager
 * ([packages], an `IPackageManager`).
 *
 * The permission service's calls took a device in Android 14: an int
 * device id in QPR2, a persistent device id from QPR3 on, right before
 * the user. Each is called for the default device in whichever form this
 * version has ([DeviceCall]). The package manager's calls are the same
 * from Android 12 on, but for `getPackageInfo`, whose flags became a
 * long in Android 13.
 *
 * The permission service of Android 15 and later does not throw when it
 * refuses a change (a permission fixed by the system or by policy, an
 * app too old for runtime permissions): every change is looked at again
 * afterwards, and fails if it did not take.
 */
@SuppressLint("PrivateApi")
internal class Permissions(private val service: Any, private val packages: Any) : RuntimePermissions {
    private val permissionType = Class.forName("android.permission.IPermissionManager")
    private val packageType = Class.forName("android.content.pm.IPackageManager")

    private val getFlags = DeviceCall(permissionType, "getPermissionFlags", listOf(STRING, STRING), listOf(INT))
    private val updateFlags = DeviceCall(permissionType, "updatePermissionFlags", listOf(STRING, STRING, INT, INT, BOOLEAN), listOf(INT))
    private val grantCall = DeviceCall(permissionType, "grantRuntimePermission", listOf(STRING, STRING), listOf(INT))
    private val revokeCall = DeviceCall(permissionType, "revokeRuntimePermission", listOf(STRING, STRING), listOf(INT, STRING))
    private val packagesForUid = packageType.getMethod("getPackagesForUid", INT)
    private val checkUidPermission = packageType.getMethod("checkUidPermission", STRING, INT)
    private val packageInfo = packageType.optionalMethod("getPackageInfo", STRING, LONG, INT)
    private val packageInfoInt = if (packageInfo == null) packageType.getMethod("getPackageInfo", STRING, INT, INT) else null
    private val addListener = permissionType.getMethod("addOnPermissionsChangeListener", Class.forName("android.permission.IOnPermissionsChangeListener"))

    // Without these, ops are put back as saved, whatever the grants.
    private val permissionInfo = permissionType.optionalMethod("getPermissionInfo", STRING, STRING, INT)
    private val backgroundField = try {
        // Part of the system API, not of the SDK.
        PermissionInfo::class.java.getField("backgroundPermission")
    } catch (_: Throwable) {
        null
    }

    override fun runtime(permission: String): RuntimePermissions.Runtime? {
        val method = permissionInfo ?: error("no getPermissionInfo")
        val field = backgroundField ?: error("no backgroundPermission")
        // Asked for as the system, which is shown every permission.
        val info = method.call(service, permission, "android", 0) as PermissionInfo? ?: return null
        return if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) RuntimePermissions.Runtime(field.get(info) as String?) else null
    }

    /** Have [watcher] called on every change of a permission. */
    fun watch(watcher: Binder) {
        val listener = Class.forName("android.permission.IOnPermissionsChangeListener\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, watcher)
        addListener.call(service, listener)
    }

    override fun grant(pkg: String, permission: String, uid: Int) {
        grantCall.call(service, listOf(pkg, permission), listOf(userOf(uid)))
    }

    override fun revoke(pkg: String, permission: String, uid: Int) {
        // The system's own reason, as when the user revokes it: the app
        // can read why it was ended.
        revokeCall.call(service, listOf(pkg, permission), listOf(userOf(uid), null))
    }

    /** The packages of [uid]. */
    fun packagesOf(uid: Int): List<String> = (packagesForUid.call(packages, uid) as Array<*>?).orEmpty().filterIsInstance<String>()

    override fun requesting(uid: Int, permission: String): RuntimePermissions.Requests {
        val names = packagesForUid.call(packages, uid) as Array<*>? ?: return RuntimePermissions.Requests(emptyList(), complete = false)
        val requesting = mutableListOf<String>()
        var complete = true
        for (name in names.filterIsInstance<String>()) {
            val info = if (packageInfo != null) {
                packageInfo.call(packages, name, PackageManager.GET_PERMISSIONS.toLong(), userOf(uid))
            } else {
                packageInfoInt!!.call(packages, name, PackageManager.GET_PERMISSIONS, userOf(uid))
            } as PackageInfo?
            if (info == null) {
                complete = false
            } else if (info.requestedPermissions?.contains(permission) == true) {
                requesting += name
            }
        }
        return RuntimePermissions.Requests(requesting, complete)
    }

    override fun isGranted(uid: Int, permission: String): Boolean = checkUidPermission.call(packages, permission, uid) == PackageManager.PERMISSION_GRANTED

    override fun flags(pkg: String, permission: String, uid: Int): Int = getFlags.call(service, listOf(pkg, permission), listOf(userOf(uid))) as Int

    // Policy flags are never among them, so the check of the right to
    // change those is asked for.
    override fun setFlags(pkg: String, permission: String, uid: Int, mask: Int, values: Int) {
        updateFlags.call(service, listOf(pkg, permission, mask, values, true), listOf(userOf(uid)))
    }
}
