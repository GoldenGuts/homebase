import WidgetKit
import SwiftUI
import AppIntents
import HomebaseCore

/// Control Center / Lock Screen / Action button control (iOS 18): switch one Home Assistant entity.
@available(iOS 18.0, *)
struct ToggleControlConfig: ControlConfigurationIntent {
    static var title: LocalizedStringResource = "Home Assistant toggle"
    @Parameter(title: "Entity") var entity: ToggleEntity?
    init() {}
}

@available(iOS 18.0, *)
struct SetEntityIntent: SetValueIntent {
    static var title: LocalizedStringResource = "Switch a Home Assistant entity"
    static var isDiscoverable: Bool = false
    /// Controls work from the Lock Screen; unlocking a door must not.
    static var authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Entity") var entityId: String
    @Parameter(title: "On") var value: Bool

    init() {}
    init(_ entityId: String) { self.entityId = entityId }

    func perform() async throws -> some IntentResult {
        let domain = entityId.split(separator: ".").first.map(String.init) ?? "homeassistant"
        let call: (String, String) = {
            switch domain {
            case "cover": return ("cover", value ? "open_cover" : "close_cover")
            case "lock": return ("lock", value ? "unlock" : "lock")
            case "valve": return ("valve", value ? "open_valve" : "close_valve")
            default: return (domain, value ? "turn_on" : "turn_off")
            }
        }()
        try await HomeActions.service(call.0, call.1, ["entity_id": entityId])
        return .result()
    }
}

@available(iOS 18.0, *)
struct EntityToggleControl: ControlWidget {
    struct Value {
        let id: String
        let name: String
        let isOn: Bool
        let symbol: String
    }

    struct Provider: AppIntentControlValueProvider {
        func previewValue(configuration: ToggleControlConfig) -> Value {
            Value(id: configuration.entity?.id ?? "", name: configuration.entity?.name ?? "Light", isOn: true, symbol: "lightbulb.fill")
        }

        func currentValue(configuration: ToggleControlConfig) async throws -> Value {
            guard let id = configuration.entity?.id else { return Value(id: "", name: "Choose an entity", isOn: false, symbol: "house.fill") }
            var snap = HomeData.load()
            if !snap.demo, let f = snap.fetchedAt, Date().timeIntervalSince(f) > 120 { snap = await HomeData.refresh() }
            guard let e = snap.state(id) else { return Value(id: id, name: configuration.entity?.name ?? id, isOn: false, symbol: "questionmark.circle") }
            return Value(id: id, name: e.name, isOn: Rules.shared.isActive(e: e), symbol: Theme.symbol(for: e))
        }
    }

    var body: some ControlWidgetConfiguration {
        AppIntentControlConfiguration(kind: "in.weenja.homebase.toggle", provider: Provider()) { value in
            ControlWidgetToggle(value.name, isOn: value.isOn, action: SetEntityIntent(value.id)) { on in
                Label(on ? "On" : "Off", systemImage: value.symbol)
            }
        }
        .displayName("Home Assistant")
        .description("Switch a light, plug, fan, cover or lock.")
    }
}
