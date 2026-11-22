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

import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/*
 * The illustration lives in a fixed 328 × 400 space ("stage units"), scaled to fit its area. Every part is a
 * function of the stage position s: 0 is the first page, 1 the timeline, 2 the jail.
 */
internal const val STAGE_WIDTH = 328f
internal const val STAGE_HEIGHT = 400f
internal const val APP_COUNT = 4

internal fun ramp(value: Float, start: Float, end: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** Size changes read as even steps when interpolated on a log scale. */
private fun logLerp(start: Float, stop: Float, fraction: Float) = start * (stop / start).pow(fraction)

private fun arc(fraction: Float) = sin(PI * fraction).toFloat()

internal object StageLayout {
    const val MEDALLION_X = 164f
    const val MEDALLION_Y = 200f
    const val MEDALLION_RADIUS = 94f
    const val CAGE_X = 164f
    const val CAGE_Y = 214f
    const val CAGE_RADIUS = 92f
    const val ROW_ICON_X = 60f
    private const val ROW_TOP = 158f
    const val ROW_STEP = 54f

    /** The sample apps around the guard, clockwise from the top left. */
    val appX = floatArrayOf(66f, 262f, 262f, 66f)
    val appY = floatArrayOf(92f, 92f, 308f, 308f)

    /** Each app's chip on the first page sits beside it, away from the guard. */
    val dataChipX = floatArrayOf(66f, 262f, 262f, 66f)
    val dataChipY = floatArrayOf(48f, 48f, 352f, 352f)

    /** What an isolated app gets, placed around the cage and clear of it. */
    val jailChipX = floatArrayOf(76f, 250f, 164f)
    val jailChipY = floatArrayOf(84f, 84f, 356f)

    fun rowIconY(index: Int) = ROW_TOP + 26 + ROW_STEP * index

    /** The beam's angle, in degrees, from the medallion's centre to an app. */
    fun appAngle(index: Int) = (atan2(appY[index] - MEDALLION_Y, appX[index] - MEDALLION_X) * 180 / PI).toFloat()

    /** The guard's gaze towards an app, as a unit vector. */
    fun gazeX(index: Int) = (appX[index] - MEDALLION_X) / gazeLength(index)

    fun gazeY(index: Int) = (appY[index] - MEDALLION_Y) / gazeLength(index)

    private fun gazeLength(index: Int) = hypot(appX[index] - MEDALLION_X, appY[index] - MEDALLION_Y)
}

/**
 * Where everything is for one stage position. Draw and layer lambdas read it many times per frame, so it is updated in
 * place and only when its inputs change.
 */
internal class StageFrame {
    private var lastS = Float.NaN
    private var lastHand = Float.NaN
    private var lastSplash: SplashCircle? = null

    // The medallion: the guard's portrait, docking as a watcher in the corner.
    var cx = 0f
    var cy = 0f
    var radius = 0f
    var lobes = 0f
    var rotation = 0f
    var guardX = 0f
    var guardY = 0f
    var guardScale = 0f
    var rays = 0f
    var look = 0f

    // The timeline card.
    var cardAlpha = 0f
    var cardOut = 0f
    var cardDy = 0f
    var cardScale = 1f

    // Weather on its way into the cage.
    var weatherX = 0f
    var weatherY = 0f
    var weatherSize = 0f

    // The cage: a circle that grows under Weather and becomes an arch.
    var cageAlpha = 0f
    var cageX = 0f
    var cageY = 0f
    var cageRadius = 0f
    var arch = 0f
    var bars = 0f
    var outline = 0f

    // Output of app(), dataChip() and jailChip(), read right after the call.
    var x = 0f
    var y = 0f
    var scale = 0f
    var alpha = 0f

    var s = 0f
        private set

    fun update(s: Float, hand: Float, splash: SplashCircle) {
        if (s == lastS && hand == lastHand && splash == lastSplash) return
        lastS = s
        lastHand = hand
        lastSplash = splash
        this.s = s
        updateMedallion(s, hand, splash)
        updateCard(s)
        updateCage(s)
    }

    private fun updateMedallion(s: Float, hand: Float, splash: SplashCircle) {
        val dock = ramp(s, 0f, 0.8f)
        cx = lerp(splash.x, lerp(StageLayout.MEDALLION_X, 40f, dock), hand)
        cy = lerp(splash.y, lerp(StageLayout.MEDALLION_Y, 34f, dock), hand)
        radius = lerp(splash.radius, logLerp(StageLayout.MEDALLION_RADIUS, 24f, dock), hand)
        lobes = hand.coerceIn(0f, 1f) * (1 - ramp(s, 0.05f, 0.7f))
        rotation = 45 * dock
        val k = radius / logLerp(124f, 50f, dock)
        guardX = cx - lerp(138f, 140f, dock) * k
        guardY = cy - lerp(114f, 92f, dock) * k
        guardScale = k
        rays = 1 - ramp(s, 0f, 0.45f)
        look = ramp(s, 0.1f, 0.8f)
    }

    private fun updateCard(s: Float) {
        val cardIn = ramp(s, 0.25f, 0.85f)
        cardOut = ramp(s, 1.05f, 1.5f)
        cardAlpha = cardIn * (1 - cardOut)
        cardDy = lerp(64f, 0f, cardIn) + lerp(0f, 48f, cardOut)
        cardScale = lerp(0.94f, 1f, cardIn)
    }

    private fun updateCage(s: Float) {
        val travel = ramp(s, 1.1f, 1.75f)
        weatherX = lerp(StageLayout.ROW_ICON_X, StageLayout.CAGE_X, travel)
        weatherY = lerp(StageLayout.rowIconY(0), StageLayout.CAGE_Y + 24, travel) - 26 * arc(travel)
        weatherSize = lerp(36f, 60f, travel)
        val grow = ramp(s, 1.15f, 1.8f)
        cageAlpha = ramp(s, 1.1f, 1.25f)
        cageX = lerp(weatherX, StageLayout.CAGE_X, grow)
        cageY = lerp(weatherY, StageLayout.CAGE_Y, grow)
        cageRadius = logLerp(21f, StageLayout.CAGE_RADIUS, grow)
        arch = ramp(s, 1.3f, 1.85f)
        bars = ramp(s, 1.55f, 1.95f)
        outline = ramp(s, 1.75f, 1.97f)
    }

    /** A sample app around the guard, then in its timeline row; Weather goes on into the cage. */
    fun app(index: Int, pop: Float) {
        alpha = (pop * 3).coerceIn(0f, 1f)
        val size: Float
        if (index == 0 && s > 1) {
            x = weatherX
            y = weatherY
            size = weatherSize
        } else {
            val u = ramp(s, 0.08f + 0.06f * index, 0.68f + 0.06f * index)
            x = lerp(StageLayout.appX[index], StageLayout.ROW_ICON_X, u)
            y = lerp(StageLayout.appY[index], StageLayout.rowIconY(index) + cardDy, u) - 28 * arc(u)
            size = lerp(44f, 36f, u)
            if (index > 0) alpha *= 1 - cardOut
        }
        scale = size / APP_ICON_SIZE * max(0f, pop)
    }

    /** What an app read: out of the app, then into its row, where it becomes the row's summary. */
    fun dataChip(index: Int, pop: Float, width: Float) {
        val u = ramp(s, 0.12f + 0.06f * index, 0.72f + 0.06f * index)
        val targetX = lerp(StageLayout.dataChipX[index], 90 + 0.55f * width / 2 - 12, u)
        val targetY = lerp(StageLayout.dataChipY[index], StageLayout.rowIconY(index) + 10 + cardDy, u) - 30 * arc(u)
        val out = pop.coerceIn(0f, 1.2f)
        x = lerp(StageLayout.appX[index], targetX, out)
        y = lerp(StageLayout.appY[index], targetY, out)
        alpha = (1 - ramp(s, 0.62f + 0.06f * index, 0.8f + 0.06f * index)) * (pop * 3).coerceIn(0f, 1f)
        scale = lerp(1f, 0.55f, u) * max(0f, pop)
    }

    /** What an isolated app gets, pushed back out of the cage. */
    fun jailChip(index: Int) {
        val u = ramp(s, 1.5f + 0.06f * index, 1.92f + 0.06f * index)
        x = lerp(weatherX, StageLayout.jailChipX[index], u)
        y = lerp(weatherY, StageLayout.jailChipY[index], u)
        alpha = ramp(s, 1.5f + 0.06f * index, 1.68f + 0.06f * index)
        scale = lerp(0.5f, 1f, u)
    }

    companion object {
        const val APP_ICON_SIZE = 36f
    }
}

/** The circle the medallion starts from: the splash screen's icon, in stage units. */
internal data class SplashCircle(val x: Float, val y: Float, val radius: Float) {
    companion object {
        /** The centre of a phone window, where the system draws the splash icon, when its bounds are unknown. */
        val Default = SplashCircle(164f, 314f, 80f)
    }
}
