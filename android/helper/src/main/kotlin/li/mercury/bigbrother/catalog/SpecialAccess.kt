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
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IBinder
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.PermissionState
import li.mercury.bigbrother.catalog.Taking.HoldResult

private const val NOTIFICATION_ACCESS = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

/** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. */
private const val ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

private const val ACCESSIBILITY_UPDATE_LIMIT = 3

/**
 * The access to the notifications and to the screen the user gave an
 * app's services ([AppOpsGuardProtocol.SPECIAL_ACCESS]): notification
 * listeners through the notification service ([notifications], an
 * `INotificationManager`), as the system's settings grant it;
 * accessibility services through the secure setting the accessibility
 * service reads. A service is one or the other by the permission it is
 * bound with, which it declares.
 *
 * Taken from every service of the uid that has it, and given back to
 * those that had it, and to no other: one never given the access must
 * not get it. Neither service reports to a process such as this one
 * when the user gives the access again: that is found by the checks
 * every minute. With no state known, which ones had it cannot be told,
 * and none gets it back.
 *
 * Most apps have no such service: for them only the package manager is
 * asked, and neither the notification service nor the setting is
 * reached, which then cannot fail them.
 */
@SuppressLint("PrivateApi")
internal class SpecialAccess(
    private val services: (uid: Int, permission: String) -> Set<String>,
    private val settings: SettingsStore,
    private val requireOwner: (Int) -> Unit,
) : Taking {
    /** [packages] is an `IPackageManager`. */
    constructor(
        packages: Any,
        settings: SettingsStore = FallbackSettingsStore(BinderSettingsStore(), CmdSettingsStore(), System.err::println),
    ) : this(DeclaredServices(packages)::forUid, settings, Framework::requireOwner)

    // All accessibility services for a user share one setting.
    private val accessibilityUpdates = Any()

    private val notificationType by lazy { Class.forName("android.app.INotificationManager") }
    private val notifications by lazy {
        Class.forName("android.app.INotificationManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, Framework.systemService("notification") ?: error("no notification service"))!!
    }
    private val listeners by lazy { notificationType.getMethod("getEnabledNotificationListeners", INT) }

    // Whether the user set it, from Android 12 on.
    private val grantListener by lazy { notificationType.optionalMethod("setNotificationListenerAccessGrantedForUser", ComponentName::class.java, INT, BOOLEAN, BOOLEAN) }
    private val grantListenerBefore by lazy { notificationType.getMethod("setNotificationListenerAccessGrantedForUser", ComponentName::class.java, INT, BOOLEAN) }

    override fun state(uid: Int, name: String): PermissionState? {
        val services = services(uid, name)
        if (services.isEmpty()) return null
        return PermissionState.ofServices(given(uid, name, services))
    }

    override fun hold(uid: Int, name: String, mayChange: () -> Boolean): HoldResult {
        val services = services(uid, name)
        if (services.isEmpty()) return HoldResult.HELD
        if (name == NOTIFICATION_ACCESS) {
            val given = notificationListeners(uid).toSet() intersect services
            if (given.isEmpty()) return HoldResult.HELD
            if (!mayChange()) return HoldResult.SUSPENDED
            setNotificationListeners(uid, given, false)
            check(notificationListeners(uid).none { it in services }) { "the system did not take notification access away" }
        } else {
            val enabled = accessibilityServices(uid)
            if (enabled.none { it in services }) return HoldResult.HELD
            if (!mayChange()) return HoldResult.SUSPENDED
            val readback = updateAccessibility(uid, services, emptySet(), enabled)
            check(readback.none { it in services }) { "the system did not take accessibility access away" }
        }
        return HoldResult.CHANGED
    }

    override fun putBack(uid: Int, name: String, state: PermissionState?) {
        state ?: return
        val services = services(uid, name)
        if (services.isEmpty()) return
        // A service the app no longer has is given nothing.
        val wanted = state.services intersect services
        val readback = if (name == NOTIFICATION_ACCESS) {
            val given = notificationListeners(uid).toSet() intersect services
            setNotificationListeners(uid, wanted - given, true)
            setNotificationListeners(uid, given - wanted, false)
            notificationListeners(uid)
        } else {
            updateAccessibility(uid, services, wanted, accessibilityServices(uid))
        }
        check(readback.toSet() intersect services == wanted) { "the system did not give special access back" }
    }

    /** Those of [services] that have [name] now. */
    private fun given(uid: Int, name: String, services: Set<String>): Set<String> = (if (name == NOTIFICATION_ACCESS) notificationListeners(uid) else accessibilityServices(uid)).toSet() intersect services

    private fun notificationListeners(uid: Int): List<String> = (listeners.call(notifications, userOf(uid)) as List<*>?).orEmpty()
        .filterIsInstance<ComponentName>()
        .map { it.flattenToString() }

    /** As the setting lists them, in its order, each in its long form. */
    private fun accessibilityServices(uid: Int): List<String> = settings.getSecure(userOf(uid), ACCESSIBILITY_SERVICES)
        ?.trim()
        .takeUnless { it == "null" }.orEmpty()
        .split(':')
        .mapNotNull { service ->
            val slash = service.indexOf('/')
            if (slash == -1 || slash == service.lastIndex) return@mapNotNull null
            // Like ComponentName.unflattenFromString, expand a relative class name.
            if (service[slash + 1] != '.') return@mapNotNull service
            val pkg = service.substring(0, slash)
            "$pkg/$pkg${service.substring(slash + 1)}"
        }

    private fun setNotificationListeners(uid: Int, services: Set<String>, given: Boolean) {
        if (services.isEmpty()) return
        requireOwner(uid)
        for (service in services) {
            val component = ComponentName.unflattenFromString(service) ?: continue
            val withUserSet = grantListener
            if (withUserSet != null) {
                withUserSet.call(notifications, component, userOf(uid), given, true)
            } else {
                grantListenerBefore.call(notifications, component, userOf(uid), given)
            }
        }
    }

    private fun updateAccessibility(uid: Int, services: Set<String>, wanted: Set<String>, initial: List<String>): List<String> = synchronized(accessibilityUpdates) {
        updateAccessibilityServices(
            services,
            wanted,
            read = { accessibilityServices(uid) },
            write = {
                requireOwner(uid)
                settings.putSecure(userOf(uid), ACCESSIBILITY_SERVICES, it.joinToString(":"))
            },
            initial = initial,
        )
    }
}

@SuppressLint("PrivateApi")
private class DeclaredServices(private val packages: Any) {
    private val packageType = Class.forName("android.content.pm.IPackageManager")
    private val packagesForUid = packageType.getMethod("getPackagesForUid", INT)
    private val packageInfo = packageType.optionalMethod("getPackageInfo", STRING, LONG, INT)
    private val packageInfoInt = if (packageInfo == null) packageType.getMethod("getPackageInfo", STRING, INT, INT) else null

    /** The services of [uid]'s packages the system binds with [permission], as `<package>/<class>`. */
    fun forUid(uid: Int, permission: String): Set<String> {
        val names = packagesForUid.call(packages, uid) as Array<*>? ?: return emptySet()
        return names.filterIsInstance<String>().flatMap { pkg ->
            val info = if (packageInfo != null) {
                packageInfo.call(packages, pkg, PackageManager.GET_SERVICES.toLong(), userOf(uid))
            } else {
                packageInfoInt!!.call(packages, pkg, PackageManager.GET_SERVICES, userOf(uid))
            } as PackageInfo?
            info?.services.orEmpty().filter { it.permission == permission }.map { "${it.packageName}/${it.name}" }
        }.toSet()
    }
}

internal fun updateAccessibilityServices(
    services: Set<String>,
    enabled: Set<String>,
    read: () -> List<String>,
    write: (List<String>) -> Unit,
    initial: List<String> = read(),
): List<String> {
    fun updated(current: List<String>): List<String> = current - (services - enabled) + (enabled - current.toSet())

    var current = initial
    repeat(ACCESSIBILITY_UPDATE_LIMIT) {
        val wanted = updated(current)
        if (wanted == current) return current
        // Settings replaces the whole list. Confirm the merge's input as
        // close to the write as possible and keep any observed newer list.
        val confirmed = read()
        if (confirmed == current) {
            write(wanted)
            current = read()
            if (current - services == confirmed - services) return current
        } else {
            current = confirmed
        }
    }
    check(updated(current) == current) { "accessibility services changed during update" }
    return current
}
