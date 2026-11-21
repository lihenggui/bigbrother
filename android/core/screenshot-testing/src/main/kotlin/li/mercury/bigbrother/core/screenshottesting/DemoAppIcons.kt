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

@file:OptIn(ExperimentalCoilApi::class)

package li.mercury.bigbrother.core.screenshottesting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import li.mercury.bigbrother.core.model.AppIconSource
import android.graphics.Path as AndroidPath

/**
 * Launcher icons for the demo apps, which are not installed: AppIcon asks
 * Coil for an app's icon by its [AppIconSource], and in inspection mode Coil
 * asks [previewHandler] instead of loading it.
 */
internal object DemoAppIcons {
    /** Large enough for the biggest icon shown (40 dp) at xxxhdpi. */
    private const val SIZE = 192

    /** The glyph's share of the icon's width. */
    private const val GLYPH_SCALE = 0.54f

    private val icons = HashMap<DemoApp, Bitmap>()

    val previewHandler = AsyncImagePreviewHandler { request ->
        val packageName = (request.data as? AppIconSource)?.packageName
        val app = DemoApp.of(packageName.orEmpty()) ?: error("No demo app has the package $packageName")
        icon(app).asImage()
    }

    @Synchronized
    private fun icon(app: DemoApp): Bitmap = icons.getOrPut(app) { draw(app) }

    /** A disc in the app's colors with its glyph in the middle, as a round launcher mask shows an adaptive icon. */
    private fun draw(app: DemoApp): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = SIZE / 2f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                SIZE.toFloat(),
                app.topColor.toInt(),
                app.bottomColor.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(radius, radius, radius, background)
        val glyphSize = SIZE * GLYPH_SCALE
        val inset = (SIZE - glyphSize) / 2
        drawVector(canvas, app.glyph, inset, glyphSize, app.glyphColor.toInt())
        return bitmap
    }

    /** Fills [vector]'s paths in [color], scaled to a square of [size] at ([offset], [offset]). */
    private fun drawVector(canvas: Canvas, vector: ImageVector, offset: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.translate(offset, offset)
        canvas.scale(size / vector.viewportWidth, size / vector.viewportHeight)
        // Material icons are flat: paths in groups without transformations.
        fun draw(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorGroup -> draw(node)

                    is VectorPath -> {
                        val path = node.pathData.toPath().asAndroidPath()
                        path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                            AndroidPath.FillType.EVEN_ODD
                        } else {
                            AndroidPath.FillType.WINDING
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
        draw(vector.root)
        canvas.restore()
    }
}
