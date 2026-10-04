import Foundation
import HomebaseCore
#if canImport(WidgetKit)
import WidgetKit
#endif

/// What a widget draws from: the home (states, registries, favorites, energy setup) and when it was fetched.
struct Snapshot {
    let home: Home
    let fetchedAt: Date?
    let demo: Bool

    func state(_ id: String?) -> EntityState? {
        guard let id else { return nil }
        return home.states[id]
    }

    var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Which extras a refresh should fetch beyond the states.
struct Needs {
    var weather: [String] = []
    var graph: [String] = []
    var energy = false
    var images: [String] = []

    static let all = Needs(weather: [], graph: [], energy: true)
}

/// Loads, refreshes and scans. The app and the widget extension share the cache in the App Group.
enum HomeData {
    private static let statesFile = "states.json"
    private static let homeFile = "home.json"

    static var offsetMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }
    static var nowMillis: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// The cached snapshot, or the demo home when there is none (or demo mode is on).
    static func load() -> Snapshot {
        if Prefs.demo || !Prefs.isConnected { return demo() }
        guard let data = HB.read(statesFile) else { return demo() }
        let states = EntityState.companion.parseList(text: String(decoding: data, as: UTF8.self))
        let scan = HB.read(homeFile).flatMap { Json.companion.parseOrNull(text: String(decoding: $0, as: UTF8.self)) }
        return Snapshot(home: Home.companion.fromJson(j: scan, states: states), fetchedAt: Prefs.lastFetch, demo: false)
    }

    static func demo() -> Snapshot {
        Snapshot(home: DemoHome.shared.home(nowMillis: nowMillis, offsetMinutes: offsetMinutes), fetchedAt: Date(), demo: true)
    }

    // MARK: - Refresh

    private static let keepDomains: Set<String> = ["light", "fan", "switch", "media_player", "input_boolean", "input_number", "input_select", "binary_sensor",
        "weather", "sun", "scene", "script", "camera", "zone", "climate", "cover", "lock", "alarm_control_panel", "vacuum", "person", "calendar",
        "timer", "lawn_mower", "valve", "humidifier", "water_heater", "siren", "button", "input_button", "number", "todo", "select", "update", "remote"]
    private static let keepSensorClasses: Set<String> = ["temperature", "humidity", "battery", "power", "energy", "timestamp", "date", "distance", "carbon_dioxide", "duration"]
    private static let stripDomains: Set<String> = ["update", "script", "scene", "remote", "automation", "button", "input_button"]

    /// Fetch the states, keep what widgets read, attach forecasts / history / energy, save. Falls back to the cache.
    /// Widgets of one extension refresh one after another, and a refresh within a minute of the last one is
    /// skipped when the cache already has what this caller needs (unless `force`: taps, pull to refresh).
    @discardableResult
    static func refresh(_ needs: Needs = Needs(), force: Bool = false) async -> Snapshot {
        if Prefs.demo || !Prefs.isConnected { return load() }
        await RefreshGate.shared.run {
            if !force, let last = Prefs.lastFetch, Date().timeIntervalSince(last) < 60, HomeData.satisfied(needs, HomeData.load()) { return }
            await HomeData.fetch(needs)
        }
        return load()
    }

    private static func satisfied(_ needs: Needs, _ snap: Snapshot) -> Bool {
        needs.graph.allSatisfy { snap.state($0)?.attr(key: Extra.shared.HISTORY) != nil } &&
            needs.weather.allSatisfy { snap.state($0)?.attr(key: Extra.shared.DAILY) != nil } &&
            (!needs.energy || snap.home.energy.todayStats.isEmpty || snap.state(Extra.shared.ENERGY_ID) != nil)
    }

