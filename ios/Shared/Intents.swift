import AppIntents
import HomebaseCore

// MARK: - Entities for widget settings (searchable, with live state, no ids to type)

/// Entities from the cached snapshot matching [domains], most useful first, with "Area · state" details.
enum EntityCatalog {
    static func list(domains: Set<String>, numericOnly: Bool = false, matching q: String = "") -> [(id: String, name: String, detail: String)] {
        let snap = HomeData.load()
        let now = snap.now
        let query = q.trimmingCharacters(in: .whitespaces).lowercased()
        return snap.home.useful
            .filter { domains.isEmpty || domains.contains($0.domain) }
            .filter { !numericOnly || $0.number != nil }
            .map { e in (id: e.id, name: e.name, detail: [EntityPicker.shared.whereLine(home: snap.home, entityId: e.id), Rules.shared.stateLabel(e: e, nowMillis: now)].filter { !$0.isEmpty }.joined(separator: " · ")) }
            .filter { query.isEmpty || $0.name.lowercased().contains(query) || $0.id.contains(query) || $0.detail.lowercased().contains(query) }
    }

    static func byIds(_ ids: [String]) -> [(id: String, name: String, detail: String)] {
        let snap = HomeData.load()
        return ids.map { id in (id: id, name: snap.state(id)?.name ?? id, detail: snap.state(id).map { Rules.shared.stateLabel(e: $0, nowMillis: snap.now) } ?? "not found") }
    }

    static let controlDomains: Set<String> = ["light", "switch", "fan", "cover", "lock", "input_boolean", "scene", "script", "button", "input_button",
                                              "media_player", "climate", "humidifier", "vacuum", "valve", "siren", "water_heater", "lawn_mower", "sensor", "binary_sensor"]
    static let toggleDomains: Set<String> = ["light", "switch", "fan", "cover", "lock", "input_boolean", "valve", "siren", "humidifier"]
}

struct ControlEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Entity"
    static var defaultQuery = ControlEntityQuery()
    var id: String
    var name: String
    var detail: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)", subtitle: "\(detail)") }
}

