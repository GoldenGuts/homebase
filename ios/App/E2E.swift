#if DEBUG
import Foundation
import HomebaseCore

/// Debug builds only: CI's end-to-end run against a real Home Assistant, started with the launch
/// arguments `-HomebaseE2E <address> <OAuth code>`. Logs in, scans, fetches, plans, toggles a light and
/// writes Documents/e2e.json.
enum E2E {
    static func start() {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-HomebaseE2E"), args.count > i + 2 else { return }
        let base = args[i + 1], code = args[i + 2]
        Task.detached { await E2E.run(base: base, code: code) }
    }

    static func run(base: String, code: String) async {
        var report: [String: Any] = [:]
        var order: [String] = []
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("e2e.json")
        let partial = url.deletingLastPathComponent().appendingPathComponent("e2e-partial.json")
        func save(_ to: URL) {
            report["order"] = order
            if let data = try? JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]) {
                try? data.write(to: to, options: .atomic)
            }
        }
        // each step is recorded before it runs, so a hang shows where it stopped
        func step(_ name: String, _ body: () async throws -> Any) async {
            order.append(name)
            report[name] = "running"
            save(partial)
            do { report[name] = try await body() } catch { report[name] = "FAIL: \(error.localizedDescription)" }
            save(partial)
        }
        Prefs.demo = false
        Prefs.baseURL = base
        await step("login") {
            try await HAClient.shared.exchange(code: code, base: base)
            guard Auth.load() != nil else { throw HAError("token not stored") }
            return "oauth"
        }
        await step("scan") { try await HomeData.scan(); return "ok" }
        await step("fetch") {
            let snap = await HomeData.refresh(Needs.all, force: true)
            if let e = Prefs.lastError { throw HAError(e) }
            return snap.home.states.count
        }
        let snap = HomeData.load()
        // the scan as the app sees it (load() shows the demo home until the first fetch has been saved)
        await step("home") {
            let h = snap.home
            if snap.demo { throw HAError("still the demo home") }
            return ["location": h.locationName, "areas": h.registry.areas.count, "floors": h.registry.floors.count,
                    "devices": h.registry.devices.count, "favorites": h.favorites.count, "suggested": h.suggested.count,
                    "energy_stats": h.energy.todayStats.count]
        }
        await step("plans") { Planner.shared.plan(home: snap.home).map { "\($0.kind): \($0.title) (\($0.reason))" } }
        await step("forecast") {
            // the entity the Weather widget shows (WeatherView: the first weather entity of Home.useful)
            guard let w = snap.home.ofDomain(domain: "weather").first else { return "no weather" }
            let days = Weather.shared.forecasts(e: w, daily: true).count
            return "\(w.id): " + (days == 0 ? "FAIL: no forecast" : "\(days) days")
        }
        await step("history") {
            guard let id = snap.home.states.values.first(where: { $0.attrStringOr(key: "device_class", def: "") == "temperature" })?.id else { return "no sensor" }
            let s = await HomeData.refresh(Needs(graph: [id]), force: true)
            return "\(id): " + (s.state(id)?.attr(key: Extra.shared.HISTORY) == nil ? "FAIL: no history" : "history attached")
        }
        await step("toggle") {
            guard let light = snap.home.states.keys.sorted().first(where: { $0.hasPrefix("light.") }) else { return "no light" }
            let before = snap.state(light)?.state ?? ""
            try await HomeActions.tap(light)
            let after = HomeData.load().state(light)?.state ?? ""
            return "\(light): \(before) → \(after)" + (before == after ? " (FAIL: unchanged)" : "")
        }
        await step("dashboards") { try await HomeData.dashboardPlans(snap.home).map { "\($0.kind): \($0.title)" } }
        save(url)
    }
}
#endif
