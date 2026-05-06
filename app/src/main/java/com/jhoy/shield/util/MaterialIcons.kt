package com.jhoy.shield.util

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Provides Material Design icons as programmatic Drawables.
 * No XML vector files needed — all icons are drawn via Canvas paths.
 */
object MaterialIcons {

    fun play(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Play triangle
        moveTo(size * 0.25f, size * 0.15f)
        lineTo(size * 0.25f, size * 0.85f)
        lineTo(size * 0.82f, size * 0.5f)
        close()
    }

    fun pause(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Two vertical bars
        addRect(size * 0.2f, size * 0.15f, size * 0.42f, size * 0.85f, Path.Direction.CW)
        addRect(size * 0.58f, size * 0.15f, size * 0.8f, size * 0.85f, Path.Direction.CW)
    }

    fun skipNext(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Triangle + vertical bar
        moveTo(size * 0.15f, size * 0.2f)
        lineTo(size * 0.15f, size * 0.8f)
        lineTo(size * 0.62f, size * 0.5f)
        close()
        addRect(size * 0.7f, size * 0.2f, size * 0.82f, size * 0.8f, Path.Direction.CW)
    }

    fun skipPrevious(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Vertical bar + triangle
        addRect(size * 0.18f, size * 0.2f, size * 0.3f, size * 0.8f, Path.Direction.CW)
        moveTo(size * 0.85f, size * 0.2f)
        lineTo(size * 0.85f, size * 0.8f)
        lineTo(size * 0.38f, size * 0.5f)
        close()
    }

    fun download(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Arrow pointing down
        moveTo(size * 0.5f, size * 0.12f)
        lineTo(size * 0.5f, size * 0.58f)
        // Arrow head
        moveTo(size * 0.28f, size * 0.44f)
        lineTo(size * 0.5f, size * 0.66f)
        lineTo(size * 0.72f, size * 0.44f)
        // Bottom line
        moveTo(size * 0.2f, size * 0.8f)
        lineTo(size * 0.8f, size * 0.8f)
    }

    fun stop(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        addRect(size * 0.2f, size * 0.2f, size * 0.8f, size * 0.8f, Path.Direction.CW)
    }

    fun musicNote(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        // Note head (ellipse approximation)
        addOval(size * 0.18f, size * 0.6f, size * 0.45f, size * 0.82f, Path.Direction.CW)
        // Stem
        addRect(size * 0.42f, size * 0.15f, size * 0.47f, size * 0.7f, Path.Direction.CW)
        // Flag
        moveTo(size * 0.47f, size * 0.15f)
        lineTo(size * 0.75f, size * 0.28f)
        lineTo(size * 0.47f, size * 0.4f)
        close()
    }

    fun arrowBack(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable = PathIconDrawable(sizeDp, color) { size ->
        moveTo(size * 0.75f, size * 0.5f)
        lineTo(size * 0.28f, size * 0.5f)
        moveTo(size * 0.28f, size * 0.5f)
        lineTo(size * 0.48f, size * 0.28f)
        moveTo(size * 0.28f, size * 0.5f)
        lineTo(size * 0.48f, size * 0.72f)
    }

    /**
     * A Drawable that renders a filled/stroked icon from a Path lambda.
     */
    private class PathIconDrawable(
        private val sizeDp: Int,
        private val color: Int,
        private val isFilled: Boolean = true,
        private val pathBuilder: Path.(size: Float) -> Unit
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@PathIconDrawable.color
            style = if (isFilled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val size = bounds.width().toFloat()
            val path = Path()

            // Check if path uses moveTo + lineTo sequences (stroke icons like download/arrow)
            path.pathBuilder(size)

            // For download and arrow icons, use stroke style
            if (!isFilled) {
                paint.style = Paint.Style.STROKE
            }

            canvas.drawPath(path, paint)
        }

        override fun getIntrinsicWidth(): Int = sizeDp
        override fun getIntrinsicHeight(): Int = sizeDp
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // Convenience: create stroke-style icons
    fun downloadStroke(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            // Arrow shaft
            moveTo(size * 0.5f, size * 0.12f)
            lineTo(size * 0.5f, size * 0.58f)
            // Arrow head
            moveTo(size * 0.3f, size * 0.44f)
            lineTo(size * 0.5f, size * 0.66f)
            lineTo(size * 0.7f, size * 0.44f)
            // Bottom line
            moveTo(size * 0.2f, size * 0.82f)
            lineTo(size * 0.8f, size * 0.82f)
        }

    fun arrowBackStroke(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            moveTo(size * 0.75f, size * 0.5f)
            lineTo(size * 0.28f, size * 0.5f)
            moveTo(size * 0.45f, size * 0.28f)
            lineTo(size * 0.28f, size * 0.5f)
            lineTo(size * 0.45f, size * 0.72f)
        }

    fun error(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color) { size ->
            // Warning triangle
            moveTo(size * 0.5f, size * 0.1f)
            lineTo(size * 0.9f, size * 0.85f)
            lineTo(size * 0.1f, size * 0.85f)
            close()
        }

    fun close(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            moveTo(size * 0.25f, size * 0.25f)
            lineTo(size * 0.75f, size * 0.75f)
            moveTo(size * 0.75f, size * 0.25f)
            lineTo(size * 0.25f, size * 0.75f)
        }

    fun check(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            moveTo(size * 0.2f, size * 0.5f)
            lineTo(size * 0.42f, size * 0.72f)
            lineTo(size * 0.8f, size * 0.28f)
        }

    fun downloading(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            // Down arrow
            moveTo(size * 0.5f, size * 0.15f)
            lineTo(size * 0.5f, size * 0.6f)
            moveTo(size * 0.32f, size * 0.46f)
            lineTo(size * 0.5f, size * 0.64f)
            lineTo(size * 0.68f, size * 0.46f)
            // Tray
            moveTo(size * 0.15f, size * 0.65f)
            lineTo(size * 0.15f, size * 0.82f)
            lineTo(size * 0.85f, size * 0.82f)
            lineTo(size * 0.85f, size * 0.65f)
        }

    fun shuffle(color: Int = Color.WHITE, sizeDp: Int = 24): Drawable =
        PathIconDrawable(sizeDp, color, isFilled = false) { size ->
            // Two crossing arrows
            // Top-left to bottom-right line
            moveTo(size * 0.15f, size * 0.3f)
            lineTo(size * 0.65f, size * 0.7f)
            // Bottom-left to top-right line
            moveTo(size * 0.15f, size * 0.7f)
            lineTo(size * 0.65f, size * 0.3f)
            // Right arrow head top
            moveTo(size * 0.55f, size * 0.2f)
            lineTo(size * 0.75f, size * 0.3f)
            lineTo(size * 0.55f, size * 0.4f)
            // Right arrow head bottom
            moveTo(size * 0.55f, size * 0.6f)
            lineTo(size * 0.75f, size * 0.7f)
            lineTo(size * 0.55f, size * 0.8f)
        }
}
