import SwiftUI
import WidgetKit
import HomebaseCore

/// The app's home: connection status, "For your home" (widgets auto-setup built from the scan, with
/// live previews), how to add them, dashboard import and settings.
struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @State private var confirmLogout = false
    @State private var showPrivacy = false
    @ObservedObject private var pro = ProStore.shared

    /// Kinds the iOS widgets implement.
    private static let iosKinds: Set<String> = ["favorites", "suggested", "room", "energy", "security", "weather", "now", "graph"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    statusCard
                    Block(title: "For your home") {
                        Text("Widgets built from your Home Assistant. To add one: touch and hold the Home Screen, tap Edit → Add Widget → Homebase, and pick it. Touch and hold a widget → Edit Widget to choose entities or a room.")
                            .font(.footnote).foregroundStyle(Theme.muted)
                        PlanCard(title: "Home", reason: "Your whole home on one page", kind: "dashboard", spec: nil, snap: model.snap)
                        ForEach(Array(plans.enumerated()), id: \.offset) { _, p in
                            PlanCard(title: p.title, reason: p.reason, kind: p.kind, spec: p, snap: model.snap)
                        }
                    }
                    if !model.demo {
                        Block(title: "From your dashboards") {
                            Text("Turns the cards of your Home Assistant dashboards into widget suggestions.").font(.footnote).foregroundStyle(Theme.muted)
                            Button { Task { await model.importDashboards() } } label: { Secondary("Import from my dashboards") }
                            ForEach(Array(model.dashboardPlans.filter { Self.iosKinds.contains($0.kind) }.prefix(12).enumerated()), id: \.offset) { _, p in
                                PlanCard(title: p.title, reason: p.reason, kind: p.kind, spec: p, snap: model.snap)
                            }
                        }
                    }
                    Block(title: "Lock Screen and Control Center") {
                        Text("The Tile widget also fits the Lock Screen. On iOS 18, add the Home Assistant control in Control Center (or on the Action button) to switch a light, plug, fan, cover or lock.")
                            .font(.footnote).foregroundStyle(Theme.muted)
                    }
                    if model.connected && !pro.unlocked {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Favorites, Tile and Weather (small) are free").font(.headline).foregroundStyle(Theme.ink)
                            Text("Unlock every widget and size with one purchase: rooms, energy, security, the Home dashboard and the rest.")
                                .font(.footnote).foregroundStyle(Theme.muted)
                            Button { model.showPro = true } label: { Primary("Homebase Pro") }.padding(.top, 6)
                        }
                        .padding(16).background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
                    }
                    settings
                    FeedbackCard()
                    SupportCard()
                }
                .padding(16)
            }
            .refreshable { await model.refresh() }
            .navigationTitle("Homebase")
            .background(Theme.bg)
            .task { if !model.demo { await model.refresh() } }
            .alert("Log out?", isPresented: $confirmLogout) {
                Button("Log out", role: .destructive) { model.logout() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("Your widgets show the demo home until you log in again.") }
            .sheet(isPresented: $showPrivacy) { PrivacyView() }
        }
    }

    private var plans: [WidgetSpec] { Planner.shared.plan(home: model.snap.home).filter { Self.iosKinds.contains($0.kind) } }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.demo ? "Demo home" : (Prefs.serverName.isEmpty ? Prefs.baseURL : Prefs.serverName)).font(.title3.weight(.bold)).foregroundStyle(Theme.ink)
            Text(model.demo ? "Made-up data. Connect to see your own home." :
                    "\(model.snap.home.states.count) entities · updated \(ago(model.snap.fetchedAt))" + (Prefs.lastScan.map { " · scanned \(ago($0))" } ?? ""))
                .font(.footnote).foregroundStyle(Theme.muted)
            if !model.status.isEmpty { Text(model.status).font(.footnote.weight(.semibold)).foregroundStyle(Theme.pink) }
            HStack {
                if model.demo {
                    Button { model.setDemo(false) } label: { Primary("Connect to Home Assistant") }
                } else {
                    Button { Task { await model.refresh() } } label: { Secondary(model.busy ? "Refreshing…" : "Refresh") }
                    Button { Task { await model.rescan() } } label: { Secondary("Scan again") }
                }
            }
            .padding(.top, 6)
        }
        .padding(16)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }

    private var settings: some View {
        Block(title: "Settings") {
            Toggle("Demo home", isOn: Binding(get: { model.demo }, set: { model.setDemo($0) }))
                .padding(14).background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
            Button { model.showPro = true } label: { Secondary(pro.unlocked ? "Homebase Pro ✓" : "Unlock every widget") }
            Button { showPrivacy = true } label: { Secondary("Privacy") }
            if model.connected { Button { confirmLogout = true } label: { Secondary("Log out") } }
            Text("Homebase \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · no account, no analytics, no ads.")
                .font(.caption).foregroundStyle(Theme.dim)
        }
    }
}

