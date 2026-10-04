package in_.weenja.hawidgets.widgets

/**
 * Every widget provider the app ships: its id (preview file names, backups), the shared kind it
 * implements, a title, whether it is a core widget, an Extra (needs the author's own Home Assistant
 * packages) or a legacy one, and its preview size in dp.
 */
object Catalog {
    enum class Group { CORE, EXTRAS, LEGACY }

    class Entry(
        val id: String,
        val cls: Class<out CardWidget>,
        val kind: String,
        val title: String,
        val group: Group,
        val w: Float,
        val h: Float,
        /** Global config groups this widget reads, editable per widget in its settings. */
        val configGroups: List<String> = emptyList(),
        /** Extra heights for the in-app gallery. */
        val heights: List<Float> = listOf(h),
    ) {
        val widget: CardWidget by lazy { cls.getDeclaredConstructor().newInstance() }
        val isTile get() = w < 100f || (h < 100f && id.startsWith("t_"))
    }

    private fun core(id: String, cls: Class<out CardWidget>, kind: String, title: String, w: Float, h: Float, vararg cfg: String, heights: List<Float> = listOf(h)) =
        Entry(id, cls, kind, title, Group.CORE, w, h, cfg.toList(), heights)
    private fun extra(id: String, cls: Class<out CardWidget>, kind: String, title: String, w: Float, h: Float, vararg cfg: String, heights: List<Float> = listOf(h)) =
        Entry(id, cls, kind, title, Group.EXTRAS, w, h, cfg.toList(), heights)
    private fun legacy(id: String, cls: Class<out CardWidget>, kind: String, title: String, w: Float, h: Float, vararg cfg: String) =
        Entry(id, cls, kind, title, Group.LEGACY, w, h, cfg.toList())

