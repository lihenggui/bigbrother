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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import li.mercury.bigbrother.configureBadgingTasks
import li.mercury.bigbrother.configureKotlinAndroid
import li.mercury.bigbrother.configureLicensee
import li.mercury.bigbrother.configureSpotlessForAndroid
import li.mercury.bigbrother.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "bigbrother.android.lint")
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = libs.findVersion("androidTargetSdk").get().requiredVersion.toInt()
            }
            configureSpotlessForAndroid()
            configureLicensee()
            extensions.configure<ApplicationAndroidComponentsExtension> {
                configureBadgingTasks(extensions.getByType<ApplicationExtension>(), this)
            }
        }
    }
}
