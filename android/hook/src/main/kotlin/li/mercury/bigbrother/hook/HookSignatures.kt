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

package li.mercury.bigbrother.hook

/** Only use argument positions whose meaning is known for the whole declaration. */
internal data class HookSignature(val returns: String, val parameters: List<String>) {
    fun matches(returns: String, vararg parameters: String): Boolean = this.returns == returns && this.parameters == parameters.asList()
}

internal data class FilterArguments(val callingUid: Int, val target: Int)

internal fun locateFilterArguments(signature: HookSignature): FilterArguments? = when {
    signature.matches("boolean", "int", "com.android.server.pm.SettingBase", "com.android.server.pm.PackageSetting", "int") ||
        signature.matches("boolean", "int", "com.android.server.pm.PackageSetting", "com.android.server.pm.PackageSetting", "int") -> FilterArguments(0, 2)

    signature.matches("boolean", "com.android.server.pm.snapshot.PackageDataSnapshot", "int", "java.lang.Object", "com.android.server.pm.pkg.PackageStateInternal", "int") -> FilterArguments(1, 3)

    else -> null
}

internal fun isSettingsCall(signature: HookSignature): Boolean = signature.matches("android.os.Bundle", "android.content.AttributionSource", "java.lang.String", "java.lang.String", "java.lang.String", "android.os.Bundle")

internal fun isSettingsQuery(signature: HookSignature): Boolean = signature.matches("android.database.Cursor", "android.content.AttributionSource", "android.net.Uri", "java.lang.String[]", "android.os.Bundle", "android.os.ICancellationSignal")

internal data class PermissionArguments(val permission: Int, val subject: Int, val user: Int = -1)

internal fun locatePackagePermission(signature: HookSignature): PermissionArguments? = when {
    signature.matches("int", "java.lang.String", "java.lang.String", "int") -> PermissionArguments(1, 0, 2)

    signature.matches("int", "java.lang.String", "java.lang.String", "int", "int") ||
        signature.matches("int", "java.lang.String", "java.lang.String", "java.lang.String", "int") -> PermissionArguments(1, 0, 3)

    else -> null
}

internal fun locateUidPermission(signature: HookSignature): PermissionArguments? = when {
    signature.matches("int", "java.lang.String", "int") -> PermissionArguments(0, 1)

    signature.matches("int", "int", "java.lang.String") ||
        signature.matches("int", "int", "java.lang.String", "int") -> PermissionArguments(1, 0)

    else -> null
}

internal fun isIdentifierBind(signature: HookSignature): Boolean {
    val prefix = listOf("android.app.IApplicationThread", "android.os.IBinder", "android.content.Intent", "java.lang.String", "android.app.IServiceConnection")
    val suffix = BIND_SUFFIXES.firstOrNull { signature.parameters.takeLast(it.size) == it } ?: return false
    val middle = signature.parameters.drop(prefix.size).dropLast(suffix.size)
    return signature.returns == "int" &&
        signature.parameters.take(prefix.size) == prefix &&
        middle in BIND_VARIANTS
}

private val BIND_SUFFIXES = listOf(
    listOf("java.lang.String", "int"),
    // OxygenOS 16 adds an overload that the binder entry point calls, and
    // has the first one pass through to it. The hook reads the intent
    // alone, which both have in the same place.
    listOf("java.lang.String", "int", "long"),
)

private val BIND_VARIANTS = setOf(
    listOf("int", "java.lang.String"),
    listOf("int", "java.lang.String", "boolean", "int", "java.lang.String"),
    listOf("long", "java.lang.String", "boolean", "int", "java.lang.String", "android.app.IApplicationThread"),
)

internal fun isIdentifierProvider(signature: HookSignature): Boolean = signature.matches("android.app.ContentProviderHolder", "android.app.IApplicationThread", "java.lang.String", "java.lang.String", "int", "boolean")
