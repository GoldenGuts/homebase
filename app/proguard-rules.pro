# Widget providers are instantiated by class reference (Refresh.providers) and by the manifest.
-keep class in_.weenja.hawidgets.widgets.** extends android.appwidget.AppWidgetProvider { <init>(); }
-keep class in_.weenja.hawidgets.widgets.ActionReceiver { <init>(); }
-keep class in_.weenja.hawidgets.widgets.RefreshWorker { <init>(...); }
-dontwarn org.jetbrains.annotations.**
