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

@file:OptIn(ExperimentalRoborazziApi::class, ExperimentalCoilApi::class)

package li.mercury.bigbrother.core.screenshottesting

import android.graphics.Bitmap.CompressFormat.PNG
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.rules.ActivityScenarioRule
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker.CheckLevel
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions
import com.github.takahirom.roborazzi.RoborazziOptions.RecordOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import li.mercury.bigbrother.core.designsystem.theme.BigBrotherTheme
import org.hamcrest.Matcher
import org.hamcrest.Matchers
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

val DefaultRoborazziOptions =
    RoborazziOptions(
        // Pixel-perfect matching
        compareOptions = CompareOptions(changeThreshold = 0f),
        // Reduce the size of the PNGs
        recordOptions = RecordOptions(resizeScale = 0.5),
    )

enum class DefaultTestDevices(val description: String, val spec: String) {
    PHONE("phone", "spec:shape=Normal,width=412,height=915,unit=dp,dpi=480"),
    FOLDABLE("foldable", "spec:shape=Normal,width=673,height=841,unit=dp,dpi=480"),
    TABLET("tablet", "spec:shape=Normal,width=1280,height=800,unit=dp,dpi=480"),
}

fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.captureMultiDevice(
    screenshotName: String,
    accessibilitySuppressions: Matcher<in AccessibilityViewCheckResult> = Matchers.not(Matchers.anything()),
    body: @Composable () -> Unit,
) {
    DefaultTestDevices.entries.forEach {
        this.captureForDevice(
            deviceName = it.description,
            deviceSpec = it.spec,
            screenshotName = screenshotName,
            body = body,
            accessibilitySuppressions = accessibilitySuppressions,
        )
    }
}

fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.captureForDevice(
    deviceName: String,
    deviceSpec: String,
    screenshotName: String,
    roborazziOptions: RoborazziOptions = DefaultRoborazziOptions,
    accessibilitySuppressions: Matcher<in AccessibilityViewCheckResult> = Matchers.not(Matchers.anything()),
    darkMode: Boolean = false,
    body: @Composable () -> Unit,
) {
    capture(
        file = File("src/test/screenshots/${screenshotName}_$deviceName.png"),
        deviceSpec = deviceSpec,
        roborazziOptions = roborazziOptions,
        accessibilitySuppressions = accessibilitySuppressions,
        darkMode = darkMode,
        body = body,
    )
}

/**
 * Shows [body] on a screen of [deviceSpec]'s size in the app's own color
 * scheme (dynamic colors would follow the test machine), with the demo
 * apps' icons, then checks its accessibility and captures it to [file].
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<ActivityScenarioRule<A>, A>.capture(
    file: File,
    deviceSpec: String,
    roborazziOptions: RoborazziOptions,
    accessibilitySuppressions: Matcher<in AccessibilityViewCheckResult>,
    darkMode: Boolean,
    body: @Composable () -> Unit,
) {
    val (width, height, dpi) = extractSpecs(deviceSpec)

    // Set qualifiers from specs
    RuntimeEnvironment.setQualifiers("+w${width}dp-h${height}dp-${dpi}dpi")

    this.activity.setContent {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            // AsyncImage asks this for images in inspection mode.
            LocalAsyncImagePreviewHandler provides DemoAppIcons.previewHandler,
        ) {
            DeviceConfigurationOverride(
                override = DeviceConfigurationOverride.DarkMode(darkMode),
            ) {
                BigBrotherTheme(useDynamicColor = false, darkTheme = darkMode) {
                    // setContent on the same activity keeps the composition,
                    // and with it what the last capture remembered: a test
                    // that captures several states would see the first again.
                    key(file.path) { body() }
                }
            }
        }
    }
    // A bottom sheet or a dialog is a window of its own, with a root of its
    // own: each is checked, and the screen is captured with all of them.
    val roots = onAllNodes(isRoot())
    val rootCount = roots.fetchSemanticsNodes().size

    // Run Accessibility checks first so logging is included
    val accessibilityException = try {
        repeat(rootCount) { index ->
            roots[index].checkRoboAccessibility(
                roborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
                    failureLevel = CheckLevel.Error,
                    checker = RoborazziATFAccessibilityChecker(
                        preset = AccessibilityCheckPreset.LATEST,
                        suppressions = accessibilitySuppressions,
                    ),
                ),
            )
        }
        null
    } catch (e: AccessibilityViewCheckException) {
        e
    }

    if (rootCount > 1) {
        captureScreenRoboImage(file.path, roborazziOptions = roborazziOptions)
    } else {
        this.onRoot()
            .captureRoboImage(
                file.path,
                roborazziOptions = roborazziOptions,
            )
    }

    // Rethrow the Accessibility exception once screenshots have passed
    if (accessibilityException != null) {
        accessibilityException.results.forEachIndexed { index, check ->
            val viewImage = check.viewImage
            if (viewImage is BitmapImage) {
                val failure = File("build/outputs/roborazzi/${file.nameWithoutExtension}_$index.png")
                println("Writing check.viewImage to $failure")
                FileOutputStream(failure).use {
                    viewImage.bitmap.compress(PNG, 100, it)
                }
            }
        }

        throw accessibilityException
    }
}

/**
 * Extracts some properties from the spec string. Note that this function is not exhaustive.
 */
private fun extractSpecs(deviceSpec: String): TestDeviceSpecs {
    val specs = deviceSpec.substringAfter("spec:")
        .split(",").map { it.split("=") }.associate { it[0] to it[1] }
    val width = specs["width"]?.toInt() ?: 640
    val height = specs["height"]?.toInt() ?: 480
    val dpi = specs["dpi"]?.toInt() ?: 480
    return TestDeviceSpecs(width, height, dpi)
}

data class TestDeviceSpecs(val width: Int, val height: Int, val dpi: Int)
