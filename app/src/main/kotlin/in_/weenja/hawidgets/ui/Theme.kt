package in_.weenja.hawidgets.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import in_.weenja.hawidgets.core.Accent

/** Colour tokens of the HA theme "neon-candy" (homeassistant/themes/neon-candy.yaml), dark + light modes. */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xff0d0f14 else 0xffeef0f6
    val card = if (dark) 0xff171a22 else 0xffffffff
    val border = if (dark) 0xff242835 else 0xffe1e4ec
    val ink = if (dark) 0xfff3f4f8 else 0xff14161e
    val muted = if (dark) 0xff8a90a3 else 0xff6b7285
    val dim = if (dark) 0xff5a6075 else 0xff9aa1b3
    val surface2 = if (dark) 0xff1d2130 else 0xfff1f3f8
    val track = if (dark) 0xff262a36 else 0xffe3e6ee
    val orange = if (dark) 0xffff9f2e else 0xffff8a1e
    val pink = if (dark) 0xffff4fa3 else 0xffff2d8a
    val lime = if (dark) 0xffb5e21c else 0xff7fb800
    val cyan = if (dark) 0xff3fe3ff else 0xff0aa8e0
    val purple = if (dark) 0xff7b61ff else 0xff6a3dff
    val error = if (dark) 0xfffb5c7c else 0xffe0245e
    val pillDark = 0xff2a2f3e to 0xff1d2130

    // gradients (same in both modes)
    val gradOrange = 0xffffb84d to 0xffff8a1e
    val gradCyan = 0xff4fd7ff to 0xff1fa8ff
    val gradPink = 0xffff5c8a to 0xffff2d6f
    val gradLime = 0xffc6f04a to 0xff8fc31f
    val gradPurple = 0xff9d7bff to 0xff6a3dff

    // ink colours used on top of the gradients (device_tile variables.ink)
    val inkOnOrange = 0xff1b1206
    val inkOnCyan = 0xff06131b
    val inkOnLime = 0xff12200a
    val inkOnPink = 0xffffffff
    val inkOnPurple = 0xffffffff

    /** Solid colour of a shared-rules accent. */
    fun color(a: Accent): Long = when (a) { Accent.ORANGE -> orange; Accent.CYAN -> cyan; Accent.PINK -> pink; Accent.LIME -> lime; Accent.PURPLE -> purple; Accent.MUTED -> muted }

    /** Gradient of an active tile with this accent. */
    fun grad(a: Accent): Pair<Long, Long> = when (a) { Accent.ORANGE -> gradOrange; Accent.CYAN -> gradCyan; Accent.PINK -> gradPink; Accent.LIME -> gradLime; Accent.PURPLE -> gradPurple; Accent.MUTED -> pillDark }

    /** Ink on top of [grad]. */
    fun inkOn(a: Accent): Long = when (a) { Accent.ORANGE -> inkOnOrange; Accent.CYAN -> inkOnCyan; Accent.PINK -> inkOnPink; Accent.LIME -> inkOnLime; Accent.PURPLE -> inkOnPurple; Accent.MUTED -> ink }

    /** color-mix(in srgb, c 16%, surface2): the chip background. */
    fun mix(c: Long, pct: Float = 0.16f, base: Long = surface2): Int = blend(c.toInt(), base.toInt(), pct)

    companion object {
        fun of(ctx: Context): Palette {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return Palette(night)
        }

        fun blend(top: Int, bottom: Int, t: Float): Int {
            val a = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = ((top shr shift and 0xff) * a + (bottom shr shift and 0xff) * (1 - a)).toInt().coerceIn(0, 255)
            return Color.argb(255, ch(16), ch(8), ch(0))
        }

        fun alpha(c: Long, a: Float): Int = (c.toInt() and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
        fun alpha(c: Int, a: Float): Int = (c and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
