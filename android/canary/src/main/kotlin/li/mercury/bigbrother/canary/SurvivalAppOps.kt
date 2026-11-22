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

package li.mercury.bigbrother.canary

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlin.system.exitProcess

/** Runs as root outside the tested UID so it can see even changes that kill the activity. */
@SuppressLint("PrivateApi")
object SurvivalAppOps {
    private const val CALLBACK = "com.android.internal.app.IAppOpsCallback"
    private const val TAG = "BigBrotherSurvival"

    @JvmStatic
    fun main(args: Array<String>) {
        val uid = args[0].toInt()
        val generation = args[1]
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "appops") as IBinder
        binder.linkToDeath({ exitProcess(1) }, 0)
        val service = Class.forName("com.android.internal.app.IAppOpsService")
        val appOps = Class.forName("${service.name}\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val asCallback = Class.forName("$CALLBACK\$Stub").getMethod("asInterface", IBinder::class.java)
        val startWatching = service.getMethod("startWatchingMode", Int::class.javaPrimitiveType, String::class.java, Class.forName(CALLBACK))
        val codeOf = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
        for (op in listOf("manage_external_storage", "get_usage_stats")) {
            val opCode = codeOf.invoke(null, "android:$op") as Int
            // AppOps files each listener under its first op. Watching without
            // foreground flags keeps UID state changes from looking like mode changes.
            val listener = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(CALLBACK)
                    if (data.readInt() == opCode && data.readInt() == uid) {
                        Log.i(TAG, "$generation appop $uid $op")
                    }
                    return true
                }
            }
            startWatching.invoke(appOps, opCode, null, asCallback.invoke(null, listener))
        }
        println("ready")
        // The shell owns this observer. Losing the connection also ends it.
        if (System.`in`.read() == -1) return
        println("stopped")
    }
}
