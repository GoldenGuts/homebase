import WidgetKit
import SwiftUI
import AppIntents
import HomebaseCore

// MARK: - Timeline plumbing

struct HomeEntry: TimelineEntry {
    let date: Date
    let snap: Snapshot
}

struct ConfigEntry<C>: TimelineEntry {
    let date: Date
    let snap: Snapshot
    let config: C
}

/// iOS gives widgets a refresh budget of roughly one update every 15–60 minutes; a tap on any button
/// (or on "updated N min ago") reloads at once.
private let refreshPolicy: () -> TimelineReloadPolicy = { .after(Date().addingTimeInterval(15 * 60)) }

struct StaticProvider: TimelineProvider {
    let needs: Needs

    func placeholder(in context: Context) -> HomeEntry { HomeEntry(date: Date(), snap: HomeData.demo()) }

    func getSnapshot(in context: Context, completion: @escaping (HomeEntry) -> Void) {
        completion(HomeEntry(date: Date(), snap: context.isPreview ? HomeData.demo() : HomeData.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HomeEntry>) -> Void) {
        Task {
            let snap = await HomeData.refresh(needs)
            completion(Timeline(entries: [HomeEntry(date: Date(), snap: snap)], policy: refreshPolicy()))
        }
    }
}

struct IntentProvider<C: WidgetConfigurationIntent>: AppIntentTimelineProvider {
    typealias Entry = ConfigEntry<C>
    typealias Intent = C
    let needs: (C, Snapshot) -> Needs

    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snap: HomeData.demo(), config: C()) }

    func snapshot(for configuration: C, in context: Context) async -> Entry {
        Entry(date: Date(), snap: context.isPreview ? HomeData.demo() : HomeData.load(), config: configuration)
    }

    func timeline(for configuration: C, in context: Context) async -> Timeline<Entry> {
        let snap = await HomeData.refresh(needs(configuration, HomeData.load()))
        return Timeline(entries: [Entry(date: Date(), snap: snap, config: configuration)], policy: refreshPolicy())
    }
}

/// Hands the widget family to the shared views.
struct Fam<Content: View>: View {
    @Environment(\.widgetFamily) private var family
    private let content: (WidgetFamily) -> Content
    init(@ViewBuilder content: @escaping (WidgetFamily) -> Content) { self.content = content }
    var body: some View { content(family) }
}

extension View {
    func card() -> some View { containerBackground(for: .widget) { Theme.card } }
}

// MARK: - Widgets

struct DashboardWidget: Widget {
    static var families: [WidgetFamily] {
        var f: [WidgetFamily] = [.systemLarge, .systemExtraLarge]
        #if HOMEBASE_IOS27
        f.append(.systemExtraLargePortrait)
        #endif
        return f
    }

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dashboard", provider: StaticProvider(needs: Needs.all)) { entry in
            Fam { f in Gated(kind: "dashboard", title: "Home") { DashboardView(snap: entry.snap, family: f) } }.card()
        }
        .configurationDisplayName("Home")
        .description("Your whole home on one page: favorites, rooms, security and energy.")
        .supportedFamilies(Self.families)
    }
}