    val all: List<Entry> = listOf(
        core("favorites", FavoritesWidget::class.java, "favorites", "Favorites", 320f, 190f, heights = listOf(190f, 280f)),
        core("suggested", SuggestedWidget::class.java, "suggested", "Suggested", 320f, 190f),
        core("room", RoomWidget::class.java, "room", "Room", 320f, 220f, heights = listOf(150f, 220f)),
        core("energy", EnergyWidget::class.java, "energy", "Energy", 320f, 200f, heights = listOf(150f, 200f)),
        core("security", SecurityWidget::class.java, "security", "Security", 320f, 200f, heights = listOf(200f, 280f)),
        core("graph", GraphWidget::class.java, "graph", "Sensor graph", 320f, 170f),
        core("devices", DevicesWidget::class.java, "devices", "My devices", 320f, 190f, "lights", heights = listOf(190f, 280f)),
        core("weather", WeatherWidget::class.java, "weather", "Weather", 320f, 190f, "weather", heights = listOf(190f, 300f)),
        core("weather_s", WeatherSmallWidget::class.java, "weather", "Weather · small", 150f, 150f, "weather"),
        core("now", NowWidget::class.java, "now", "Now playing · bar", 320f, 64f, "media", heights = listOf(64f, 96f)),
        core("now_bar", NowBarWidget::class.java, "now", "Now playing · card", 320f, 180f, "media"),
        core("now_s", NowSmallWidget::class.java, "now", "Now playing · small", 150f, 150f, "media"),
        core("todo", TodoWidget::class.java, "todo", "To-do", 320f, 230f, "today"),
        core("scenes", QuickActionsWidget::class.java, "scenes", "Scenes", 320f, 120f, heights = listOf(120f, 200f)),
        core("light", BulbWidget::class.java, "light", "Light", 320f, 200f, "lights"),
        core("t_bulb", BulbTile::class.java, "tile", "Tile", 72f, 72f),
        core("people", PeopleWidget::class.java, "people", "Who's home", 320f, 150f),
        core("camera", CameraWidget::class.java, "camera", "Camera", 320f, 200f),
        core("vacuum", VacuumWidget::class.java, "vacuum", "Vacuum", 320f, 160f),
        core("covers", CoversWidget::class.java, "covers", "Blinds & garage", 320f, 190f),
        core("countdown", CountdownWidget::class.java, "countdown", "Timers", 320f, 150f),
        core("calendar", CalendarWidget::class.java, "calendar", "Calendar", 320f, 220f),
        core("battery", BatteryWidget::class.java, "battery", "Batteries & offline", 320f, 220f),
        core("bins", BinsWidget::class.java, "bins", "Bin day", 320f, 140f),
        core("ev", EvWidget::class.java, "ev", "Car charging", 320f, 130f),
        core("tvremote", TvRemoteWidget::class.java, "remote", "TV remote", 320f, 120f, "media", heights = listOf(120f, 330f)),
        core("sky", SkyWidget::class.java, "sky", "Sky", 320f, 120f, "weather"),
        core("body", BodyWidget::class.java, "body", "Body", 320f, 170f, "body"),
        core("body_s", BodySmallWidget::class.java, "body", "Body · small", 150f, 150f, "body"),

        extra("mac", MacWidget::class.java, "mac", "Mac apps & power", 320f, 190f, "mac"),
        extra("macscreen", MacScreenWidget::class.java, "mac", "Mac screen", 320f, 230f, "mac"),
        extra("claude", ClaudeWidget::class.java, "claude", "Claude Code", 320f, 330f, "mac", heights = listOf(200f, 360f)),
        extra("claude_s", ClaudeSmallWidget::class.java, "claude", "Claude · small", 150f, 150f, "mac"),
        extra("projects", ProjectsWidget::class.java, "claude", "Projects", 320f, 300f, "mac"),
        extra("gaming", GamingWidget::class.java, "gaming", "Gaming PC", 320f, 190f, "gaming"),
        extra("spend", SpendWidget::class.java, "money", "Spending", 320f, 420f, "money", "general"),
        extra("dues", DuesWidget::class.java, "money", "Coming up", 320f, 170f, "money", "general"),
        extra("budget", BudgetWidget::class.java, "money", "Budget", 320f, 170f, "money", "general"),
        extra("budget_s", BudgetSmallWidget::class.java, "money", "Budget · small", 150f, 150f, "money", "general"),
        extra("nutrition", NutritionWidget::class.java, "fuel", "Fuel", 320f, 230f, "fuel"),
        extra("screentime", ScreenTimeWidget::class.java, "screentime", "Screen time", 320f, 170f, "screentime"),
        extra("screentime_s", ScreenTimeSmallWidget::class.java, "screentime", "Screen time · small", 150f, 150f, "screentime"),
        extra("overhead", OverheadWidget::class.java, "overhead", "Overhead", 320f, 300f, "overhead"),
        extra("overhead_s", OverheadSmallWidget::class.java, "overhead", "Overhead · small", 150f, 150f, "overhead"),
        extra("t_claude", ClaudeTile::class.java, "claude", "Tile · Claude", 150f, 72f, "mac"),
        extra("t_pc", WakePcTile::class.java, "gaming", "Tile · Wake PC", 150f, 72f, "gaming"),
        extra("t_lock", LockMacTile::class.java, "mac", "Tile · Lock Mac", 72f, 72f, "mac"),
        extra("t_water", WaterTile::class.java, "fuel", "Tile · Water +250", 150f, 72f, "fuel"),
        extra("t_screenoff", ScreenOffTile::class.java, "mac", "Tile · Mac screen off", 150f, 72f, "mac"),

        legacy("tube", TubeWidget::class.java, "light", "Light B", 320f, 200f, "lights"),
        legacy("t_tube", TubeTile::class.java, "light", "Tile · Light B", 72f, 72f, "lights"),
        legacy("t_fan", FanTile::class.java, "fan", "Tile · Fan", 72f, 72f, "lights"),
        legacy("t_tv", TvTile::class.java, "remote", "Tile · TV", 72f, 72f, "media"),
        legacy("t_movie", MovieTile::class.java, "scenes", "Tile · Movie", 150f, 72f, "media"),
        legacy("t_alloff", AllOffTile::class.java, "scenes", "Tile · All off", 150f, 72f, "media"),
        legacy("t_music", MusicTile::class.java, "now", "Tile · Music", 150f, 72f, "media"),
    )

    private val byClass = all.associateBy { it.cls.name }

    fun of(cls: Class<*>): Entry? = byClass[cls.name]
    fun byClassName(name: String): Entry? = byClass[name]
    fun byId(id: String): Entry? = all.firstOrNull { it.id == id }

    val providers: List<Class<out CardWidget>> = all.map { it.cls }
    val core: List<Entry> get() = all.filter { it.group == Group.CORE }
    val extras: List<Entry> get() = all.filter { it.group == Group.EXTRAS }
    val legacy: List<Entry> get() = all.filter { it.group == Group.LEGACY }

    /** Extras kinds with a guide and a YAML package in assets/extras. */
    val guides: List<Pair<String, String>> = listOf("mac" to "Mac", "claude" to "Claude Code", "gaming" to "Gaming PC", "money" to "Money",
        "fuel" to "Fuel", "screentime" to "Screen time", "overhead" to "Overhead")
}
