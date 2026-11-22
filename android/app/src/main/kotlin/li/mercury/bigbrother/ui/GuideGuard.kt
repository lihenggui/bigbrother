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

package li.mercury.bigbrother.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.VectorConfig
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.VectorProperty
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.util.lerp
import li.mercury.bigbrother.R

/**
 * The guard, drawn from his vector drawable with its named groups driven by the guide's springs: the arms flex from
 * crossed to raised, the head and glasses follow his gaze, and the glasses glint when the beam locks on.
 */
@Composable
internal fun rememberGuardPainter(motion: GuideMotion): VectorPainter {
    val image = ImageVector.vectorResource(R.drawable.guide_guard)
    val configs = remember(motion) { guardConfigs(motion) }
    return rememberVectorPainter(
        defaultWidth = image.defaultWidth,
        defaultHeight = image.defaultHeight,
        viewportWidth = image.viewportWidth,
        viewportHeight = image.viewportHeight,
        name = image.name,
        autoMirror = false,
    ) { _, _ -> RenderVectorGroup(image.root, configs) }
}

private fun guardConfigs(motion: GuideMotion): Map<String, VectorConfig> {
    val left = { motion.flexLeft.value }
    val right = { motion.flexRight.value }
    // Once docked, he watches the timeline.
    val gazeX = { lerp(motion.gazeX.value, 0.55f, motion.frame().look) }
    val gazeY = { lerp(motion.gazeY.value, 0.8f, motion.frame().look) }
    return mapOf(
        "left_arm" to GroupConfig(rotation = { lerp(43f, 0f, left()) }),
        "left_forearm" to GroupConfig(rotation = { lerp(-69f, 0f, left()) }),
        "right_arm" to GroupConfig(rotation = { lerp(43f, 0f, right()) }),
        "right_forearm" to GroupConfig(rotation = { lerp(-69f, 0f, right()) }),
        "chest" to GroupConfig(scaleX = { lerp(0.96f, 1f, left().coerceAtMost(1f)) }),
        "head" to GroupConfig(rotation = { gazeX() * 5 }, translateX = { gazeX() * 0.7f }, translateY = { gazeY() * 0.5f }),
        "glasses" to GroupConfig(translateX = { gazeX() * 1.6f }, translateY = { gazeY() }),
        "glint" to GlintConfig { motion.glint.value.coerceIn(0f, 1f) },
    )
}

private class GroupConfig(
    private val rotation: (() -> Float)? = null,
    private val scaleX: (() -> Float)? = null,
    private val translateX: (() -> Float)? = null,
    private val translateY: (() -> Float)? = null,
) : VectorConfig {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T {
        val value = when (property) {
            VectorProperty.Rotation -> rotation
            VectorProperty.ScaleX -> scaleX
            VectorProperty.TranslateX -> translateX
            VectorProperty.TranslateY -> translateY
            else -> null
        }
        return value?.invoke() as T? ?: defaultValue
    }
}

private class GlintConfig(private val glint: () -> Float) : VectorConfig {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T = when (property) {
        VectorProperty.StrokeAlpha -> (0.6f + 0.4f * glint()) as T
        VectorProperty.StrokeLineWidth -> (1.3f + 0.9f * glint()) as T
        else -> defaultValue
    }
}
