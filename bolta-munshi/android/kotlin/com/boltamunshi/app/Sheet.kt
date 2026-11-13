package com.boltamunshi.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The settings screens' look: a dark rounded sheet, grouped rows with thin lines between
 * them, small status lines with a coloured dot, switches and pill buttons. Built in code,
 * as the rest of the app is (no layout files).
 */
object Sheet {
    const val BG = 0xFF111113.toInt()
    const val GROUP = 0xFF18181C.toInt()
    const val LINE = 0xFF25252B.toInt()
    const val FIELD = 0xFF0E0E11.toInt()
    const val TEXT = 0xFFECECF1.toInt()
    const val SUB = 0xFF8E8E99.toInt()
    const val DIM = 0xFF75757F.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val AMBER = 0xFFF5B14A.toInt()
    const val RED = 0xFFF87171.toInt()
    const val BLUE = 0xFF9AA6FF.toInt()
    const val OFF = 0xFF3A3A42.toInt()

    fun interface OnFlag { fun changed(on: Boolean) }
    fun interface OnText { fun done(text: String) }

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun round(c: Context, color: Int, radius: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(c, radius).toFloat()
        if (stroke != 0) setStroke(dp(c, 1f), stroke)
    }

    class Page(val dialog: Dialog, val body: LinearLayout)