    private static func fetch(_ needs: Needs) async {
        do {
            let raw = try await HAClient.shared.states()
            let cached = HB.read(homeFile).flatMap { Json.companion.parseOrNull(text: String(decoding: $0, as: UTF8.self)) }
            let cachedHome = Home.companion.fromJson(j: cached, states: [])
            let pinned = Set(cachedHome.favorites + cachedHome.suggested)
            // One entity at a time, so a big home never sits in memory as a whole (widget extensions get ~30 MB).
            var list: [[String: Any]] = []
            let isArray = JSONStream.elements(of: raw) { slice in
                autoreleasepool {
                    guard let o = try? JSONSerialization.jsonObject(with: slice) as? [String: Any],
                          let id = o["entity_id"] as? String else { return }
                    let domain = String(id.split(separator: ".").first ?? "")
                    var attrs = o["attributes"] as? [String: Any] ?? [:]
                    let keep = keepDomains.contains(domain) || pinned.contains(id) || needs.graph.contains(id) ||
                        (domain == "sensor" && keepSensorClasses.contains(attrs["device_class"] as? String ?? ""))
                    guard keep else { return }
                    var out = o
                    out.removeValue(forKey: "context")
                    out.removeValue(forKey: "last_reported")
                    if stripDomains.contains(domain) { attrs = ["friendly_name": attrs["friendly_name"] ?? "", "icon": attrs["icon"] ?? ""] }
                    attrs.removeValue(forKey: "entity_picture_local")
                    out["attributes"] = attrs
                    list.append(out)
                }
            }
            guard isArray else { throw HAError("Unexpected answer") }
            // forecasts: weather.get_forecasts works for every weather integration
            // without a configured one, the entity the Weather widget shows: the first by name (Home.useful's order)
            func sortName(_ o: [String: Any]) -> String {
                let id = o["entity_id"] as? String ?? ""
                // as EntityState.name: a blank friendly_name falls back to the object id
                let friendly = ((o["attributes"] as? [String: Any])?["friendly_name"] as? String)?.trimmingCharacters(in: .whitespaces)
                let name = (friendly?.isEmpty == false ? friendly : nil) ?? String(id.split(separator: ".").last ?? "").replacingOccurrences(of: "_", with: " ")
                return name.lowercased()
            }
            let weatherIds = needs.weather.isEmpty
                ? list.filter { ($0["entity_id"] as? String)?.hasPrefix("weather.") == true }.sorted { sortName($0) < sortName($1) }
                    .prefix(1).compactMap { $0["entity_id"] as? String }
                : needs.weather
            for wid in weatherIds {
                guard let i = list.firstIndex(where: { $0["entity_id"] as? String == wid }) else { continue }
                var attrs = list[i]["attributes"] as? [String: Any] ?? [:]
                for (type, key) in [("daily", Extra.shared.DAILY), ("hourly", Extra.shared.HOURLY)] {
                    if let r = try? await HAClient.shared.callServiceResponse("weather", "get_forecasts", ["entity_id": wid, "type": type]),
                       let f = (r[wid] as? [String: Any])?["forecast"] { attrs[key] = f }
                }
                list[i]["attributes"] = attrs
            }
            // 24 h history for graphed sensors
            if !needs.graph.isEmpty, let h = try? await HAClient.shared.history(needs.graph, since: Date().addingTimeInterval(-24 * 3600)) {
                let series = Series.companion.parseHistory(json: Json.companion.parseOrNull(text: String(decoding: h, as: UTF8.self)), unit: { _ in "" })
                for s in series {
                    if let i = list.firstIndex(where: { $0["entity_id"] as? String == s.entityId }),
                       let obj = try? JSONSerialization.jsonObject(with: Data(s.toJson().description.utf8)) {
                        var attrs = list[i]["attributes"] as? [String: Any] ?? [:]
                        attrs[Extra.shared.HISTORY] = obj
                        list[i]["attributes"] = attrs
                    }
                }
            }
            // today's energy (WebSocket statistics), at most every 10 minutes
            if needs.energy {
                let setup = cachedHome.energy
                var energy: [String: Any]? = nil
                if let last = Prefs.lastEnergy, Date().timeIntervalSince(last) < 600,
                   let old = previousEnergy() { energy = old }
                else if !setup.todayStats.isEmpty {
                    energy = try? await energyToday(setup)
                    if energy != nil { Prefs.lastEnergy = Date() }
                }
                if let energy { list.append(["entity_id": Extra.shared.ENERGY_ID, "state": "ok", "attributes": energy]) }
            }
            let data = try JSONSerialization.data(withJSONObject: list)
            HB.write(statesFile, data)
            Prefs.lastFetch = Date()
            Prefs.lastError = nil
        } catch {
            Prefs.lastError = error.localizedDescription
        }
    }

