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

import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.FLAG_USER_FIXED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_ALLOWED
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_FOREGROUND
import li.mercury.bigbrother.catalog.AppOpsGuardProtocol.MODE_IGNORED
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.BACKGROUND
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.CAMERA
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.COARSE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.CONTACTS
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.FINE
import li.mercury.bigbrother.catalog.FakeAndroid.Companion.REVOKED_COMPAT
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertEquals

/**
 * Plays each case of `testdata/permissions/matrix.tsv`, which
 * `scripts/check-permissions.sh` plays on a device: the app enters the
 * sandbox with its permission in one state, things change while it is in,
 * and it leaves. The helper's own code runs, on [FakeAndroid], told what
 * the daemon would tell it ([DaemonDouble]).
 */
@RunWith(Parameterized::class)
class PermissionMatrixTest(private val case: Case) {
    @Test
    fun givenPermissionState_whenSandboxedChangedAndLeft_thenEndsAsTheMatrixSays() {
        val android = FakeAndroid()
        val daemon = DaemonDouble(android)
        val subject = SUBJECTS.getValue(case.subject)
        before(android, subject, case.before)
        daemon.enable()
        daemon.check()
        // Each group runs before the helper looks; the leave joins the last
        // one when it is to come right after it.
        val groups = (if (case.steps == "-") emptyList() else case.steps.split(',').map { it.split('&') }).toMutableList()
        val leave = case.leave.removePrefix("&")
        if (case.leave.startsWith("&") && groups.isNotEmpty()) groups[groups.lastIndex] = groups.last() + "leave:$leave" else groups += listOf("leave:$leave")
        for (group in groups) {
            // A restart in a group stops the helper or the daemon where it
            // stands; what follows it in the group happens before it is back.
            var restart: String? = null
            for (step in group) {
                if (step.startsWith("restart:")) restart = step.removePrefix("restart:") else play(android, daemon, subject, step)
            }
            when (restart) {
                null -> Unit
                "helper" -> daemon.restartHelper()
                "daemon" -> daemon.restartDaemon()
                else -> error("no restart:$restart")
            }
            // The system has settled by the time the helper looks.
            android.settle()
            daemon.check()
        }

        val granted = android.isGranted(subject.permission)
        val mode = android.mode(subject.op)
        val seen = mutableListOf(
            if (granted) "granted" else "denied",
            MODE_NAMES.getValue(mode),
            subject.reads(android),
            "revoked_compat=${android.hasFlag(subject.permission, REVOKED_COMPAT)}",
            "user_fixed=${android.hasFlag(subject.permission, FLAG_USER_FIXED)}",
        )
        val expected = mutableListOf(case.permission, case.mode, case.reads, "revoked_compat=${expectedRevokedCompat(case)}", "user_fixed=false")
        if (case.subject == "location") {
            // Marked for any mode but allowed, while it is granted.
            seen += "background_revoked_compat=${android.hasFlag(BACKGROUND, REVOKED_COMPAT)}"
            expected += "background_revoked_compat=${android.isGranted(BACKGROUND) && mode != MODE_ALLOWED}"
        }
        assertEquals(expected, seen, case.name)
    }

    private fun before(android: FakeAndroid, subject: Subject, state: String) {
        when (state) {
            "granted" -> subject.permissions.forEach(android::pmGrant)

            "denied" -> Unit

            "ignored" -> {
                subject.permissions.forEach(android::pmGrant)
                subject.ignoredOps.forEach { android.userSetsMode(it, MODE_IGNORED) }
            }

            "always" -> (subject.permissions + BACKGROUND).forEach(android::pmGrant)

            "approximate" -> android.pmGrant(COARSE)

            else -> error("no state $state")
        }
    }