    /** A sheet with a title, an optional line under it, and a back arrow when [onBack] is given. */
    fun open(c: Context, title: String, subtitle: String?, onBack: Runnable?): Page {
        val d = Dialog(c)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(c, BG, 24f, LINE)
        }
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, if (onBack == null) 20f else 8f), dp(c, 14f), dp(c, 16f), dp(c, 4f))
        }
        if (onBack != null) {
            head.addView(TextView(c).apply {
                text = "‹"; textSize = 30f; setTextColor(TEXT); gravity = Gravity.CENTER
                contentDescription = "Back"
                setOnClickListener { d.dismiss(); onBack.run() }
            }, LinearLayout.LayoutParams(dp(c, 44f), dp(c, 44f)))
        }
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(c).apply { text = title; textSize = 19f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD })
        if (subtitle != null) titles.addView(TextView(c).apply { text = subtitle; textSize = 12f; setTextColor(SUB) })
        head.addView(titles)
        card.addView(head)
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 14f))
        }
        card.addView(body)
        val scroll = ScrollView(c)
        scroll.addView(card)
        d.setContentView(scroll)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.decorView.setPadding(dp(c, 16f), dp(c, 16f), dp(c, 16f), dp(c, 16f))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.attributes = w.attributes.apply { dimAmount = 0.72f }
        }
        return Page(d, body)
    }

    private fun gap(c: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 12f) }

    /** A rounded box that holds rows. */
    fun group(body: LinearLayout): LinearLayout {
        val c = body.context
        val g = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = round(c, GROUP, 18f, LINE) }
        body.addView(g, gap(c))
        return g
    }

    /** A small grey heading over a group, like "MODEL". */
    fun label(body: LinearLayout, text: String) {
        val c = body.context
        body.addView(TextView(c).apply { this.text = text; textSize = 12f; setTextColor(SUB); setPadding(dp(c, 6f), 0, 0, dp(c, 6f)) })
    }

    /** A small centred note. */
    fun note(body: LinearLayout, text: String) {
        val c = body.context
        body.addView(TextView(c).apply { this.text = text; textSize = 11f; setTextColor(DIM); gravity = Gravity.CENTER }, gap(c))
    }

    class Row(val view: LinearLayout, val sub: TextView, val dot: View, val right: TextView?) {
        /** The line under the name, with its dot in the same colour. */
        fun status(text: String, color: Int) {
            sub.text = text; sub.setTextColor(color)
            dot.visibility = View.VISIBLE
            (dot.background as GradientDrawable).setColor(color)
        }
        fun plain(text: String) { sub.text = text; sub.setTextColor(SUB); dot.visibility = View.GONE }
    }

    private fun divider(g: LinearLayout) {
        if (g.childCount == 0) return
        g.addView(View(g.context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    /**
     * A row: an icon tile (or none), the name with a line under it, and on the right a word
     * ("Change", "›") or a view (a switch, a radio). [onClick] makes the name part tappable.
     */
    fun row(g: LinearLayout, icon: String?, iconColor: Int, name: String, sub: String?,
            right: String?, rightColor: Int, rightView: View?, onClick: Runnable?): Row {
        val c = g.context
        divider(g)
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(c, 56f)
            setPadding(dp(c, 12f), dp(c, 8f), dp(c, 12f), dp(c, 8f))
        }
        if (icon != null) {
            r.addView(TextView(c).apply {
                text = icon; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (iconColor == OFF) TEXT else 0xFF0D0D0F.toInt())
                background = round(c, iconColor, 9f)
            }, LinearLayout.LayoutParams(dp(c, 30f), dp(c, 30f)).apply { rightMargin = dp(c, 12f) })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply { text = name; textSize = 15f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD })
        val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(c).apply { background = round(c, SUB, 3f); visibility = View.GONE }
        line.addView(dot, LinearLayout.LayoutParams(dp(c, 6f), dp(c, 6f)).apply { rightMargin = dp(c, 6f) })
        val subView = TextView(c).apply { textSize = 12f; setTextColor(SUB); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; text = sub ?: "" }
        line.addView(subView)
        if (sub != null) texts.addView(line)
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var rightText: TextView? = null
        if (right != null) {
            rightText = TextView(c).apply { text = right; textSize = if (right.length <= 2) 20f else 14f; setTextColor(rightColor); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(c, 8f), 0, 0, 0) }
            r.addView(rightText)
        }
        if (rightView != null) r.addView(rightView)
        if (onClick != null) r.setOnClickListener { onClick.run() }
        g.addView(r)
        return Row(r, subView, dot, rightText)
    }

    /** An on/off switch: green track when on, grey when off. */
    class Toggle(c: Context, start: Boolean) : FrameLayout(c) {
        var on = start; private set
        var onChange: OnFlag? = null
        private val track = round(c, if (start) GREEN else OFF, 13f)
        private val knob = View(c).apply { background = round(c, Color.WHITE, 10f) }
        init {
            background = track
            addView(knob, LayoutParams(dp(c, 20f), dp(c, 20f)).apply { topMargin = dp(c, 3f) })
            layoutParams = LinearLayout.LayoutParams(dp(c, 44f), dp(c, 26f)).apply { leftMargin = dp(c, 10f) }
            contentDescription = "On or off"
            place()
            setOnClickListener { set(!on); onChange?.changed(on) }
        }
        fun set(v: Boolean) { on = v; track.setColor(if (on) GREEN else OFF); place() }
        private fun place() { (knob.layoutParams as LayoutParams).leftMargin = dp(context, if (on) 21f else 3f); knob.requestLayout() }
    }

    /** A round choice mark: amber ring and dot when chosen. */
    class Radio(c: Context, start: Boolean) : FrameLayout(c) {
        private val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(c, 2f), if (start) AMBER else 0xFF4A4A52.toInt()) }
        private val dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) } }
        init {
            background = ring
            addView(dot, LayoutParams(dp(c, 10f), dp(c, 10f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(c, 22f), dp(c, 22f)).apply { leftMargin = dp(c, 10f) }
            set(start)
        }
        fun set(v: Boolean) { ring.setStroke(dp(context, 2f), if (v) AMBER else 0xFF4A4A52.toInt()); dot.visibility = if (v) View.VISIBLE else View.INVISIBLE }
    }

    fun field(c: Context, hint: String, value: String, secret: Boolean): EditText = EditText(c).apply {
        this.hint = hint; setText(value); textSize = 14f; setTextColor(TEXT); setHintTextColor(DIM)
        setSingleLine(true)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background = round(c, FIELD, 12f, 0xFF2C2C33.toInt())
        setPadding(dp(c, 12f), 0, dp(c, 12f), 0)
    }

    fun pill(c: Context, text: String, onClick: Runnable): TextView = TextView(c).apply {
        this.text = text; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFF0D0D0F.toInt()); background = round(c, TEXT, 12f)
        setPadding(dp(c, 14f), 0, dp(c, 14f), 0)
        setOnClickListener { onClick.run() }
    }

    /** A key box with a Save pill beside it; [indent] lines it up under a row's name. */
    fun keyLine(g: LinearLayout, hint: String, value: String, secret: Boolean, indent: Boolean, onSave: OnText): LinearLayout {
        val c = g.context
        val line = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, if (indent) 54f else 12f), if (indent) 0 else dp(c, 10f), dp(c, 12f), dp(c, 12f))
        }
        val f = field(c, hint, value, secret)
        line.addView(f, LinearLayout.LayoutParams(0, dp(c, 44f), 1f).apply { rightMargin = dp(c, 8f) })
        line.addView(pill(c, "Save") { onSave.done(f.text.toString().trim()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 44f)))
        g.addView(line)
        return line
    }

    /** A status line on its own (under a key box): dot and text. */
    fun statusLine(g: LinearLayout): Row {
        val c = g.context
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 14f), 0, dp(c, 14f), dp(c, 10f)) }
        val dot = View(c).apply { background = round(c, SUB, 3f) }
        r.addView(dot, LinearLayout.LayoutParams(dp(c, 6f), dp(c, 6f)).apply { rightMargin = dp(c, 6f) })
        val t = TextView(c).apply { textSize = 12f; setTextColor(SUB) }
        r.addView(t)
        g.addView(r)
        return Row(r, t, dot, null)
    }

    fun primary(body: LinearLayout, text: String, onClick: Runnable): TextView {
        val c = body.context
        val b = TextView(c).apply {
            this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF0D0D0F.toInt()); background = round(c, TEXT, 14f)
            setOnClickListener { onClick.run() }
        }
        body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48f)))
        return b
    }

    /** A bar from 0 to [total]: amber filled part, dark rest. */
    class Bar(c: Context) : LinearLayout(c) {
        private val fill = View(c).apply { background = round(c, AMBER, 4f) }
        private val rest = View(c)
        init {
            orientation = HORIZONTAL
            background = round(c, LINE, 4f)
            addView(fill, LayoutParams(0, dp(c, 8f), 0f))
            addView(rest, LayoutParams(0, dp(c, 8f), 1f))
        }
        fun set(done: Int, total: Int, color: Int = AMBER) {
            val t = maxOf(1, total)
            (fill.layoutParams as LayoutParams).weight = done.coerceIn(0, t).toFloat()
            (rest.layoutParams as LayoutParams).weight = (t - done.coerceIn(0, t)).toFloat()
            (fill.background as GradientDrawable).setColor(color)
            requestLayout()
        }
    }

    /** A sheet that shows work going on: a big count, a bar, the current line, and one button. */
    class Progress(val page: Page, val count: TextView, val bar: Bar, val line: TextView, val note: TextView, val button: TextView) {
        fun set(done: Int, total: Int, text: String) {
            count.text = "$done / $total"; bar.set(done, total); line.text = text
        }
        fun finish(text: String, color: Int, onDone: Runnable) {
            note.text = text; note.setTextColor(color)
            button.text = "OK"
            button.setOnClickListener { page.dialog.dismiss(); onDone.run() }
        }
    }

    fun progress(c: Context, title: String, subtitle: String, onStop: Runnable): Progress {
        val p = open(c, title, subtitle, null)
        p.dialog.setCancelable(false)
        val count = TextView(c).apply { textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TEXT) }
        p.body.addView(count)
        val bar = Bar(c)
        p.body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8f)).apply { topMargin = dp(c, 10f); bottomMargin = dp(c, 12f) })
        val line = TextView(c).apply { textSize = 14f; setTextColor(0xFFC9C9D1.toInt()); maxLines = 3 }
        p.body.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 8f) })
        val note = TextView(c).apply { textSize = 12f; setTextColor(SUB) }
        p.body.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 12f) })
        val b = TextView(c).apply {
            text = "Stop"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(TEXT); background = round(c, GROUP, 14f, 0xFF2C2C33.toInt())
            setOnClickListener { onStop.run() }
        }
        p.body.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48f)))
        p.dialog.show()
        return Progress(p, count, bar, line, note, b)
    }

    /** Small boxes side by side, each a number over a word ("265" over "Words"). */
    fun stats(body: LinearLayout, vararg items: Pair<String, String>): List<TextView> {
        val c = body.context
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val numbers = mutableListOf<TextView>()
        items.forEachIndexed { i, (num, word) ->
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = round(c, GROUP, 14f, LINE)
                setPadding(dp(c, 12f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
            }
            val n = TextView(c).apply { text = num; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TEXT) }
            box.addView(n); numbers += n
            box.addView(TextView(c).apply { text = word; textSize = 12f; setTextColor(SUB) })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(c, 8f) })
        }
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(c, 12f) })
        return numbers
    }
}

