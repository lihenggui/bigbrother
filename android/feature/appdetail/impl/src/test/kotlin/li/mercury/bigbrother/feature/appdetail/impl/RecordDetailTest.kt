package li.mercury.bigbrother.feature.appdetail.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import li.mercury.bigbrother.feature.appdetail.impl.RecordDetail.App
import li.mercury.bigbrother.feature.appdetail.impl.RecordDetail.Plain

class RecordDetailTest {
    @Test
    fun givenServiceCallAboutAnApp_whenDetailed_thenOnlyTheAppIsKept() {
        assertEquals(
            App("com.devadvance.rootcloak"),
            recordDetail("binder:android.content.pm.IPackageManager/getPackageInfo#com.devadvance.rootcloak"),
        )
        assertNull(recordDetail("binder:android.content.IClipboard/getPrimaryClip"))
        assertEquals(
            App("eu.chainfire.supersu"),
            recordDetail("binder:android.content.pm.IPackageManager/getPackageInfo#!eu.chainfire.supersu"),
        )
    }

    @Test
    fun givenCommandOrAddress_whenDetailed_thenKeptAsIs() {
        assertEquals(Plain("which su"), recordDetail("exec:which su"))
        assertEquals(Plain("127.0.0.1:8080"), recordDetail("net://127.0.0.1:8080"))
    }

    @Test
    fun givenRecordTheTitleNames_whenDetailed_thenNoDetail() {
        assertNull(recordDetail("/proc/sys/kernel/random/boot_id"))
        assertNull(recordDetail("content://sms"))
        assertNull(recordDetail("setting:secure/android_id"))
        assertNull(recordDetail("appop:android:read_clipboard"))
    }

    @Test
    fun givenCallsAboutTheSameApp_whenDetailed_thenAppListedOnce() {
        assertEquals(
            listOf(App("com.a")),
            recordDetails(
                listOf(
                    "binder:android.content.pm.IPackageManager/getPackageInfo#com.a",
                    "binder:android.content.pm.IPackageManager/getApplicationInfo#com.a",
                    "binder:android.content.pm.IPackageManager/getInstalledPackages",
                ),
                titled = true,
            ),
        )
    }

    @Test
    fun givenUntitledRow_whenDetailed_thenEachRecordListedOnceAsIs() {
        assertEquals(
            listOf(Plain("/sys/x"), Plain("/sys/y")),
            recordDetails(listOf("/sys/x", "/sys/y", "/sys/x"), titled = false),
        )
    }
}
