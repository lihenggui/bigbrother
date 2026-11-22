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

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.MutableCubic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stage's two morphing outlines, traced into paths that are reused frame after frame. The morphs are built once;
 * MaterialShapes are normalized to the unit square.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class StageShapes {
    private val medallionMorph = Morph(MaterialShapes.Cookie12Sided, MaterialShapes.Circle)
    private val cageMorph = Morph(MaterialShapes.Circle, MaterialShapes.Arch)
    private val cubic = MutableCubic()

    val medallion = Path()
    val cage = Path()

    private val medallionInputs = FloatArray(6) { Float.NaN }
    private val cageInputs = FloatArray(5) { Float.NaN }

    /** Cookie12Sided while the guard poses, a circle once he docks as an avatar; [unit] is pixels per stage unit. */
    fun traceMedallion(frame: StageFrame, unit: Float) {
        val circle = 1 - frame.lobes
        if (!medallionInputs.update(frame.cx, frame.cy, frame.radius, circle, frame.rotation, unit)) return
        // The cookie's lobes reach a little past the circle, as the prototype's do.
        val radius = frame.radius * lerp(1.04f, 1f, circle)
        trace(medallionMorph, circle, medallion, frame.cx * unit, frame.cy * unit, radius * unit, radius * unit, frame.rotation)
    }

    /** A circle under Weather that becomes the Arch of the Jail icon, taller than wide. */
    fun traceCage(frame: StageFrame, unit: Float) {
        if (!cageInputs.update(frame.cageX, frame.cageY, frame.cageRadius, frame.arch, unit)) return
        val radius = frame.cageRadius * unit
        trace(cageMorph, frame.arch, cage, frame.cageX * unit, frame.cageY * unit, radius, radius * lerp(1f, 1.15f, frame.arch), 0f)
    }

    private fun trace(morph: Morph, progress: Float, path: Path, cx: Float, cy: Float, rx: Float, ry: Float, degrees: Float) {
        val radians = degrees * PI.toFloat() / 180
        val cos = cos(radians)
        val sin = sin(radians)
        fun px(x: Float, y: Float) = cx + ((x - 0.5f) * cos - (y - 0.5f) * sin) * 2 * rx
        fun py(x: Float, y: Float) = cy + ((x - 0.5f) * sin + (y - 0.5f) * cos) * 2 * ry
        path.rewind()
        var first = true
        morph.forEachCubic(progress, cubic) { c ->
            if (first) {
                path.moveTo(px(c.anchor0X, c.anchor0Y), py(c.anchor0X, c.anchor0Y))
                first = false
            }
            path.cubicTo(
                px(c.control0X, c.control0Y),
                py(c.control0X, c.control0Y),
                px(c.control1X, c.control1Y),
                py(c.control1X, c.control1Y),
                px(c.anchor1X, c.anchor1Y),
                py(c.anchor1X, c.anchor1Y),
            )
        }
        path.close()
    }

    /** Stores the inputs a path was traced from; false when they have not changed. */
    private fun FloatArray.update(vararg values: Float): Boolean {
        if (values.contentEquals(this)) return false
        values.copyInto(this)
        return true
    }
}