/// One suggestion: title, why, and the real widget view at its size.
struct PlanCard: View {
    let title: String
    let reason: String
    let kind: String
    let spec: WidgetSpec?
    let snap: Snapshot

    var body: some View {
        let family = Self.family(kind)
        VStack(alignment: .leading, spacing: 8) {
            Text(title.isEmpty ? kind.capitalized : title).font(.headline).foregroundStyle(Theme.ink)
            if !reason.isEmpty { Text(reason).font(.caption).foregroundStyle(Theme.muted) }
            WidgetPreview(kind: kind, spec: spec, snap: snap, family: family)
                .frame(width: Self.size(family).width, height: Self.size(family).height)
                .background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Theme.border, lineWidth: 1))
                .frame(maxWidth: .infinity)
            if !Pro.allowed(kind: kind, family: family) {
                Label("Homebase Pro", systemImage: "lock.fill").font(.caption.weight(.bold)).foregroundStyle(Theme.cyan)
            }
            Text("Add: Home Screen → Edit → Add Widget → Homebase → \(Self.widgetName(kind))").font(.caption2).foregroundStyle(Theme.dim)
        }
        .padding(14)
        .background(Theme.surface.opacity(0.5), in: RoundedRectangle(cornerRadius: 20))
    }

    static func family(_ kind: String) -> WidgetFamily {
        switch kind {
        case "dashboard": return .systemLarge
        case "weather", "energy": return .systemMedium
        case "favorites", "security", "room": return .systemLarge
        default: return .systemMedium
        }
    }

    static func size(_ f: WidgetFamily) -> CGSize {
        switch f {
        case .systemSmall: return CGSize(width: 170, height: 170)
        case .systemLarge: return CGSize(width: 338, height: 354)
        default: return CGSize(width: 338, height: 158)
        }
    }

    static func widgetName(_ kind: String) -> String {
        ["dashboard": "Home", "favorites": "Favorites", "suggested": "Suggested", "room": "Room", "energy": "Energy", "security": "Security",
         "weather": "Weather", "now": "Now playing", "graph": "Sensor graph"][kind] ?? kind.capitalized
    }
}

/// The widget views as the app shows them (same code as the widgets).
struct WidgetPreview: View {
    let kind: String
    let spec: WidgetSpec?
    let snap: Snapshot
    let family: WidgetFamily

    var body: some View {
        Group {
            switch kind {
            case "dashboard": DashboardView(snap: snap, family: family)
            case "favorites":
                let own = spec?.list(slot: "items") ?? []
                FavoritesView(snap: snap, ids: own.isEmpty ? snap.home.favorites : own, title: spec?.title ?? "Favorites", family: family)
            case "suggested": FavoritesView(snap: snap, ids: snap.home.suggested, title: "Suggested", family: family)
            case "room": RoomView(snap: snap, spec: spec, family: family)
            case "energy": EnergyView(snap: snap, family: family)
            case "security": SecurityView(snap: snap, family: family)
            case "weather": WeatherView(snap: snap, family: family)
            case "now": NowPlayingView(snap: snap, playerId: spec?.first(slot: "players"), family: family)
            case "graph": GraphView(snap: snap, sensorId: spec?.first(slot: "sensor"), family: family)
            default: EmptyNote(icon: "square.grid.2x2", title: kind, hint: "")
            }
        }
        .padding(16)
    }
}

struct PrivacyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Privacy").font(.largeTitle.weight(.bold))
                Text("Homebase talks to your own Home Assistant only. There is no account, no analytics, no crash reporting and no third-party service.")
                Text("Your Home Assistant address and settings stay on this device in the app's App Group; the login token is in the Keychain. The widgets read the same data.")
                Text("Entity states, forecasts and energy totals are cached on the device so widgets can draw without network.")
                Text("Finding Home Assistant uses Bonjour on your local network only. Logging out deletes the tokens and the cache; deleting the app deletes everything.")
            }
            .padding(20)
        }
        .foregroundStyle(Theme.ink)
        .background(Theme.bg)
        .presentationDetents([.medium, .large])
    }
}
