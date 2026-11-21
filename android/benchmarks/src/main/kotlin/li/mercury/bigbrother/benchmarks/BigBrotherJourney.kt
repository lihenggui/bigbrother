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

package li.mercury.bigbrother.benchmarks

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

internal const val APP_PACKAGE = "li.mercury.bigbrother"
private const val UI_TIMEOUT_MS = 10_000L

internal class BigBrotherJourney(private val scope: MacrobenchmarkScope) {
    private val device = scope.device
    private val resources by lazy {
        InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(APP_PACKAGE)
    }

    fun launch() {
        scope.startActivityAndWait()
        val overview = selectedDestination("nav_overview")
        val skipLabel = string("guide_skip")
        val skip = By.pkg(APP_PACKAGE).text(skipLabel)
        val guideOrOverview = Condition<UiDevice, UiObject2?> { device ->
            device.findObject(skip) ?: device.findObject(overview)
        }
        val landing = checkNotNull(device.wait(guideOrOverview, UI_TIMEOUT_MS)) { "Missing first-launch guide or overview" }
        if (landing.text == skipLabel) {
            landing.click()
            await(overview)
        }
    }

    fun visitDestinationsAndScroll() {
        openDestination("nav_apps")
        val scrollable = By.pkg(APP_PACKAGE).scrollable(true)
        device.wait(Until.findObject(scrollable), 3_000)?.scroll(Direction.DOWN, 0.8f)

        openDestination("nav_timeline")
        openDestination("nav_jail")
        openDestination("nav_overview")

        // The records list needs data from the daemon. Settings exercises
        // scrolling a populated list even on a device without root.
        await(By.pkg(APP_PACKAGE).desc(string("settings"))).click()
        await(By.pkg(APP_PACKAGE).text(string("settings_title")))
        await(scrollable).scroll(Direction.DOWN, 0.8f)
    }

    private fun openDestination(label: String) {
        await(navigation(label)).click()
        await(selectedDestination(label))
        device.waitForIdle()
    }

    private fun navigation(label: String): BySelector = By.pkg(APP_PACKAGE).text(string(label))

    private fun selectedDestination(label: String): BySelector = By.pkg(APP_PACKAGE).selected(true).hasDescendant(navigation(label))

    private fun string(name: String): String = resources.getString(resources.getIdentifier(name, "string", APP_PACKAGE))

    private fun await(selector: BySelector): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), UI_TIMEOUT_MS)) { "Missing UI element: $selector" }
}
