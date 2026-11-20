package com.music.bitchord.lockscreen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * A TextView that can draw a dark outline under its letters, so the floating
 * bar's words stay readable over a light screen once its background has been
 * turned down. The outline is the same text drawn first with a stroke; the
 * colour swap in between must not ask for another draw, hence [drawing].
 */
@SuppressLint("AppCompatCustomView")
internal class EdgedTextView(context: Context) : TextView(context) {
    /** The outline's width in pixels; 0 for none. */
    var outlineWidth: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var outlineColor: Int = 0xE6000000.toInt()

    private var drawing = false

    override fun invalidate() {
        if (!drawing) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth <= 0f) {
            super.onDraw(canvas)
            return
        }
        drawing = true
        val fill = currentTextColor
        val shadowRadius = shadowRadius
        val shadowDx = shadowDx
        val shadowDy = shadowDy
        val shadowColor = shadowColor
        try {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paint.strokeJoin = Paint.Join.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            // The outline already carries the edge: no shadow under the fill as well.
            setShadowLayer(0f, 0f, 0f, 0)
            setTextColor(fill)
            super.onDraw(canvas)
        } finally {
            setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
            paint.style = Paint.Style.FILL
            drawing = false
        }
    }
}
