package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Painter
import org.json.JSONArray
import org.json.JSONObject

/** "Coming up": predicted renewals (pills) + card dues (rows), the bottom half of the spend card on its own. */
class DuesWidget : CardWidget() {
    private val spend = SpendWidget()

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val feed = snap[a.cfg["spend_feed"]]?.attrs ?: JSONObject()
        val up = feed.optJSONArray("coming_up") ?: JSONArray()
        val cards = feed.optJSONArray("cards") ?: JSONArray()

        // header meta: everything due in the next 14 days (renewals + card dues)
        var due14 = 0.0
        for (i in 0 until up.length()) { val d = Common.daysUntil(up.getJSONObject(i).optString("date")); if (d != null && d in 0..14) due14 += up.getJSONObject(i).optDouble("amount", 0.0) }
        for (i in 0 until cards.length()) { val d = Common.daysUntil(cards.getJSONObject(i).optString("due_date")); if (d != null && d in 0..14) due14 += cards.getJSONObject(i).optDouble("due", 0.0) }

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Coming up", if (badge == null) if (due14 > 0) "${Common.rs(due14)} in 14 days" else "next 5 weeks" else "", padX, y, innerW)
        y += 22f

        if (up.length() == 0 && cards.length() == 0) {
            Common.empty(p, RectF(padX, y, p.w - padX, bottom), Common.ICON_EMPTY, "Nothing predicted yet", "The spend feed sensor has no coming_up items")
            return spots
        }
        if (up.length() > 0) y = spend.pills(p, up, padX, y, innerW, bottom)
        if (cards.length() > 0 && bottom - y >= 40f) {
            p.tag("Card dues", padX, y + 8f); y += 20f
            for (i in 0 until cards.length()) {
                if (y + 28f > bottom) break
                spend.cardRow(p, cards.getJSONObject(i), padX, y, innerW); y += 28f
            }
        }
        return spots
    }
}
