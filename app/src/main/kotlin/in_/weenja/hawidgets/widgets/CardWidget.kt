package in_.weenja.hawidgets.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.WidgetStore
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Time
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Base for every widget: draws the card into a bitmap at the widget's exact dp size and lays
 * invisible tap zones over it. Subclasses only implement [draw]; they read their configuration
 * through `a.cfg` (this widget's own slots first, then the global defaults).
 */
abstract class CardWidget : AppWidgetProvider() {

    /** Paint the card into [p] (dp units) and return the tap zones. */
    abstract fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot>

    /** Draw the "updated N min ago" line under the card. Tiles and full-bleed cards opt out. */
    open val freshness: Boolean get() = true

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = Refresh.current(ctx)
        for (id in ids) try { render(ctx, mgr, id, snap) } catch (e: Exception) { Log.e(Api.TAG, "render", e) }
        Refresh.enqueueNow(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        try { render(ctx, mgr, id, Refresh.current(ctx)) } catch (e: Exception) { Log.e(Api.TAG, "render", e) }
    }

    override fun onEnabled(ctx: Context) { Refresh.schedule(ctx) }

    override fun onDisabled(ctx: Context) { if (Refresh.widgetCount(ctx) == 0) Refresh.cancel(ctx) }

    override fun onDeleted(ctx: Context, ids: IntArray) { WidgetStore.remove(ctx, ids) }

    /** Backup restore on a new phone: the launcher re-creates the widgets under new ids. */
    override fun onRestored(ctx: Context, oldIds: IntArray, newIds: IntArray) { WidgetStore.remap(ctx, oldIds, newIds) }

    /**
     * The widget's configuration: its own, else one restored from a backup file, else a fresh auto-fill
     * from the last scan (favorites → common_control → area and domain rules). Null keeps the global defaults.
     */
    fun specFor(ctx: Context, id: Int, snap: Snapshot): WidgetSpec? {
        WidgetStore.get(ctx, id)?.let { return it }
        val entry = Catalog.of(this::class.java) ?: return null
        WidgetStore.takePending(ctx, entry.id)?.let { WidgetStore.put(ctx, id, it); return it }
        val kind = in_.weenja.hawidgets.core.Kinds.get(entry.kind) ?: return null
        if (!kind.autoFill || snap.demo || !HomeStore.hasScan(ctx)) return null
        val home = HomeStore.home(ctx, snap)
        val others = WidgetStore.all(ctx).filterKeys { it != id }.values.filter { it.kind == entry.kind }
        val taken = others.mapNotNull { it.option("area") }.toSet() + others.flatMap { it.entityIds }
        val spec = Planner.fill(entry.kind, home, 12, taken) ?: return null
        WidgetStore.put(ctx, id, spec)
        return spec
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int, snap: Snapshot) {
        val spec = specFor(ctx, id, snap)
        val opts = mgr.getAppWidgetOptions(id)
        val sizes = sizesFor(opts)
        val views = LinkedHashMap<SizeF, RemoteViews>()
        for (s in sizes) views[s] = build(ctx, s.width, s.height, snap, spec, id)
        val rv = if (views.size == 1) views.values.first() else RemoteViews(views)
        mgr.updateAppWidget(id, rv)
    }

    /** The sizes the launcher may show this widget at (portrait + landscape on a phone). */
    private fun sizesFor(opts: Bundle): List<SizeF> {
        @Suppress("DEPRECATION")
        val list = opts.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        if (!list.isNullOrEmpty()) return list.filter { it.width > 40 && it.height > 40 }.distinct().take(2).ifEmpty { listOf(SizeF(300f, 180f)) }
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).toFloat()
        val maxW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300).toFloat()
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).toFloat()
        val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).toFloat()
        // Samsung's cover screen can report 0 x 0 before it lays the widget out
        if (minW <= 40f || maxH <= 40f) return listOf(SizeF(300f, 180f))
        val portrait = SizeF(minW, maxH); val landscape = SizeF(maxW, minH)
        return if (portrait == landscape) listOf(portrait) else listOf(portrait, landscape)
    }

    /** Bitmap + tap zones for one size. Public so previews and setWidgetPreview can call it. */
    fun build(ctx: Context, wDp: Float, hDp: Float, snap: Snapshot, spec: WidgetSpec? = null, widgetId: Int = 0): RemoteViews {
        val density = ctx.resources.displayMetrics.density
        // RemoteViews bitmaps cross Binder; keep the biggest one around 3 MB
        val cap = sqrt(3_000_000f / 4f / (wDp * hDp).coerceAtLeast(1f))
        val scale = min(density, cap).coerceAtLeast(1f)
        val p = Painter(ctx, wDp, hDp, scale, Palette.of(ctx))
        val a = Actions(ctx, widgetId, spec)
        Common.symbol = a.cfg.currency
        val entry = Catalog.of(this::class.java)
        if (!in_.weenja.hawidgets.Pro.allowed(ctx, entry?.id)) return locked(ctx, p, entry?.title ?: "", widgetId)
        val spots = paint(ctx, p, snap, a)

        val rv = RemoteViews(ctx.packageName, R.layout.widget_frame)
        rv.setImageViewBitmap(R.id.img, p.bitmap)
        rv.setContentDescription(R.id.img, describe(ctx, a))
        rv.setOnClickPendingIntent(R.id.img, if (Prefs(ctx).isLoggedIn) a.open() else a.app())
        spots.take(HOT_IDS.size).forEachIndexed { i, h ->
            val vid = HOT_IDS[i]
            rv.setViewVisibility(vid, android.view.View.VISIBLE)
            rv.setViewLayoutWidth(vid, h.rect.width(), TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewLayoutHeight(vid, h.rect.height(), TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(vid, RemoteViews.MARGIN_LEFT, h.rect.left, TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(vid, RemoteViews.MARGIN_TOP, h.rect.top, TypedValue.COMPLEX_UNIT_DIP)
            rv.setOnClickPendingIntent(vid, h.intent)
            h.label?.let { rv.setContentDescription(vid, it) }
        }
        for (i in spots.size until HOT_IDS.size) rv.setViewVisibility(HOT_IDS[i], android.view.View.GONE)
        return rv
    }

    /** A Pro widget on a free install: what it is, and a tap that opens the unlock screen. */
    private fun locked(ctx: Context, p: Painter, title: String, widgetId: Int): RemoteViews {
        val pal = p.p
        p.card()
        val small = p.w < 200f || p.h < 120f
        val cy = p.h / 2
        if (!small) p.text(title, 20f, 28f, 14f, 700, pal.muted, maxW = p.w - 40f)
        p.circle(p.w / 2, cy - (if (small) 14f else 18f), if (small) 16f else 20f, pal.mix(pal.cyan, .18f))
        p.icon(R.drawable.ic_lock, p.w / 2, cy - (if (small) 14f else 18f), if (small) 16f else 20f, pal.cyan.toInt())
        p.text("Homebase Pro", p.w / 2, cy + (if (small) 12f else 16f), if (small) 12f else 14f, 700, pal.ink, Align.CENTER, maxW = p.w - 16f)
        p.text(if (small) "Tap to unlock" else "Tap to unlock every widget, once", p.w / 2, cy + (if (small) 27f else 34f), 11f, 500, pal.muted, Align.CENTER, maxW = p.w - 16f)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_frame)
        rv.setImageViewBitmap(R.id.img, p.bitmap)
        rv.setContentDescription(R.id.img, "$title · Homebase Pro, tap to unlock")
        val open = android.content.Intent(ctx, in_.weenja.hawidgets.ProActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        rv.setOnClickPendingIntent(R.id.img, android.app.PendingIntent.getActivity(ctx, 70_000 + widgetId, open,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        for (vid in HOT_IDS) rv.setViewVisibility(vid, android.view.View.GONE)
        return rv
    }

    private fun describe(ctx: Context, a: Actions): String =
        Catalog.of(this::class.java)?.title?.let { t -> a.cfg.title.takeIf { it.isNotBlank() && it != t }?.let { "$t · $it" } ?: t } ?: ctx.getString(R.string.app_name)

    /** Same drawing, as a plain bitmap + zones, for the previews in the app. */
    fun preview(ctx: Context, wDp: Float, hDp: Float, snap: Snapshot, spec: WidgetSpec? = null, widgetId: Int = 0,
                scale: Float = ctx.resources.displayMetrics.density): Pair<android.graphics.Bitmap, List<Hotspot>> {
        val p = Painter(ctx, wDp, hDp, scale, Palette.of(ctx))
        val a = Actions(ctx, widgetId, spec)
        Common.symbol = a.cfg.currency
        return p.bitmap to paint(ctx, p, snap, a)
    }

    private fun paint(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val spots = try { draw(ctx, p, snap, a).toMutableList() } catch (e: Exception) { Log.e(Api.TAG, "draw", e); drawError(p, e); mutableListOf() }
        if (freshness) statusLine(ctx, p, snap, a)?.let { spots.add(minOf(1, spots.size), it) }
        return spots
    }

    /**
     * The line under the card: "updated 4 min ago" (tap = refresh), "offline · updated 25 min ago",
     * or "1 entity not found · tap to fix" when Home Assistant renamed or removed something.
     */
    private fun statusLine(ctx: Context, p: Painter, snap: Snapshot, a: Actions): Hotspot? {
        if (p.h < 96f || p.w < 170f) return null
        val prefs = Prefs(ctx)
        if (snap.demo) return null
        val now = System.currentTimeMillis()
        val missing = a.spec?.entityIds?.count { snap[it] == null } ?: 0
        val age = now - snap.fetchedAt
        val (text, color, pi) = when {
            missing > 0 -> Triple("$missing entit${if (missing == 1) "y" else "ies"} not found · tap to fix", p.p.error.toInt(), a.configure())
            prefs.lastError != null -> Triple("offline · updated ${Time.ago(snap.fetchedAt, now)}", p.p.error.toInt(), a.refresh())
            age > STALE_MS -> Triple("updated ${Time.ago(snap.fetchedAt, now)} · tap to refresh", p.p.orange.toInt(), a.refresh())
            else -> Triple("updated ${Time.ago(snap.fetchedAt, now)}", p.p.dim.toInt(), a.refresh())
        }
        val y = p.h - 8.5f
        val tw = p.text(text, p.w / 2 + 6f, y, 9.5f, 500, color, Align.CENTER, maxW = p.w - 60f)
        p.icon(R.drawable.ic_refresh, p.w / 2 + 6f - tw / 2 - 8f, y, 10f, color)
        return Hotspot(RectF(p.w / 2 - tw / 2 - 24f, p.h - 17f, p.w / 2 + tw / 2 + 24f, p.h), pi, text)
    }

    private fun drawError(p: Painter, e: Exception) {
        p.card()
        p.text("Widget error", 20f, 24f, 14f, 700, p.p.error.toInt())
        p.paragraph(e.toString(), 20f, 40f, p.w - 40f, 11f, 400, p.p.muted.toInt(), 4)
    }

    companion object {
        /** Data older than this is drawn with a "tap to refresh" hint. */
        const val STALE_MS = 30 * 60_000L

        val HOT_IDS = intArrayOf(R.id.h0, R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5, R.id.h6, R.id.h7, R.id.h8, R.id.h9,
            R.id.h10, R.id.h11, R.id.h12, R.id.h13, R.id.h14, R.id.h15, R.id.h16, R.id.h17, R.id.h18, R.id.h19)
    }
}
