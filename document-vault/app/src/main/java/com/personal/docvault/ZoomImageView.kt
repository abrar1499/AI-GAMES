package com.personal.docvault

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** Image view with pinch-to-zoom, drag and double-tap zoom. */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    private val m = Matrix()
    private var base = 1f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(d.focusX, d.focusY, (scale * d.scaleFactor).coerceIn(1f, 6f))
            return true
        }
    })

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAround(e.x, e.y, if (scale > 1.01f) 1f else 2.5f)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    private fun zoomAround(fx: Float, fy: Float, newScale: Float) {
        val f = newScale / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = newScale
        apply()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    private fun reset() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        base = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        scale = 1f
        tx = 0f
        ty = 0f
        apply()
    }

    private fun apply() {
        val d = drawable ?: return
        val w = d.intrinsicWidth * base * scale
        val h = d.intrinsicHeight * base * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
        m.reset()
        m.postScale(base * scale, base * scale)
        m.postTranslate(tx, ty)
        imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        if (e.pointerCount > 1 || scaler.isInProgress) {
            dragging = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    tx += e.x - lastX
                    ty += e.y - lastY
                    apply()
                }
                lastX = e.x
                lastY = e.y
                dragging = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
