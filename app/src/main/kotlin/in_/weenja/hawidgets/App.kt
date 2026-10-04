package in_.weenja.hawidgets

import android.app.Application
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.migrate(this)
        Extras.decide(this)
        // (re)arm the periodic refresh whenever the process starts; a no-op when no widget exists
        if (Refresh.widgetCount(this) > 0) Refresh.schedule(this)
        // Homebase Pro: what this Google account owns (a purchase on another phone, a refund)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { try { Pro.restore(this@App) } catch (_: Exception) {} }
    }
}
