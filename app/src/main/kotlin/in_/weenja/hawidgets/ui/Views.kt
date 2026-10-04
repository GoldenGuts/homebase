package in_.weenja.hawidgets.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import in_.weenja.hawidgets.R

/**
 * The app's screens are built in code with these helpers: one dark theme (the widgets' neon-candy
 * palette), rounded cards, pill buttons. No Compose, no Material library.
 */
object V {
    const val BG = 0xff0d0f14.toInt()
    const val CARD = 0xff171a22.toInt()
    const val SURFACE = 0xff1d2130.toInt()
    const val BORDER = 0xff242835.toInt()
    const val INK = 0xfff3f4f8.toInt()
    const val MUTED = 0xff8a90a3.toInt()
    const val DIM = 0xff5a6075.toInt()
    const val CYAN = 0xff3fe3ff.toInt()
    const val PINK = 0xffff4fa3.toInt()
    const val LIME = 0xffb5e21c.toInt()
    const val ORANGE = 0xffff9f2e.toInt()
    const val PURPLE = 0xff7b61ff.toInt()

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun font(ctx: Context, weight: Int): Typeface = Fonts.get(ctx, weight)

    fun rounded(color: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx; if (strokePx > 0) setStroke(strokePx, stroke)
    }

    /** Full-screen vertical scroll page with a column; returns the column. */
    fun page(a: Activity, title: String, subtitle: String? = null): LinearLayout {
        val scroll = ScrollView(a).apply { setBackgroundColor(BG); fitsSystemWindows = true; isFillViewport = true }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16f), dp(a, 18f), dp(a, 16f), dp(a, 28f)) }
        scroll.addView(col)
        a.setContentView(scroll)
        a.window.statusBarColor = BG; a.window.navigationBarColor = BG
        col.addView(text(a, title, 26f, 800, INK))
        subtitle?.let { col.addView(text(a, it, 13.5f, 400, MUTED).apply { setPadding(0, dp(a, 4f), 0, 0) }) }
        return col
    }

    fun text(ctx: Context, s: CharSequence, sp: Float, weight: Int = 400, color: Int = INK): TextView = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); typeface = font(ctx, weight)
        setLineSpacing(0f, 1.15f)
    }

    fun h2(ctx: Context, s: String) = text(ctx, s, 17f, 700, INK).apply { setPadding(0, dp(ctx, 24f), 0, dp(ctx, 6f)) }
    fun body(ctx: Context, s: CharSequence) = text(ctx, s, 14f, 400, INK)
    fun muted(ctx: Context, s: CharSequence, sp: Float = 12.5f) = text(ctx, s, sp, 400, MUTED)

    /** Rounded card; returns the inner column. */
    fun card(ctx: Context, parent: ViewGroup, pad: Float = 16f, top: Float = 10f): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, dp(ctx, 20f).toFloat(), BORDER, dp(ctx, 1f))
            setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
        }
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) })
        return c
    }

    enum class Style { PRIMARY, SECONDARY, GHOST, DANGER }

    fun button(ctx: Context, label: String, style: Style = Style.SECONDARY, icon: Int? = null, onClick: (View) -> Unit): TextView = TextView(ctx).apply {
        text = label; gravity = Gravity.CENTER; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = font(ctx, 700)
        val (bg, fg) = when (style) {
            Style.PRIMARY -> CYAN to 0xff06131b.toInt()
            Style.SECONDARY -> SURFACE to INK
            Style.GHOST -> Color.TRANSPARENT to CYAN
            Style.DANGER -> 0xff3a1a26.toInt() to PINK
        }
        setTextColor(fg)
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), rounded(bg, dp(ctx, 22f).toFloat(), if (style == Style.GHOST) BORDER else 0, if (style == Style.GHOST) dp(ctx, 1f) else 0), null)
        setPadding(dp(ctx, 16f), dp(ctx, 11f), dp(ctx, 16f), dp(ctx, 11f))
        minHeight = dp(ctx, 44f)
        icon?.let { res ->
            val d = ctx.getDrawable(res)?.mutate()?.apply { setTint(fg); setBounds(0, 0, dp(ctx, 18f), dp(ctx, 18f)) }
            setCompoundDrawables(d, null, null, null); compoundDrawablePadding = dp(ctx, 8f)
        }
        isClickable = true; isFocusable = true
        setOnClickListener(onClick)
    }

    /** Buttons side by side, sharing the width. */
    fun row(ctx: Context, vararg views: View, gap: Float = 8f, top: Float = 10f): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(ctx, gap) })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, top) }
    }

    fun field(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS): EditText = EditText(ctx).apply {
        this.hint = hint; setText(value); setHintTextColor(DIM); setTextColor(INK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = font(ctx, 400); inputType = type; setSingleLine()
        background = rounded(SURFACE, dp(ctx, 14f).toFloat())
        setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8f) }
    }

    fun label(ctx: Context, s: String) = text(ctx, s.uppercase(), 11f, 700, MUTED).apply { letterSpacing = .08f; setPadding(0, dp(ctx, 14f), 0, 0) }

    fun spinner(ctx: Context): ProgressBar = ProgressBar(ctx).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(CYAN) }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Float = 22f): ImageView = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    /** A widget preview bitmap at its dp size, rounded by the widget itself. */
    fun preview(ctx: Context, bmp: android.graphics.Bitmap, wDp: Float, hDp: Float): ImageView = ImageView(ctx).apply {
        setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true
        layoutParams = LinearLayout.LayoutParams(dp(ctx, wDp), dp(ctx, hDp)).apply { topMargin = dp(ctx, 10f); gravity = Gravity.CENTER_HORIZONTAL }
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply { topMargin = dp(ctx, 12f); bottomMargin = dp(ctx, 4f) }
    }

    fun spacer(ctx: Context, h: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h)) }

    fun frame(ctx: Context): FrameLayout = FrameLayout(ctx)
}
