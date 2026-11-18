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

package li.mercury.bigbrother.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * The jail's glyphs: a domed cage on a base, drawn on the 24 dp grid of
 * Material Symbols with the same 2 dp strokes. Every variant is built from
 * the centre lines below, so the outlined and filled cages stay in step.
 */
object CageIcons {
    private const val STROKE = 2f
    private const val CENTER_X = 12f
    private const val CENTER_Y = 11f
    private const val RADIUS = 7f
    private const val LEFT = CENTER_X - RADIUS
    private const val RIGHT = CENTER_X + RADIUS
    private const val FLOOR = 20.5f
    private val bars = floatArrayOf(9.67f, 14.33f)

    // Where raised bars end; the space below them reads as the way out.
    private const val RAISED = 12.5f

    // The filled cage's openings are this much smaller than the outlined
    // cage's, so its bars read slightly heavier, as filled glyphs do.
    private const val FILLED_INSET = 0.2f

    private const val HALF = STROKE / 2
    private val BASE = "M${LEFT - 2},${FLOOR}H${RIGHT + 2}"
    private val DOME = "M$LEFT,${FLOOR}V${CENTER_Y}A$RADIUS,$RADIUS 0 0 1 $RIGHT,${CENTER_Y}V$FLOOR"

    /** The jail destination, unselected. */
    val Outlined: ImageVector by lazy { strokedCage("Cage.Outlined", barsFrom = FLOOR) }

    /** The jail destination, selected: the outlined cage with its openings cut out of a solid dome. */
    val Filled: ImageVector by lazy {
        val outer = RADIUS + HALF
        val solid = "M${LEFT - 2},${FLOOR - HALF}H${LEFT - HALF}V${CENTER_Y}" +
            "A$outer,$outer 0 0 1 ${RIGHT + HALF},${CENTER_Y}V${FLOOR - HALF}H${RIGHT + 2}" +
            "A$HALF,$HALF 0 0 1 ${RIGHT + 2},${FLOOR + HALF}H${LEFT - 2}A$HALF,$HALF 0 0 1 ${LEFT - 2},${FLOOR - HALF}Z"
        val edges = floatArrayOf(LEFT, *bars, RIGHT)
        val openings = (0 until edges.size - 1).joinToString("") { i ->
            val x1 = edges[i] + HALF + FILLED_INSET
            val x2 = edges[i + 1] - HALF - FILLED_INSET
            val r = RADIUS - HALF - FILLED_INSET
            val bottom = FLOOR - HALF - FILLED_INSET
            "M$x1,${bottom}V${domeY(x1, r)}A$r,$r 0 0 1 $x2,${domeY(x2, r)}V${bottom}Z"
        }
        cage("Cage.Filled") {
            addPath(
                pathData = addPathNodes(solid + openings),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
        }
    }

    /** Ending an isolation: the bars are raised. */
    val Open: ImageVector by lazy { strokedCage("Cage.Open", barsFrom = RAISED) }

    /** Marks an isolated app at badge size, where two bars would merge into one. */
    val Badge: ImageVector by lazy {
        cage("Cage.Badge") {
            stroke(BASE, width = 3f)
            stroke(DOME, width = 3f)
            stroke("M$CENTER_X,${FLOOR}V${CENTER_Y - RADIUS + HALF}", width = 3f)
        }
    }

    private fun strokedCage(name: String, barsFrom: Float): ImageVector = cage(name) {
        stroke(BASE)
        stroke(DOME)
        stroke(bars.joinToString("") { x -> "M$x,${barsFrom}V${domeY(x, RADIUS)}" })
    }

    /** The y of the dome of radius [r] above [x]. */
    private fun domeY(x: Float, r: Float): Float {
        val dx = x - CENTER_X
        return CENTER_Y - sqrt((r * r - dx * dx).coerceAtLeast(0f))
    }

    private fun cage(name: String, block: ImageVector.Builder.() -> Unit): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.stroke(pathData: String, width: Float = STROKE) {
        addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
