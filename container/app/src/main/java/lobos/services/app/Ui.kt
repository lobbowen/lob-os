package lobos.services.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = "#0B0F14"
    const val SURFACE = "#131A22"
    const val SURFACE2 = "#1B2430"
    const val STROKE = "#243040"
    const val TEXT = "#E6EDF3"
    const val TEXT2 = "#9FB0C0"
    const val TEXT3 = "#6E7F90"
    const val OK = "#3FB950"
    const val WARN = "#D29922"
    const val ERR = "#F85149"
    const val ACCENT = "#2F81F7"

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun sp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.scaledDensity

    fun rounded(ctx: Context, fill: String, radiusDp: Int = 16, stroke: String? = null): Drawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        g.setColor(Color.parseColor(fill))
        if (stroke != null) g.setStroke(dp(ctx, 1), Color.parseColor(stroke))
        return g
    }

    fun pressable(ctx: Context, fill: String, radiusDp: Int = 16, stroke: String? = null): Drawable {
        val bg = rounded(ctx, fill, radiusDp, stroke)
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            bg,
            null as Drawable?,
        )
    }

    fun text(
        ctx: Context,
        s: CharSequence,
        color: String = TEXT,
        sizeSp: Float = 14f,
        bold: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = s
        setTextColor(Color.parseColor(color))
        textSize = sizeSp
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        includeFontPadding = false
        setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
    }

    fun card(ctx: Context, onClick: (() -> Unit)? = null): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = pressable(ctx, SURFACE, 16, STROKE)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        return box
    }

    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor("#0B0F14"))
            background = pressable(ctx, ACCENT, 12)
            setOnClickListener { onClick() }
            minHeight = dp(ctx, 44)
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        }

    fun outlineButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(TEXT))
            background = pressable(ctx, SURFACE2, 12, STROKE)
            setOnClickListener { onClick() }
            minHeight = dp(ctx, 44)
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        }

    fun topBar(ctx: Context, title: String, subtitle: String? = null): LinearLayout {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 8))
        }
        bar.addView(text(ctx, title, TEXT, 22f, bold = true))
        if (!subtitle.isNullOrBlank()) bar.addView(text(ctx, subtitle, TEXT2, 13f))
        return bar
    }

    fun separator(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        setBackgroundColor(Color.parseColor(STROKE))
    }

    fun verticalGap(ctx: Context, dp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, dp))
    }

    fun applyInsets(root: View, ctx: Context, bottomNav: View? = null) {
        val w = ctx as? android.app.Activity ?: return
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top = insets.systemWindowInsetTop
            val bottom = insets.systemWindowInsetBottom
            v.setPadding(0, top, 0, 0)
            bottomNav?.setPadding(0, 0, 0, bottom)
            v.requestApplyInsets()
            insets
        }
    }

    fun bottomNav(
        ctx: Context,
        items: List<Triple<String, String, Class<*>?>>,
        current: Int,
    ): LinearLayout {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(SURFACE))
        }
        wrap.addView(separator(ctx))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 8))
        }
        for ((i, item) in items.withIndex()) {
            val (glyph, label, target) = item
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                val selected = i == current
                val color = if (selected) ACCENT else TEXT3
                addView(text(ctx, glyph, color, 18f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                })
                addView(text(ctx, label, color, 11f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                })
                if (target != null && !selected) {
                    isClickable = true
                    setOnClickListener {
                        runCatching {
                            ctx.startActivity(Intent(ctx, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                        }
                        (ctx as? android.app.Activity)?.overridePendingTransition(0, 0)
                    }
                }
            }
            bar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        wrap.addView(bar)
        return wrap
    }

    fun restyleButtons(root: View) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is Button) {
                v.isAllCaps = false
                v.textSize = 14f
                v.setTextColor(Color.parseColor(TEXT))
                v.background = pressable(root.context, SURFACE2, 12, STROKE)
                v.minHeight = dp(root.context, 44)
                v.setPadding(dp(root.context, 16), 0, dp(root.context, 16), 0)
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c != null) stack.addLast(c)
                }
            }
        }
    }

    fun scaffold(
        activity: android.app.Activity,
        title: String,
        subtitle: String?,
        nav: List<Triple<String, String, Class<*>?>>,
        current: Int,
    ): Pair<LinearLayout, LinearLayout> {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
        }
        val bar = topBar(activity, title, subtitle)
        root.addView(bar)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = true
            addView(body, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 16))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = bottomNav(activity, nav, current)
        root.addView(bottom)
        applyInsets(root, activity, bottom)
        activity.setContentView(root)
        return Pair(body, root)
    }
}