    private static func previousEnergy() -> [String: Any]? {
        guard let data = HB.read(statesFile) else { return nil }
        var found: [String: Any]?
        _ = JSONStream.elements(of: data) { slice in
            guard found == nil, let o = try? JSONSerialization.jsonObject(with: slice) as? [String: Any],
                  o["entity_id"] as? String == Extra.shared.ENERGY_ID else { return }
            found = o["attributes"] as? [String: Any]
        }
        return found
    }

    private static func energyToday(_ setup: EnergySetup) async throws -> [String: Any] {
        try await HAClient.shared.withSocket { ws in
            var change: [String: Double] = [:]
            for id in setup.todayStats {
                let r = await ws.tryCall("recorder/statistic_during_period", ["statistic_id": id, "calendar": ["period": "day"], "types": ["change"]])
                if let v = Ws.shared.changeOf(result: r)?.doubleValue { change[id] = v }
            }
            func sum(_ ids: [String]) -> Double? { let v = ids.compactMap { change[$0] }; return v.isEmpty ? nil : v.reduce(0, +) }
            var out: [String: Any] = ["friendly_name": "Energy today"]
            if let v = sum(setup.gridImport) { out["grid_in"] = v }
            if let v = sum(setup.gridExport) { out["grid_out"] = v }
            if let v = sum(setup.solarEnergy) { out["solar"] = v }
            if let v = sum(setup.batteryIn) { out["battery_in"] = v }
            if let v = sum(setup.batteryOut) { out["battery_out"] = v }
            return out
        }
    }

    // MARK: - Scan (auto-setup)

    /// Registries, favorites, common_control suggestions and energy prefs over the WebSocket API.
    static func scan() async throws {
        let home: Home = try await HAClient.shared.withSocket { ws in
            let states = await ws.tryCall("get_states")
            let areas = await ws.tryCall("config/area_registry/list")
            let floors = await ws.tryCall("config/floor_registry/list")
            let labels = await ws.tryCall("config/label_registry/list")
            let devices = await ws.tryCall("config/device_registry/list")
            let entities = await ws.tryCall("config/entity_registry/list_for_display")
            let favorites = await ws.tryCall("frontend/get_system_data", ["key": "home"])
            let suggested = await ws.tryCall("usage_prediction/common_control")
            let energy = await ws.tryCall("energy/get_prefs")
            let config = await ws.tryCall("get_config")
            let list = states.map { EntityState.companion.parseList(json: $0) } ?? []
            var byId: [String: EntityState] = [:]
            for s in list { byId[s.id] = s }
            return Home(states: byId,
                        registry: Registry.companion.parse(areas: areas, floors: floors, devices: devices, entities: entities, labels: labels),
                        favorites: Ws.shared.favoritesOf(result: favorites), suggested: Ws.shared.suggestedOf(result: suggested),
                        energy: EnergySetup.companion.parse(prefs: energy), locationName: config?.str(key: "location_name") ?? "",
                        scannedAt: nowMillis)
        }
        HB.write(homeFile, Data(home.toJson().description.utf8))
        Prefs.lastScan = Date()
        if !home.locationName.isEmpty { Prefs.serverName = home.locationName }
    }

    /// Plans for dashboards' cards (Lovelace import).
    static func dashboardPlans(_ home: Home) async throws -> [WidgetSpec] {
        try await HAClient.shared.withSocket { ws in
            let dashboards = Lovelace.shared.dashboards(result: await ws.tryCall("lovelace/dashboards/list"))
            var out: [WidgetSpec] = []
            for d in dashboards {
                var fields: [String: Any] = [:]
                if let p = d.urlPath { fields["url_path"] = p }
                out += Lovelace.shared.plans(config: await ws.tryCall("lovelace/config", fields), home: home, dashboardTitle: d.title)
            }
            return out
        }
    }