    private fun play(android: FakeAndroid, daemon: DaemonDouble, subject: Subject, step: String) {
        val (verb, argument) = step.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        when (verb) {
            "pm", "settings" -> {
                val (change, named) = argument!!.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
                val permissions = named?.let { listOf(PERMISSIONS.getValue(it)) } ?: when {
                    verb == "settings" && change == "revoke" -> subject.deniedInSettings
                    else -> subject.permissions
                }
                for (permission in permissions) {
                    when ("$verb:$change") {
                        "pm:grant" -> android.pmGrant(permission)
                        "pm:revoke" -> android.pmRevoke(permission)
                        "settings:grant" -> android.settingsGrant(permission)
                        "settings:revoke" -> android.settingsRevoke(permission)
                        else -> error("no step $step")
                    }
                }
            }

            "set" -> {
                val (item, mode) = if (argument!!.contains('=')) argument.split('=').let { it[0] to it[1] } else subject.item to argument
                daemon.set(item, mode)
            }

            "refuse" -> android.refuseNextFlagChange = true

            "late" -> android.syncsLate = true

            "leave" -> when (argument) {
                "disable" -> daemon.disable()

                "off" -> daemon.set(subject.item, "off")

                "uninstall" -> {
                    android.uninstall()
                    daemon.uninstalled()
                    android.install()
                    subject.permissions.forEach(android::pmGrant)
                }

                else -> error("no leave $argument")
            }

            else -> error("no step $step")
        }
    }

    /**
     * What a case looks at: the permissions its steps grant, those the
     * settings deny together, the one and the op it checks, and what the
     * app reads.
     */
    internal class Subject(val item: String, val permissions: List<String>, val deniedInSettings: List<String>, val permission: String, val op: String, val ignoredOps: List<String>, val reads: (FakeAndroid) -> String)

    /** One line of the matrix. */
    data class Case(val name: String, val subject: String, val before: String, val steps: String, val leave: String, val permission: String, val mode: String, val reads: String, val where: String) {
        override fun toString() = name
    }

    companion object {
        private val MODE_NAMES = mapOf(MODE_ALLOWED to "allow", MODE_IGNORED to "ignore", MODE_FOREGROUND to "foreground")

        private val PERMISSIONS = mapOf("fine" to FINE, "coarse" to COARSE, "background" to BACKGROUND)

        /**
         * Whether the subject's permission ends marked `REVOKED_COMPAT`: one
         * granted with its op ignored, and one the user ignored before
         * revoking it, which Android leaves marked whatever the sandbox
         * does. Nothing else the sandbox did may stay behind.
         */
        private fun expectedRevokedCompat(case: Case) = when (case.permission) {
            "granted" -> case.mode == "ignore"
            else -> case.before == "ignored" && case.leave != "uninstall"
        }

        /** The app is in the foreground as it reads. */
        private fun FakeAndroid.reads(permission: String, op: String) = isGranted(permission) && mode(op) != MODE_IGNORED

        private val SUBJECTS = mapOf(
            "contacts" to Subject("contacts", listOf(CONTACTS), listOf(CONTACTS), CONTACTS, "android:read_contacts", listOf("android:read_contacts")) {
                if (it.reads(CONTACTS, "android:read_contacts")) "yes" else "no"
            },
            "camera" to Subject("camera", listOf(CAMERA), listOf(CAMERA), CAMERA, "android:camera", listOf("android:camera")) {
                if (it.reads(CAMERA, "android:camera")) "yes" else "no"
            },
            // "Don't allow" denies background location too. With precise
            // location granted and its op ignored, the location service gives
            // no location at all.
            "location" to Subject("location", listOf(FINE, COARSE), listOf(BACKGROUND, FINE, COARSE), FINE, "android:fine_location", listOf("android:coarse_location", "android:fine_location")) {
                when {
                    it.isGranted(FINE) -> if (it.reads(FINE, "android:fine_location")) "precise" else "none"
                    it.reads(COARSE, "android:coarse_location") -> "approximate"
                    else -> "none"
                }
            },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = matrix().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                require(columns.size == 9) { "not nine columns: $line" }
                Case(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[6], columns[7], columns[8])
            }
            .filter { it.where == "both" || it.where == "jvm" }

        /** The matrix, found from the module's directory, where the tests run, upwards. */
        private fun matrix(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "testdata/permissions/matrix.tsv") }
            .first { it.isFile }
    }
}
