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

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The painted parts of the stage: the guard and the launcher icon he is handed over from. */
internal class StageArt(val guard: Painter, val iconBackground: Painter, val iconForeground: Painter)

private const val BEAM_LENGTH = 196f

// The largest the guard is drawn; he is rasterized at this size and scaled down from it, never up.
private const val GUARD_MAX_SCALE = StageLayout.MEDALLION_RADIUS * 1.1f / 124f
private const val GUARD_VIEWPORT = 280f

/** Behind the apps: the searchlight, the cage, and the medallion with the guard in it. */
@Composable
internal fun StageBack(motion: GuideMotion, shapes: StageShapes, art: StageArt) {
    val colors = MaterialTheme.colorScheme
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val dark = colors.surface.luminance() < 0.5f
            val beam = if (dark) Color(0xFFF2BE52).copy(alpha = 0.28f) else Color(0xFFFFC94D).copy(alpha = 0.5f)
            val beamBrush = Brush.radialGradient(0.42f to beam, 1f to beam.copy(alpha = 0f), center = Offset.Zero, radius = BEAM_LENGTH)
            val cone = wedge(12f)
            val penumbra = wedge(19f)
            val rays = rays()
            val rayColor = Color.White.copy(alpha = if (dark) 0.08f else 0.55f)
            onDrawBehind {
                val frame = motion.frame()
                val watching = motion.beamOn.value * (1 - ramp(frame.s, 0f, 0.3f)) * motion.reveal.value
                if (watching > 0f) {
                    stageTransform(frame.cx, frame.cy, motion.beam.value) {
                        drawPath(penumbra, beamBrush, alpha = watching * 0.45f)
                        drawPath(cone, beamBrush, alpha = watching)
                    }
                }
                if (frame.cageAlpha > 0f) {
                    shapes.traceCage(frame, density)
                    drawPath(shapes.cage, colors.tertiaryContainer, alpha = frame.cageAlpha)
                }
                shapes.traceMedallion(frame, density)
                drawPath(shapes.medallion, colors.primaryContainer)
                clipPath(shapes.medallion) {
                    val rayAlpha = frame.rays * motion.reveal.value
                    if (rayAlpha > 0f) stageTransform(frame.cx, frame.cy, frame.rotation) { drawPath(rays, rayColor, alpha = rayAlpha) }
                    drawLauncherIcon(art, frame, 1 - motion.reveal.value)
                    drawGuard(art.guard, frame, motion.reveal.value)
                }
            }
        },
    )
}

/** In front of the apps: the cage's bars and outline, and the reticle locked onto an app. */
@Composable
internal fun StageFront(motion: GuideMotion, shapes: StageShapes) {
    val colors = MaterialTheme.colorScheme
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val reticle = reticle()
            val reticleStroke = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val outlineStroke = Stroke(3 * density)
            onDrawBehind {
                val frame = motion.frame()
                if (frame.cageAlpha > 0f) {
                    shapes.traceCage(frame, density)
                    if (frame.bars > 0f) clipPath(shapes.cage) { drawBars(colors, frame) }
                    if (frame.outline > 0f) drawPath(shapes.cage, colors.onTertiaryContainer, alpha = frame.outline, style = outlineStroke)
                }
                val target = motion.reticleAt
                val alpha = if (target < 0) 0f else motion.reticleAlpha.value.coerceIn(0f, 1f) * (1 - ramp(frame.s, 0f, 0.2f))
                if (alpha > 0f) {
                    translate(StageLayout.appX[target] * density, StageLayout.appY[target] * density) {
                        scale(motion.reticleScale.value * density, pivot = Offset.Zero) {
                            drawPath(reticle, colors.primary, alpha = alpha, style = reticleStroke)
                        }
                    }
                }
            }
        },
    )
}

/** Draws [block] in stage units, with the origin at a stage point and turned by [degrees]. */
private inline fun DrawScope.stageTransform(x: Float, y: Float, degrees: Float, crossinline block: DrawScope.() -> Unit) {
    val unit = density
    withTransform({
        translate(x * unit, y * unit)
        rotate(degrees, Offset.Zero)
        scale(unit, unit, Offset.Zero)
    }) { block() }
}

private fun DrawScope.drawLauncherIcon(art: StageArt, frame: StageFrame, alpha: Float) {
    if (alpha <= 0f) return
    // The adaptive icon's mask, two thirds of its 108-unit canvas, fills the medallion.
    val size = 3 * frame.radius * density
    translate((frame.cx * density) - size / 2, (frame.cy * density) - size / 2) {
        with(art.iconBackground) { draw(Size(size, size), alpha) }
        with(art.iconForeground) { draw(Size(size, size), alpha) }
    }
}

private fun DrawScope.drawGuard(guard: Painter, frame: StageFrame, alpha: Float) {
    if (alpha <= 0f) return
    val raster = GUARD_VIEWPORT * GUARD_MAX_SCALE * density
    translate(frame.guardX * density, frame.guardY * density) {
        scale(frame.guardScale / GUARD_MAX_SCALE, pivot = Offset.Zero) {
            with(guard) { draw(Size(raster, raster), alpha) }
        }
    }
}

private fun DrawScope.drawBars(colors: ColorScheme, frame: StageFrame) {
    val unit = density
    val alpha = ramp(frame.bars, 0f, 0.3f)
    translate(0f, lerp(-250f, 0f, frame.bars) * unit) {
        for (step in -2..2) {
            val x = StageLayout.CAGE_X + step * 0.33f * StageLayout.CAGE_RADIUS - 1.75f
            drawRoundRect(colors.onTertiaryContainer, Offset(x * unit, 60 * unit), Size(3.5f * unit, 300 * unit), CornerRadius(1.75f * unit), alpha = alpha)
        }
        val band = StageLayout.CAGE_Y + 0.78f * StageLayout.CAGE_RADIUS
        drawRect(colors.onTertiaryContainer, Offset(40 * unit, band * unit), Size(248 * unit, 4 * unit), alpha = alpha)
    }
}

/** A soft wedge of the searchlight, from its origin along +x. */
private fun wedge(halfAngle: Float): Path {
    val radians = halfAngle * PI.toFloat() / 180
    return Path().apply {
        moveTo(0f, 0f)
        lineTo(BEAM_LENGTH * cos(radians), -BEAM_LENGTH * sin(radians))
        arcTo(
            Rect(Offset.Zero, BEAM_LENGTH),
            -halfAngle,
            2 * halfAngle,
            forceMoveTo = false,
        )
        close()
    }
}

/** Twelve rays behind the guard, as on the launcher icon. */
private fun rays(): Path = Path().apply {
    for (index in 0 until 12) {
        val from = (index * 30 - 6) * PI.toFloat() / 180
        val to = (index * 30 + 6) * PI.toFloat() / 180
        moveTo(0f, 0f)
        lineTo(300 * cos(from), 300 * sin(from))
        lineTo(300 * cos(to), 300 * sin(to))
        close()
    }
}

/** Four corner brackets around a 58-unit square, centred on the origin. */
private fun reticle(): Path = Path().apply {
    for (corner in 0 until 4) {
        val sx = if (corner == 0 || corner == 3) -1f else 1f
        val sy = if (corner < 2) -1f else 1f
        moveTo(29 * sx, 19 * sy)
        lineTo(29 * sx, 25 * sy)
        quadraticTo(29 * sx, 29 * sy, 25 * sx, 29 * sy)
        lineTo(19 * sx, 29 * sy)
    }
}