    static func logout() {
        Auth.clear()
        HB.remove(statesFile)
        HB.remove(homeFile)
        Prefs.lastFetch = nil
        Prefs.lastScan = nil
        reloadWidgets()
    }

    static func reloadWidgets() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        if #available(iOS 18.0, *) { ControlCenter.shared.reloadAllControls() }
        #endif
    }
}

/// Taps from widgets and the app: the shared rules pick the service (toggle, open / close, lock, run…).
enum HomeActions {
    static let armWindow: TimeInterval = 6

    /// Returns false when the tap only armed a sensitive action (unlock, open the garage): tap again to confirm.
    @discardableResult
    static func tap(_ entityId: String) async throws -> Bool {
        let snap = HomeData.load()
        guard let e = snap.state(entityId), let call = Rules.shared.tapAction(e: e) else { return true }
        if snap.demo { return true }
        if call.confirm {
            let key = "\(entityId):\(call.service)"
            if let at = Prefs.armedAt(key), Date().timeIntervalSince(at) < armWindow { Prefs.arm(key, nil) }
            else { Prefs.arm(key, Date()); return false }
        }
        let data = (try? JSONSerialization.jsonObject(with: Data(call.data.description.utf8)) as? [String: Any]) ?? ["entity_id": entityId]
        try await HAClient.shared.callService(call.domain, call.service, data)
        try? await Task.sleep(nanoseconds: 700_000_000)
        await HomeData.refresh(force: true)
        return true
    }

    static func isArmed(_ entityId: String, _ service: String) -> Bool {
        guard let at = Prefs.armedAt("\(entityId):\(service)") else { return false }
        return Date().timeIntervalSince(at) < armWindow
    }

    static func service(_ domain: String, _ service: String, _ data: [String: Any]) async throws {
        if Prefs.demo || !Prefs.isConnected { return }
        try await HAClient.shared.callService(domain, service, data)
        try? await Task.sleep(nanoseconds: 700_000_000)
        await HomeData.refresh(force: true)
    }
}

/// Runs refreshes one after another within a process (each widget kind asks for its own timeline).
actor RefreshGate {
    static let shared = RefreshGate()
    private var tail: Task<Void, Never>?

    func run(_ work: @escaping @Sendable () async -> Void) async {
        let previous = tail
        let task = Task { await previous?.value; await work() }
        tail = task
        await task.value
    }
}

/// Walks the elements of a top-level JSON array without parsing the whole document.
enum JSONStream {
    /// Calls `body` with the bytes of each element; false when the data is not an array.
    static func elements(of data: Data, _ body: (Data) -> Void) -> Bool {
        data.withUnsafeBytes { (b: UnsafeRawBufferPointer) -> Bool in
            var i = 0
            while i < b.count, b[i] == 0x20 || b[i] == 0x0A || b[i] == 0x0D || b[i] == 0x09 { i += 1 }
            guard i < b.count, b[i] == UInt8(ascii: "[") else { return false }
            var depth = 0, inString = false, escaped = false, start = -1
            while i < b.count {
                let c = b[i]
                if inString {
                    if escaped { escaped = false } else if c == UInt8(ascii: "\\") { escaped = true } else if c == UInt8(ascii: "\"") { inString = false }
                } else {
                    switch c {
                    case UInt8(ascii: "\""): inString = true
                    case UInt8(ascii: "{"), UInt8(ascii: "["):
                        depth += 1
                        if depth == 2 { start = i }
                    case UInt8(ascii: "}"), UInt8(ascii: "]"):
                        if depth == 2, start >= 0 {
                            body(data.subdata(in: data.startIndex + start ..< data.startIndex + i + 1))
                            start = -1
                        }
                        depth -= 1
                    default: break
                    }
                }
                i += 1
            }
            return true
        }
    }
}
