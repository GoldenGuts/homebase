package in_.weenja.hawidgets

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Catalog

/**
 * Setup guide of one Extra: what it needs in Home Assistant and a copy-paste YAML package
 * (assets/extras/<kind>.txt and <kind>.yaml).
 */
class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind") ?: run { finish(); return }
        val title = Catalog.guides.firstOrNull { it.first == kind }?.second ?: kind
        val col = V.page(this, title, "An Extra: it needs a few things in Home Assistant first.")
        val guide = asset("extras/$kind.txt")
        val yaml = asset("extras/$kind.yaml")
        guide?.split("\n\n")?.forEach { para ->
            if (para.startsWith("# ")) col.addView(V.h2(this, para.removePrefix("# ").trim()))
            else col.addView(V.body(this, para.trim()).apply { setPadding(0, V.dp(this@GuideActivity, 6f), 0, V.dp(this@GuideActivity, 6f)) })
        }
        if (yaml != null) {
            col.addView(V.h2(this, "Home Assistant package"))
            col.addView(V.muted(this, "Save it as config/packages/homebase_$kind.yaml (with `homeassistant: packages: !include_dir_named packages` in configuration.yaml) and restart Home Assistant."))
            col.addView(V.button(this, "Copy YAML", V.Style.PRIMARY) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("homebase_$kind.yaml", yaml))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@GuideActivity, 10f) } })
            val code = V.text(this, yaml, 11.5f, 400, V.INK).apply {
                typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
                setPadding(V.dp(this@GuideActivity, 12f), V.dp(this@GuideActivity, 12f), V.dp(this@GuideActivity, 12f), V.dp(this@GuideActivity, 12f))
            }
            val scroller = HorizontalScrollView(this).apply { background = V.rounded(V.SURFACE, V.dp(this@GuideActivity, 14f).toFloat()); addView(code) }
            col.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@GuideActivity, 10f) })
        }
    }

    private fun asset(path: String): String? = try { assets.open(path).use { String(it.readBytes()) } } catch (e: Exception) { null }
}
