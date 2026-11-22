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
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val NOTIFICATION_ACCESS = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

/** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. */
private const val ACCESSIBILITY_SERVICES = "enabled_accessibility_services"

private const val ACCESSIBILITY_UPDATE_LIMIT = 3

private const val CMD = "/system/bin/cmd"

/**
 * The access to the notifications and to the screen the user gave an
 * app's services ([AppOpsGuardProtocol.SPECIAL_ACCESS]): notification
 * listeners through the notification service ([notifications], an
 * `INotificationManager`), as the system's settings grant it;
 * accessibility services through the setting the accessibility service
 * reads, changed with the `settings` command, as the system's settings
 * change it. A service is one or the other by the permission it is
 * bound with, which it declares; [packages] is an `IPackageManager`.
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
internal class SpecialAccess(private val packages: Any) : Taking {
    private val packageType = Class.forName("android.content.pm.IPackageManager")
    private val packagesForUid = packageType.getMethod("getPackagesForUid", INT)
    private val packageInfo = packageType.optionalMethod("getPackageInfo", STRING, LONG, INT)
    private val packageInfoInt = if (packageInfo == null) packageType.getMethod("getPackageInfo", STRING, INT, INT) else null

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
        val given = given(uid, name, services)
        if (given.isEmpty()) return HoldResult.HELD
        if (!mayChange()) return HoldResult.SUSPENDED
        set(uid, name, given, false)
        check(given(uid, name, services).isEmpty()) { "the system did not take $name away" }
        return HoldResult.CHANGED
    }

    override fun putBack(uid: Int, name: String, state: PermissionState?) {
        state ?: return
        val services = services(uid, name)
        if (services.isEmpty()) return
        // A service the app no longer has is given nothing.
        val wanted = state.services intersect services
        val given = given(uid, name, services)
        set(uid, name, wanted - given, true)
        set(uid, name, given - wanted, false)
        check(given(uid, name, services) == wanted) { "the system did not give $name back" }
    }

    /** The services of [uid]'s packages the system binds with [name], as `<package>/<class>`. */
    private fun services(uid: Int, name: String): Set<String> {
        val names = packagesForUid.call(packages, uid) as Array<*>? ?: return emptySet()
        return names.filterIsInstance<String>().flatMap { pkg ->
            val info = if (packageInfo != null) {
                packageInfo.call(packages, pkg, PackageManager.GET_SERVICES.toLong(), userOf(uid))
            } else {
                packageInfoInt!!.call(packages, pkg, PackageManager.GET_SERVICES, userOf(uid))
            } as PackageInfo?
            info?.services.orEmpty().filter { it.permission == name }.map { "${it.packageName}/${it.name}" }
        }.toSet()
    }

    /** Those of [services] that have [name] now. */
    private fun given(uid: Int, name: String, services: Set<String>): Set<String> = (if (name == NOTIFICATION_ACCESS) notificationListeners(uid) else accessibilityServices(uid)).toSet() intersect services

    private fun notificationListeners(uid: Int): List<String> = (listeners.call(notifications, userOf(uid)) as List<*>?).orEmpty()
        .filterIsInstance<ComponentName>()
        .map { it.flattenToString() }

    /** As the setting lists them, in its order, each in its long form. */
    private fun accessibilityServices(uid: Int): List<String> = settings("get", "--user", userOf(uid).toString(), "secure", ACCESSIBILITY_SERVICES)
        .takeUnless { it == "null" }.orEmpty()
        .split(':')
        .mapNotNull { ComponentName.unflattenFromString(it)?.flattenToString() }

    /** Give [services] [name], or take it from them. */
    private fun set(uid: Int, name: String, services: Set<String>, given: Boolean) {
        if (services.isEmpty()) return
        Framework.requireOwner(uid)
        if (name == NOTIFICATION_ACCESS) {
            for (service in services) {
                val component = ComponentName.unflattenFromString(service) ?: continue
                val withUserSet = grantListener
                if (withUserSet != null) {
                    withUserSet.call(notifications, component, userOf(uid), given, true)
                } else {
                    grantListenerBefore.call(notifications, component, userOf(uid), given)
                }
            }
            return
        }
        updateAccessibilityServices(
            services,
            given,
            read = { accessibilityServices(uid) },
            write = { settings("put", "--user", userOf(uid).toString(), "secure", ACCESSIBILITY_SERVICES, it.joinToString(":")) },
        )
    }

    /** Run the `settings` command; what it printed. */
    private fun settings(vararg args: String): String {
        val process = ProcessBuilder(listOf(CMD, "settings") + args).redirectErrorStream(true).start()
        // Drain the pipe while waiting so a full buffer cannot hold the child open.
        val printed = FutureTask { process.inputStream.bufferedReader().use { it.readText().trim() } }
        thread(isDaemon = true, name = "settings-output") { printed.run() }
        try {
            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) { "settings ${args.first()} failed" }
            return printed.get(250, TimeUnit.MILLISECONDS)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

internal fun updateAccessibilityServices(services: Set<String>, given: Boolean, read: () -> List<String>, write: (List<String>) -> Unit) {
    fun updated(current: List<String>): List<String> = if (given) current + (services - current.toSet()) else current - services

    var current = read()
    repeat(ACCESSIBILITY_UPDATE_LIMIT) {
        val wanted = updated(current)
        if (wanted == current) return
        // Settings replaces the whole list. Confirm the merge's input as
        // close to the write as possible and keep any observed newer list.
        val confirmed = read()
        if (confirmed == current) {
            write(wanted)
            current = read()
            if (current - services == confirmed - services) return
        } else {
            current = confirmed
        }
    }
    check(updated(current) == current) { "accessibility services changed during update" }
}
