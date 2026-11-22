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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Material motion tokens the guide uses; the app's theme uses the expressive scheme. */
internal class GuideSpecs(scheme: MotionScheme) {
    val spatialFast: FiniteAnimationSpec<Float> = scheme.fastSpatialSpec()
    val spatialDefault: FiniteAnimationSpec<Float> = scheme.defaultSpatialSpec()
    val effectsFast: FiniteAnimationSpec<Float> = scheme.fastEffectsSpec()
    val effectsDefault: FiniteAnimationSpec<Float> = scheme.defaultEffectsSpec()
}

/**
 * The guide's animated values. Without an opening it starts as the finished first page: every app found, the beam
 * and the reticle on Weather.
 */
@Stable
internal class GuideMotion(
    private val position: () -> Float,
    private val reduced: Boolean,
    opening: Boolean,
    private val splash: SplashHandoff?,
) {
    private val handoff = splash != null
    private val start = if (opening) 0f else 1f

    /** The stage position, following the pager through a spring. */
    val stage = Animatable(position())

    /** 0 while the medallion is the splash icon's circle, 1 once it is in place. */
    val hand = Animatable(if (opening && handoff) 0f else 1f)

    /** The launcher icon crossfading into the guard. */
    val reveal = Animatable(if (opening && handoff) 0f else 1f)
    val flexLeft = Animatable(start)
    val flexRight = Animatable(start)
    val chromeAlpha = Animatable(start)
    val chromeRise = Animatable(start)
    val guideAlpha = Animatable(if (opening && !handoff) 0f else 1f)
    val apps = List(APP_COUNT) { Animatable(start) }
    val pops = List(APP_COUNT) { Animatable(start) }
    val beam = Animatable(StageLayout.appAngle(0))
    val beamOn = Animatable(start)
    val reticleAlpha = Animatable(start)
    val reticleScale = Animatable(1f)
    val gazeX = Animatable(if (opening) 0f else StageLayout.gazeX(0))
    val gazeY = Animatable(if (opening) 0f else StageLayout.gazeY(0))
    val glint = Animatable(0f)
    var reticleAt by mutableIntStateOf(if (opening) -1 else 0)
        private set

    /** Set once the opening hands over to the watching loop. */
    var watching by mutableStateOf(!opening)
        private set

    private val caught = BooleanArray(APP_COUNT) { !opening }
    private var visits = if (opening) 0 else APP_COUNT
    private var stageOrigin = Offset.Zero
    private var stageUnit = 0f
    private var circleBounds: Rect? = null
    private var circleUnit = 0f
    private var circle = SplashCircle.Default
    private val frame = StageFrame()

    /** The stage for this frame. Reading it from a draw or layer lambda redraws only that when the stage moves. */
    fun frame(): StageFrame {
        val s = if (reduced) position().roundToInt().toFloat() else stage.value
        frame.update(s, hand.value, splashCircle())
        return frame
    }

    /** With reduced motion each page's art fades out and the next fades in, with no travel or morphing. */
    fun stageAlpha(): Float {
        if (!reduced) return 1f
        val p = position()
        return (1 - 2 * abs(p - p.roundToInt())).coerceIn(0f, 1f)
    }

    /** The stage follows the pager through a spatial spring, so the art lags a little and rebounds on fast swipes. */
    suspend fun follow(specs: GuideSpecs) {
        if (reduced) return
        coroutineScope {
            snapshotFlow(position).collect { target ->
                // Each retarget keeps the current velocity.
                launch { stage.animateTo(target, specs.spatialDefault, stage.velocity) }
            }
        }
    }

    /** Where the stage is in the window, to place the splash icon's circle in stage units. */
    fun placeStage(origin: Offset, unitPx: Float) {
        stageOrigin = origin
        stageUnit = unitPx
    }

    private fun splashCircle(): SplashCircle {
        val bounds = splash?.iconBoundsOrNull()
        if (bounds == null || stageUnit <= 0f) return SplashCircle.Default
        if (bounds != circleBounds || stageUnit != circleUnit) {
            circleBounds = bounds
            circleUnit = stageUnit
            val center = (bounds.center - stageOrigin) / stageUnit
            // The adaptive icon's circular mask spans two thirds of the splash icon view.
            circle = SplashCircle(center.x, center.y, bounds.width / 3 / stageUnit)
        }
        return circle
    }

    /** The splash icon becomes the medallion, the guard flexes, the chrome rises, then the apps appear. */
    suspend fun open(specs: GuideSpecs) = coroutineScope {
        if (splash?.awaitIconBounds() != null) {
            launch { hand.animateTo(1f, specs.spatialDefault) }
            launch { reveal.animateTo(1f, specs.effectsDefault) }
        } else {
            hand.snapTo(1f)
            reveal.snapTo(1f)
            guideAlpha.snapTo(0f)
            launch { guideAlpha.animateTo(1f, specs.effectsDefault) }
        }
        delay(400)
        launch { flexLeft.animateTo(1f, specs.spatialDefault) }
        launch { chromeAlpha.animateTo(1f, specs.effectsDefault) }
        launch { chromeRise.animateTo(1f, specs.spatialDefault) }
        delay(35)
        launch { flexRight.animateTo(1f, specs.spatialDefault) }
        delay(315)
        for (app in apps) {
            launch { app.animateTo(1f, specs.spatialFast) }
            delay(60)
        }
        delay(160)
        // The beam swings in from behind the guard.
        beam.snapTo(StageLayout.appAngle(0) - 50)
        launch { beamOn.animateTo(1f, specs.effectsDefault) }
        watching = true
    }

    /** The beam visits every app quickly, then keeps moving from app to app until cancelled. */
    suspend fun watch(specs: GuideSpecs) = coroutineScope {
        try {
            while (true) {
                // Test clocks refuse endless work here; on a device this is one frame per visit.
                withInfiniteAnimationFrameMillis { }
                visit(visits % APP_COUNT, specs)
                visits++
                delay(if (visits < APP_COUNT) FIRST_VISITS_MS else VISIT_MS)
            }
        } finally {
            // A catch cut short by leaving the page still shows what the app read.
            withContext(NonCancellable) {
                caught.forEachIndexed { index, found -> if (found) pops[index].snapTo(1f) }
                reticleScale.snapTo(1f)
                glint.snapTo(0f)
            }
        }
    }

    private fun CoroutineScope.visit(index: Int, specs: GuideSpecs) {
        val current = beam.targetValue
        var target = StageLayout.appAngle(index)
        while (target - current > 180) target -= 360
        while (target - current < -180) target += 360
        // The head turns before the beam arrives.
        launch { reticleAlpha.animateTo(0f, specs.effectsFast) }
        launch { gazeX.animateTo(StageLayout.gazeX(index), specs.spatialDefault) }
        launch { gazeY.animateTo(StageLayout.gazeY(index), specs.spatialDefault) }
        launch {
            var locked = false
            beam.animateTo(target, specs.spatialDefault) {
                if (!locked && abs(value - target) < LOCK_DEGREES) {
                    locked = true
                    lock(index, specs)
                }
            }
        }
    }

    private fun CoroutineScope.lock(index: Int, specs: GuideSpecs) {
        reticleAt = index
        launch {
            reticleScale.snapTo(1.4f)
            reticleScale.animateTo(1f, specs.spatialFast)
        }
        launch { reticleAlpha.animateTo(1f, specs.effectsFast) }
        launch { glint.animateTo(1f, specs.effectsFast) }
        launch {
            delay(140)
            glint.animateTo(0f, specs.effectsFast)
        }
        if (!caught[index]) {
            caught[index] = true
            launch { pops[index].animateTo(1f, specs.spatialFast) }
        }
    }

    private companion object {
        const val FIRST_VISITS_MS = 700L
        const val VISIT_MS = 1_800L
        const val LOCK_DEGREES = 6f
    }
}

/** A spring, or any finite spec, that starts after [delayMillis]; springs have no delay of their own. */
internal class DelayedSpec<T>(private val delayMillis: Int, private val spec: FiniteAnimationSpec<T>) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> {
        val inner = spec.vectorize(converter)
        val delayNanos = delayMillis * 1_000_000L
        return object : VectorizedFiniteAnimationSpec<V> {
            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V = if (playTimeNanos < delayNanos) {
                initialValue
            } else {
                inner.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)
            }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V = if (playTimeNanos < delayNanos) {
                initialVelocity
            } else {
                inner.getVelocityFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)
            }

            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long = delayNanos + inner.getDurationNanos(initialValue, targetValue, initialVelocity)
        }
    }
}
