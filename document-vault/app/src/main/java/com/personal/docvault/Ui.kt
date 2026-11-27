package com.personal.docvault

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.TextView

/** Small helpers for building the screens in code. */
object Ui {
    val PRIMARY = Color.parseColor("#12355B")
    val ACCENT = Color.parseColor("#0E7490")
    val BG = Color.parseColor("#F3F5F8")
    val TEXT = Color.parseColor("#1D293B")
    val MUTED = Color.parseColor("#64748B")
    val LINE = Color.parseColor("#DCE2EA")
    val DANGER = Color.parseColor("#B42318")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radiusDp: Int, ctx: Context, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    fun ripple(ctx: Context, color: Int, radiusDp: Int, stroke: Int? = null): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33000000")),
            round(color, radiusDp, ctx, stroke), round(Color.WHITE, radiusDp, ctx),
        )

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** Filled, rounded, full-width action button. */
    fun button(ctx: Context, label: String, color: Int = ACCENT): TextView =
        text(ctx, label, 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ripple(ctx, color, 12)
            minHeight = dp(ctx, 52)
            isClickable = true
            isFocusable = true
        }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
