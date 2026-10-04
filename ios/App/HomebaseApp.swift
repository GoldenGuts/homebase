import SwiftUI
import WidgetKit
import HomebaseCore

@main
struct HomebaseApp: App {
    @StateObject private var model = AppModel()

    init() {
        // CI's simulator smoke test and screenshots start straight in the demo home.
        if ProcessInfo.processInfo.arguments.contains("-HomebaseDemo") { Prefs.demo = true }
        #if DEBUG
        E2E.start()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onAppear { ProStore.shared.start() }
                .onOpenURL { url in if url.host == "pro" { model.showPro = true } }
                .sheet(isPresented: $model.showPro) { ProView() }
                .preferredColorScheme(.dark)
                .tint(Theme.cyan)
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var snap: Snapshot = HomeData.load()
    @Published var connected = Prefs.isConnected
    @Published var demo = Prefs.demo
    @Published var busy = false
    @Published var status = ""
    @Published var dashboardPlans: [WidgetSpec] = []
    /// Onboarding's "Setting up" step is on screen: keep it there even though we are connected now.
    @Published var settingUp = false
    /// The Homebase Pro sheet (from Settings, the Pro card, or a locked widget).
    @Published var showPro = false

    func reload() { snap = HomeData.load(); connected = Prefs.isConnected; demo = Prefs.demo }

    func refresh() async {
        busy = true
        snap = await HomeData.refresh(Needs.all, force: true)
        status = Prefs.lastError.map { "Last update failed: \($0)" } ?? ""
        busy = false
        HomeData.reloadWidgets()
    }

    /// After login: scan the home (registries, favorites, energy), then fetch.
    func connectedNow() async {
        Prefs.demo = false
        connected = true
        demo = false
        status = "Scanning your home…"
        do { try await HomeData.scan() } catch { status = "Connected. The scan failed (\(error.localizedDescription)); widgets still work." }
        await refresh()
    }

    func rescan() async {
        busy = true
        do { try await HomeData.scan(); status = "" } catch { status = "Scan failed: \(error.localizedDescription)" }
        await refresh()
    }

    func importDashboards() async {
        busy = true
        do { dashboardPlans = try await HomeData.dashboardPlans(snap.home) } catch { status = "Could not read the dashboards: \(error.localizedDescription)" }
        busy = false
    }

    func setDemo(_ on: Bool) {
        Prefs.demo = on
        if on { Prefs.onboarded = true }
        reload()
        HomeData.reloadWidgets()
    }

    func logout() {
        HomeData.logout()
        Prefs.demo = false
        reload()
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Group {
            if (model.connected || model.demo) && !model.settingUp { HomeView() } else { ConnectView() }
        }
        .background(Theme.bg.ignoresSafeArea())
    }
}
