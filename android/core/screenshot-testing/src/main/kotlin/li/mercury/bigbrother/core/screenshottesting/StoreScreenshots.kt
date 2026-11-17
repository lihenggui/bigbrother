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

package li.mercury.bigbrother.core.screenshottesting

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions
import com.github.takahirom.roborazzi.RoborazziOptions.RecordOptions
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import java.io.File

/**
 * The kinds of screenshot a store listing has, by fastlane's directory
 * names, with the screen each is taken on.
 */
enum class StoreScreenshotType(val directory: String, val spec: String) {
    /** A current phone, at xxhdpi: 1236 × 2745 pixels. */
    PHONE("phoneScreenshots", DefaultTestDevices.PHONE.spec),

    /** A 10-inch tablet in landscape, at hdpi: 1920 × 1200 pixels. */
    TEN_INCH("tenInchScreenshots", "spec:shape=Normal,width=1280,height=800,unit=dp,dpi=240"),
}

/** Store screenshots are shown as they are, so they are kept at full size. */
private val StoreRoborazziOptions = RoborazziOptions(
    compareOptions = CompareOptions(changeThreshold = 0f),
    recordOptions = RecordOptions(resizeScale = 1.0),
)

/**
 * Captures [body] as the store screenshot [name] (`1_overview`) of the
 * locale the test runs in, set by its qualifiers (`@Config(qualifiers =
 * "zh-rCN")`), into fastlane's layout at the repository's root:
 * `fastlane/metadata/android/<locale>/images/<type>/<name>.png`. The README
 * shows the same files. They are golden images too: `./gradlew test`
 * compares them, `./gradlew recordRoborazziDebug` writes them anew.
 */
fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.captureStoreScreenshot(
    type: StoreScreenshotType,
    name: String,
    accessibilitySuppressions: Matcher<in AccessibilityViewCheckResult> = Matchers.not(Matchers.anything()),
    body: @Composable () -> Unit,
) {
    // Fastlane names locales as BCP 47 tags: en-US, zh-CN.
    val locale = activity.resources.configuration.locales[0].toLanguageTag()
    capture(
        file = repositoryDir().resolve("fastlane/metadata/android/$locale/images/${type.directory}/$name.png"),
        deviceSpec = type.spec,
        roborazziOptions = StoreRoborazziOptions,
        accessibilitySuppressions = accessibilitySuppressions,
        darkMode = false,
        body = body,
    )
}

/**
 * The repository's root: the parent of the Gradle build, which is the
 * nearest directory up from the module under test (the tests' working
 * directory) with a settings script.
 */
private fun repositoryDir(): File {
    val build = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { it.resolve("settings.gradle.kts").isFile }
        ?: error("No Gradle build above ${File("").absolutePath}")
    return build.parentFile
}