struct FavoritesWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "favorites", intent: FavoritesConfig.self, provider: IntentProvider<FavoritesConfig>(needs: { _, _ in Needs() })) { entry in
            Fam { f in
                let own = entry.config.entities?.map(\.id) ?? []
                let ids = own.isEmpty ? (entry.snap.home.favorites.isEmpty ? Planner.shared.fillTaps(home: entry.snap.home, n: 12, exclude: Set<String>()) : entry.snap.home.favorites) : own
                FavoritesView(snap: entry.snap, ids: ids, title: "Favorites", family: f)
            }.card()
        }
        .configurationDisplayName("Favorites")
        .description("Your Home Assistant favorites: state and one-tap control.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct SuggestedWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "suggested", provider: StaticProvider(needs: Needs())) { entry in
            Fam { f in Gated(kind: "suggested", title: "Suggested") { FavoritesView(snap: entry.snap, ids: entry.snap.home.suggested.filter { entry.snap.state($0) != nil }, title: "Suggested", family: f) } }.card()
        }
        .configurationDisplayName("Suggested")
        .description("What you usually control at this time of day, learned by Home Assistant.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct RoomWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "room", intent: RoomConfig.self, provider: IntentProvider<RoomConfig>(needs: { _, _ in Needs() })) { entry in
            Fam { f in
                Gated(kind: "room", title: "Room") {
                    let rooms = Planner.shared.rooms(home: entry.snap.home)
                    let spec = entry.config.area.flatMap { a in rooms.first { $0.option(key: "area") == a.id } } ?? rooms.first
                    RoomView(snap: entry.snap, spec: spec, family: f)
                }
            }.card()
        }
        .configurationDisplayName("Room")
        .description("Temperature, humidity, the thermostat and the devices of one room.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct EnergyWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "energy", provider: StaticProvider(needs: Needs(energy: true))) { entry in
            Fam { f in Gated(kind: "energy", title: "Energy") { EnergyView(snap: entry.snap, family: f) } }.card()
        }
        .configurationDisplayName("Energy")
        .description("Live power, solar and battery, and today's kWh from your Energy dashboard.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct SecurityWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "security", provider: StaticProvider(needs: Needs())) { entry in
            Fam { f in Gated(kind: "security", title: "Security") { SecurityView(snap: entry.snap, family: f) } }.card()
        }
        .configurationDisplayName("Security")
        .description("Doors, windows, garage, locks and the alarm at a glance.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct WeatherWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "weather", provider: StaticProvider(needs: Needs())) { entry in
            Fam { f in Gated(kind: "weather", title: "Weather") { WeatherView(snap: entry.snap, family: f) } }.card()
        }
        .configurationDisplayName("Weather")
        .description("Now, hi/lo and the next days, from any weather integration.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "now", intent: PlayerConfig.self, provider: IntentProvider<PlayerConfig>(needs: { _, _ in Needs() })) { entry in
            Fam { f in Gated(kind: "now", title: "Now playing") { NowPlayingView(snap: entry.snap, playerId: entry.config.player?.id, family: f) } }.card()
        }
        .configurationDisplayName("Now playing")
        .description("Title, artist and play / pause of whatever plays.")
        .supportedFamilies([.systemMedium])
    }
}

struct GraphWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "graph", intent: GraphConfig.self, provider: IntentProvider<GraphConfig>(needs: { c, snap in
            Needs(graph: [c.sensor?.id ?? Planner.shared.graphCandidate(home: snap.home, taken: Set<String>())?.id].compactMap { $0 })
        })) { entry in
            Fam { f in Gated(kind: "graph", title: "Sensor graph") { GraphView(snap: entry.snap, sensorId: entry.config.sensor?.id, family: f) } }.card()
        }
        .configurationDisplayName("Sensor graph")
        .description("The last 24 hours of one sensor.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct TileWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "tile", intent: TileConfig.self, provider: IntentProvider<TileConfig>(needs: { _, _ in Needs() })) { entry in
            Fam { f in
                let id = entry.config.entity?.id ?? Planner.shared.fillTaps(home: entry.snap.home, n: 1, exclude: Set<String>()).first
                if f == .systemSmall {
                    TileFace(snap: entry.snap, id: id ?? "", e: entry.snap.state(id))
                        .overlay { Button(intent: TapEntityIntent(id ?? "")) { Color.clear.contentShape(Rectangle()) }.buttonStyle(.plain) }
                } else {
                    AccessoryView(snap: entry.snap, id: id, family: f)
                }
            }
            .containerBackground(for: .widget) { Theme.card }
        }
        .configurationDisplayName("Tile")
        .description("One entity. Tap = toggle, open, lock or run. Also on the Lock Screen.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}
