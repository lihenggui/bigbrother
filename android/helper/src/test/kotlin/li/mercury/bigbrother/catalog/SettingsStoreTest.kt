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

import li.mercury.bigbrother.catalog.Taking.HoldResult
import java.lang.reflect.InvocationTargetException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun givenWorkingPrimary_whenReadingAndWriting_thenOnlyPrimaryIsUsed() {
        val primary = FakeSettingsStore()
        val fallback = FakeSettingsStore()
        val warnings = mutableListOf<String>()
        val store = FallbackSettingsStore(primary, fallback) { warnings += it }

        assertEquals(null, store.getSecure(USER, KEY))
        store.putSecure(USER, KEY, SERVICE)
        assertEquals(SERVICE, store.getSecure(USER, KEY))

        assertEquals(listOf(USER to KEY, USER to KEY), primary.reads)
        assertEquals(listOf(Triple(USER, KEY, SERVICE)), primary.writes)
        assertTrue(fallback.reads.isEmpty())
        assertTrue(fallback.writes.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun givenPrimaryReadFails_whenUsingStoreAgain_thenAllOperationsUseFallbackAndWarnOnce() {
        val primary = FakeSettingsStore(readFailure = InvocationTargetException(SecurityException(SERVICE)))
        val fallback = FakeSettingsStore(SERVICE)
        val warnings = mutableListOf<String>()
        val store = FallbackSettingsStore(primary, fallback) { warnings += it }

        assertEquals(SERVICE, store.getSecure(USER, KEY))
        store.putSecure(USER, KEY, "")
        assertEquals("", store.getSecure(USER, KEY))

        assertEquals(listOf(USER to KEY), primary.reads)
        assertTrue(primary.writes.isEmpty())
        assertEquals(listOf(USER to KEY, USER to KEY), fallback.reads)
        assertEquals(listOf(Triple(USER, KEY, "")), fallback.writes)
        assertEquals(listOf("binder settings unavailable, falling back to cmd: SecurityException"), warnings)
    }

    @Test
    fun givenPrimaryWriteFails_whenWritingAndReading_thenFallbackReceivesTheSameWriteAndStaysActive() {
        val primary = FakeSettingsStore(writeFailure = NoSuchMethodException(SERVICE))
        val fallback = FakeSettingsStore(SERVICE)
        val warnings = mutableListOf<String>()
        val store = FallbackSettingsStore(primary, fallback) { warnings += it }

        store.putSecure(USER, KEY, "")
        assertEquals("", store.getSecure(USER, KEY))
        store.putSecure(USER, KEY, SERVICE)
        assertEquals(SERVICE, store.getSecure(USER, KEY))

        assertEquals(listOf(Triple(USER, KEY, "")), primary.writes)
        assertTrue(primary.reads.isEmpty())
        assertEquals(listOf(Triple(USER, KEY, ""), Triple(USER, KEY, SERVICE)), fallback.writes)
        assertEquals(listOf("binder settings unavailable, falling back to cmd: NoSuchMethodException"), warnings)
    }

    @Test
    fun givenConcurrentPrimaryFailures_whenBothCallsFallBack_thenOnlyOneWarningIsLogged() {
        val inPrimary = CountDownLatch(2)
        val primary = object : SettingsStore {
            override fun getSecure(userId: Int, key: String): String? {
                inPrimary.countDown()
                check(inPrimary.await(5, SECONDS))
                throw SecurityException(SERVICE)
            }

            override fun putSecure(userId: Int, key: String, value: String) = error("unexpected write")
        }
        val fallback = object : SettingsStore {
            override fun getSecure(userId: Int, key: String) = SERVICE
            override fun putSecure(userId: Int, key: String, value: String) = error("unexpected write")
        }
        val warnings = Collections.synchronizedList(mutableListOf<String>())
        val store = FallbackSettingsStore(primary, fallback) { warnings += it }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String?> { store.getSecure(USER, KEY) }
            val second = executor.submit<String?> { store.getSecure(USER, KEY) }
            assertEquals(SERVICE, first.get(5, SECONDS))
            assertEquals(SERVICE, second.get(5, SECONDS))
            assertEquals(listOf("binder settings unavailable, falling back to cmd: SecurityException"), warnings)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun givenSettingsInAnotherUser_whenReading_thenBinderReceivesThatUserAndReturnsTheValue() {
        val client = FakeBinderClient(SERVICE)
        val store = BinderSettingsStore(client)

        assertEquals(SERVICE, store.getSecure(USER, KEY))

        assertEquals(listOf(Request("settings", "GET_secure", KEY, mapOf("_user" to USER))), client.requests)
        val reference = client.acquired.single()
        assertEquals("settings", reference.authority)
        assertEquals(USER, reference.userId)
        assertEquals(listOf(reference), client.released)
    }

    @Test
    fun givenMissingAndEmptyValues_whenReading_thenBinderKeepsThemDistinctAndReleasesEachReference() {
        val client = FakeBinderClient()
        val store = BinderSettingsStore(client)

        assertEquals(null, store.getSecure(0, KEY))
        client.value = ""
        assertEquals("", store.getSecure(0, KEY))

        assertEquals(2, client.requests.size)
        assertNotEquals(client.acquired[0].token, client.acquired[1].token)
        assertEquals(client.acquired, client.released)
    }

    @Test
    fun givenLastServiceInAnotherUser_whenClearingIt_thenBinderSendsAnEmptyStringAndReleasesTheProvider() {
        val client = FakeBinderClient()
        val store = BinderSettingsStore(client)

        store.putSecure(USER, KEY, "")

        assertEquals(listOf(Request("settings", "PUT_secure", KEY, mapOf("_user" to USER, "value" to ""))), client.requests)
        assertEquals(USER, client.acquired.single().userId)
        assertEquals(client.acquired, client.released)
    }

    @Test
    fun givenProviderCallThrows_whenReadingOrWriting_thenBinderStillReleasesTheProvider() {
        val failure = SecurityException(SERVICE)
        val client = FakeBinderClient(failure = failure)
        val store = BinderSettingsStore(client)

        assertSame(failure, assertFailsWith<SecurityException> { store.getSecure(USER, KEY) })
        assertSame(failure, assertFailsWith<SecurityException> { store.putSecure(USER, KEY, "") })

        assertEquals(listOf("GET_secure", "PUT_secure"), client.requests.map { it.method })
        assertEquals(2, client.acquired.size)
        assertEquals(client.acquired, client.released)
    }

    @Test
    fun givenNullEmptyAndCmdNullSettings_whenReadingSpecialAccess_thenEachMeansNoEnabledService() {
        for (value in listOf(null, "", "null")) {
            val settings = FakeSettingsStore(value)
            val access = specialAccess(settings) { error("unexpected owner check") }

            assertEquals(emptySet(), access.state(UID, ACCESS)?.services)
            assertEquals(listOf(USER to KEY), settings.reads)
            assertTrue(settings.writes.isEmpty())
        }
    }

    @Test
    fun givenTheLastAccessibilityService_whenHoldingThenRestoring_thenTheSettingIsClearedAndRestoredOnce() {
        val settings = FakeSettingsStore("com.example.app/.Service")
        var ownerChecks = 0
        val access = specialAccess(settings) {
            assertEquals(UID, it)
            assertEquals(settings.writes.size + 1, ++ownerChecks)
        }
        val before = access.state(UID, ACCESS)

        assertEquals(setOf(SERVICE), before?.services)
        assertEquals(HoldResult.CHANGED, access.hold(UID, ACCESS) { true })
        assertEquals(HoldResult.HELD, access.hold(UID, ACCESS) { error("already held") })
        access.putBack(UID, ACCESS, before)

        assertEquals(2, ownerChecks)
        assertEquals(listOf(Triple(USER, KEY, ""), Triple(USER, KEY, SERVICE)), settings.writes)
    }

    @Test
    fun givenOtherEnabledServices_whenHoldingAccessibility_thenOnlyTheTargetServiceIsRemoved() {
        val settings = FakeSettingsStore("com.example.reader/.Reader::invalid:$SERVICE:$OTHER")
        val access = specialAccess(settings) { assertEquals(UID, it) }

        assertEquals(HoldResult.CHANGED, access.hold(UID, ACCESS) { true })

        assertEquals(List(3) { USER to KEY }, settings.reads)
        assertEquals(listOf(Triple(USER, KEY, "$READER:$OTHER")), settings.writes)
    }

    @Test
    fun givenTheInstallationChanged_whenHoldingAccessibility_thenTheOwnerCheckPreventsAnyWrite() {
        val settings = FakeSettingsStore(SERVICE)
        val access = specialAccess(settings) {
            assertEquals(UID, it)
            error("installation changed")
        }

        val failure = assertFailsWith<IllegalStateException> { access.hold(UID, ACCESS) { true } }

        assertEquals("installation changed", failure.message)
        assertTrue(settings.writes.isEmpty())
    }

    private fun specialAccess(settings: SettingsStore, requireOwner: (Int) -> Unit) = SpecialAccess(
        services = { uid, permission ->
            assertEquals(UID, uid)
            assertEquals(ACCESS, permission)
            setOf(SERVICE)
        },
        settings = settings,
        requireOwner = requireOwner,
    )

    private class FakeSettingsStore(
        private var value: String? = null,
        private val readFailure: Exception? = null,
        private val writeFailure: Exception? = null,
    ) : SettingsStore {
        val reads = mutableListOf<Pair<Int, String>>()
        val writes = mutableListOf<Triple<Int, String, String>>()

        override fun getSecure(userId: Int, key: String): String? {
            reads += userId to key
            readFailure?.let { throw it }
            return value
        }

        override fun putSecure(userId: Int, key: String, value: String) {
            writes += Triple(userId, key, value)
            writeFailure?.let { throw it }
            this.value = value
        }
    }

    private data class Reference(val authority: String, val userId: Int, val token: Any)

    private data class Request(val authority: String, val method: String, val key: String, val extras: Map<String, Any>)

    private class FakeBinderClient(var value: String? = null, private val failure: Exception? = null) : BinderSettingsClient {
        private val providerHolder = Any()
        val acquired = mutableListOf<Reference>()
        val released = mutableListOf<Reference>()
        val requests = mutableListOf<Request>()

        override fun newToken(): Any = Any()

        override fun acquire(authority: String, userId: Int, token: Any): Any {
            acquired += Reference(authority, userId, token)
            return providerHolder
        }

        override fun call(holder: Any, authority: String, method: String, key: String, extras: Map<String, Any>): String? {
            assertSame(providerHolder, holder)
            assertEquals(released.size + 1, acquired.size)
            requests += Request(authority, method, key, extras)
            failure?.let { throw it }
            return value
        }

        override fun release(authority: String, userId: Int, token: Any) {
            released += Reference(authority, userId, token)
        }
    }

    private companion object {
        const val USER = 10
        const val UID = 1_010_100
        const val KEY = "enabled_accessibility_services"
        const val ACCESS = "android.permission.BIND_ACCESSIBILITY_SERVICE"
        const val SERVICE = "com.example.app/com.example.app.Service"
        const val READER = "com.example.reader/com.example.reader.Reader"
        const val OTHER = "com.example.other/com.example.other.Service"
    }
}