struct ControlEntityQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [ControlEntity] { EntityCatalog.byIds(identifiers).map { ControlEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func entities(matching string: String) async throws -> [ControlEntity] { EntityCatalog.list(domains: EntityCatalog.controlDomains, matching: string).map { ControlEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func suggestedEntities() async throws -> [ControlEntity] { EntityCatalog.list(domains: EntityCatalog.controlDomains).map { ControlEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
}

/// Things that switch on and off (Control Center toggles).
struct ToggleEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Switchable entity"
    static var defaultQuery = ToggleEntityQuery()
    var id: String
    var name: String
    var detail: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)", subtitle: "\(detail)") }
}

struct ToggleEntityQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [ToggleEntity] { EntityCatalog.byIds(identifiers).map { ToggleEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func entities(matching string: String) async throws -> [ToggleEntity] { EntityCatalog.list(domains: EntityCatalog.toggleDomains, matching: string).map { ToggleEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func suggestedEntities() async throws -> [ToggleEntity] { EntityCatalog.list(domains: EntityCatalog.toggleDomains).map { ToggleEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
}

struct SensorEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Sensor"
    static var defaultQuery = SensorEntityQuery()
    var id: String
    var name: String
    var detail: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)", subtitle: "\(detail)") }
}

struct SensorEntityQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [SensorEntity] { EntityCatalog.byIds(identifiers).map { SensorEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func entities(matching string: String) async throws -> [SensorEntity] { EntityCatalog.list(domains: ["sensor", "number", "input_number"], numericOnly: true, matching: string).map { SensorEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func suggestedEntities() async throws -> [SensorEntity] { EntityCatalog.list(domains: ["sensor", "number", "input_number"], numericOnly: true).map { SensorEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
}

struct PlayerEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Media player"
    static var defaultQuery = PlayerEntityQuery()
    var id: String
    var name: String
    var detail: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)", subtitle: "\(detail)") }
}

struct PlayerEntityQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async throws -> [PlayerEntity] { EntityCatalog.byIds(identifiers).map { PlayerEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func entities(matching string: String) async throws -> [PlayerEntity] { EntityCatalog.list(domains: ["media_player"], matching: string).map { PlayerEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
    func suggestedEntities() async throws -> [PlayerEntity] { EntityCatalog.list(domains: ["media_player"]).map { PlayerEntity(id: $0.id, name: $0.name, detail: $0.detail) } }
}

/// A Home Assistant area (room).
struct AreaEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Room"
    static var defaultQuery = AreaEntityQuery()
    var id: String
    var name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct AreaEntityQuery: EntityStringQuery {
    private func all() -> [AreaEntity] { HomeData.load().home.registry.areas.map { AreaEntity(id: $0.id, name: $0.name) }.sorted { $0.name < $1.name } }
    func entities(for identifiers: [String]) async throws -> [AreaEntity] { all().filter { identifiers.contains($0.id) } }
    func entities(matching string: String) async throws -> [AreaEntity] { all().filter { string.isEmpty || $0.name.localizedCaseInsensitiveContains(string) } }
    func suggestedEntities() async throws -> [AreaEntity] { all() }
}

// MARK: - Widget settings

struct FavoritesConfig: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Favorites"
    static var description = IntentDescription("Leave empty to follow the favorites you star in Home Assistant.")
    @Parameter(title: "Entities") var entities: [ControlEntity]?
    init() {}
}

struct RoomConfig: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Room"
    static var description = IntentDescription("Empty = the room with the most to show.")
    @Parameter(title: "Room") var area: AreaEntity?
    init() {}
}

struct TileConfig: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Tile"
    static var description = IntentDescription("Empty = your first favorite.")
    @Parameter(title: "Entity") var entity: ControlEntity?
    init() {}
}

struct GraphConfig: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Sensor graph"
    static var description = IntentDescription("Empty = a room temperature.")
    @Parameter(title: "Sensor") var sensor: SensorEntity?
    init() {}
}

struct PlayerConfig: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Now playing"
    static var description = IntentDescription("Empty = whatever is playing.")
    @Parameter(title: "Player") var player: PlayerEntity?
    init() {}
}

// MARK: - Actions (interactive widgets)

/// What a tap on a tile does: toggle, open / close, lock, run a scene… Unlocking and opening a garage
/// need a second tap within a few seconds.
struct TapEntityIntent: AppIntent {
    static var title: LocalizedStringResource = "Tap a Home Assistant entity"
    static var isDiscoverable: Bool = false
    @Parameter(title: "Entity") var entityId: String

    init() {}
    init(_ entityId: String) { self.entityId = entityId }

    func perform() async throws -> some IntentResult {
        _ = try await HomeActions.tap(entityId)
        return .result()
    }
}

/// Thermostat − / +.
struct ClimateStepIntent: AppIntent {
    static var title: LocalizedStringResource = "Change the target temperature"
    static var isDiscoverable: Bool = false
    @Parameter(title: "Entity") var entityId: String
    @Parameter(title: "Target") var target: Double

    init() {}
    init(_ entityId: String, target: Double) { self.entityId = entityId; self.target = target }

    func perform() async throws -> some IntentResult {
        try await HomeActions.service("climate", "set_temperature", ["entity_id": entityId, "temperature": target])
        return .result()
    }
}

/// Media keys: play / pause, next, previous.
struct MediaIntent: AppIntent {
    static var title: LocalizedStringResource = "Media key"
    static var isDiscoverable: Bool = false
    @Parameter(title: "Entity") var entityId: String
    @Parameter(title: "Service") var service: String

    init() {}
    init(_ entityId: String, _ service: String) { self.entityId = entityId; self.service = service }

    func perform() async throws -> some IntentResult {
        try await HomeActions.service("media_player", service, ["entity_id": entityId])
        return .result()
    }
}

/// Tap on "updated N min ago": fetch now.
struct RefreshIntent: AppIntent {
    static var title: LocalizedStringResource = "Refresh Homebase widgets"
    static var isDiscoverable: Bool = false

    init() {}

    func perform() async throws -> some IntentResult {
        await HomeData.refresh(Needs.all, force: true)
        return .result()
    }
}
