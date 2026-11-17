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

package li.mercury.bigbrother.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.SensorsUsed

private const val ANDROID_SENSOR = "android.sensor."

/** Names of the sensor types the platform defines, by the last part of their `android.sensor.*` name. */
private val SENSOR_NAMES: Map<String, Int> = mapOf(
    "accelerometer" to R.string.sensor_accelerometer,
    "accelerometer_uncalibrated" to R.string.sensor_accelerometer_uncalibrated,
    "accelerometer_limited_axes" to R.string.sensor_accelerometer_limited_axes,
    "accelerometer_limited_axes_uncalibrated" to R.string.sensor_accelerometer_limited_axes_uncalibrated,
    "gyroscope" to R.string.sensor_gyroscope,
    "gyroscope_uncalibrated" to R.string.sensor_gyroscope_uncalibrated,
    "gyroscope_limited_axes" to R.string.sensor_gyroscope_limited_axes,
    "gyroscope_limited_axes_uncalibrated" to R.string.sensor_gyroscope_limited_axes_uncalibrated,
    "magnetic_field" to R.string.sensor_magnetic_field,
    "magnetic_field_uncalibrated" to R.string.sensor_magnetic_field_uncalibrated,
    "orientation" to R.string.sensor_orientation,
    "light" to R.string.sensor_light,
    "proximity" to R.string.sensor_proximity,
    "pressure" to R.string.sensor_pressure,
    "relative_humidity" to R.string.sensor_relative_humidity,
    "ambient_temperature" to R.string.sensor_ambient_temperature,
    "gravity" to R.string.sensor_gravity,
    "linear_acceleration" to R.string.sensor_linear_acceleration,
    "rotation_vector" to R.string.sensor_rotation_vector,
    "game_rotation_vector" to R.string.sensor_game_rotation_vector,
    "geomagnetic_rotation_vector" to R.string.sensor_geomagnetic_rotation_vector,
    "significant_motion" to R.string.sensor_significant_motion,
    "step_detector" to R.string.sensor_step_detector,
    "step_counter" to R.string.sensor_step_counter,
    "heart_rate" to R.string.sensor_heart_rate,
    "heart_beat" to R.string.sensor_heart_beat,
    "device_orientation" to R.string.sensor_device_orientation,
    "pick_up_gesture" to R.string.sensor_pick_up_gesture,
    "tilt_detector" to R.string.sensor_tilt_detector,
    "hinge_angle" to R.string.sensor_hinge_angle,
    "low_latency_offbody_detect" to R.string.sensor_low_latency_offbody_detect,
    "stationary_detect" to R.string.sensor_stationary_detect,
    "motion_detect" to R.string.sensor_motion_detect,
    "pose_6dof" to R.string.sensor_pose_6dof,
    "head_tracker" to R.string.sensor_head_tracker,
    "heading" to R.string.sensor_heading,
)

/** The name of a sensor type the platform defines (`android.sensor.gyroscope`); null for others. */
@StringRes
fun sensorNameRes(type: String): Int? = type.removePrefix(ANDROID_SENSOR).takeIf { it.length < type.length }?.let { SENSOR_NAMES[it] }

/**
 * A name for a sensor type this build has none for, made from the type
 * itself: `com.google.sensor.binned_brightness` becomes "Binned brightness".
 * Null when the type has nothing to make one of: empty, or a number.
 */
fun readableSensorType(type: String): String? {
    if (type.isEmpty() || type.all { it.isDigit() }) return null
    val name = type.trimEnd('.').substringAfterLast('.').replace('_', ' ').replace('-', ' ').trim()
    if (name.isEmpty()) return type
    return name.replaceFirstChar { it.uppercaseChar() }
}

/** The name of a sensor type, localized when the platform defines it. */
@Composable
fun sensorName(type: String): String {
    sensorNameRes(type)?.let { return stringResource(it) }
    return readableSensorType(type) ?: if (type.isEmpty()) {
        stringResource(R.string.sensor_unknown)
    } else {
        stringResource(R.string.sensor_type_number, type)
    }
}

/** A sensor's name, marked when the app asked for a high rate: "Gyroscope (high rate)". */
@Composable
fun sensorName(type: String, highRate: Boolean): String {
    val name = sensorName(type)
    return if (highRate) stringResource(R.string.sensor_high_rate, name) else name
}

/**
 * The sensors an app turned on and how often: "Accelerometer, Gyroscope
 * (high rate) · 12 times", or, [compact], "... · 12×".
 */
@Composable
fun sensorsSummary(used: SensorsUsed, compact: Boolean = false): String {
    val names = used.sensors.map { sensorName(it.type, it.highRate) }
    val times = if (compact) {
        stringResource(R.string.sensor_times_short, used.times)
    } else {
        pluralStringResource(R.plurals.sensor_times, used.times, used.times)
    }
    return listOfNotNull(namedList(names), times).joinToString(stringResource(R.string.dot_separator))
}
