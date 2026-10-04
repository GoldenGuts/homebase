import SwiftUI
import HomebaseCore

/// The widgets' one theme ("neon-candy"): dark cards, candy gradients for things that are on.
enum Theme {
    static let bg = Color(hex: 0x0d0f14)
    static let card = Color(hex: 0x171a22)
    static let surface = Color(hex: 0x1d2130)
    static let border = Color(hex: 0x242835)
    static let ink = Color(hex: 0xf3f4f8)
    static let muted = Color(hex: 0x8a90a3)
    static let dim = Color(hex: 0x5a6075)
    static let track = Color(hex: 0x262a36)
    static let orange = Color(hex: 0xff9f2e)
    static let pink = Color(hex: 0xff4fa3)
    static let lime = Color(hex: 0xb5e21c)
    static let cyan = Color(hex: 0x3fe3ff)
    static let purple = Color(hex: 0x7b61ff)

    static func color(_ a: Accent) -> Color {
        switch a {
        case .orange: return orange
        case .cyan: return cyan
        case .pink: return pink
        case .lime: return lime
        case .purple: return purple
        default: return muted
        }
    }

    static func gradient(_ a: Accent) -> LinearGradient {
        let pair: (UInt32, UInt32) = {
            switch a {
            case .orange: return (0xffb84d, 0xff8a1e)
            case .cyan: return (0x4fd7ff, 0x1fa8ff)
            case .pink: return (0xff5c8a, 0xff2d6f)
            case .lime: return (0xc6f04a, 0x8fc31f)
            case .purple: return (0x9d7bff, 0x6a3dff)
            default: return (0x2a2f3e, 0x1d2130)
            }
        }()
        return LinearGradient(colors: [Color(hex: pair.0), Color(hex: pair.1)], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    /// Ink on top of [gradient].
    static func inkOn(_ a: Accent) -> Color {
        switch a {
        case .orange: return Color(hex: 0x1b1206)
        case .cyan: return Color(hex: 0x06131b)
        case .lime: return Color(hex: 0x12200a)
        case .pink, .purple: return .white
        default: return ink
        }
    }

    /// SF Symbol for a Material Design Icons name from the shared rules.
    static func symbol(_ mdi: String) -> String {
        let name = mdi.hasPrefix("mdi:") ? String(mdi.dropFirst(4)) : mdi
        if let s = symbols[name] { return s }
        if name.hasPrefix("weather-") { return "cloud.sun.fill" }
        if name.hasPrefix("battery") { return "battery.50" }
        return "circle.fill"
    }

    static func symbol(for e: EntityState) -> String { symbol(Rules.shared.icon(e: e)) }

    private static let symbols: [String: String] = [
        "lightbulb": "lightbulb", "lightbulb-on": "lightbulb.fill", "lightbulb-group": "lightbulb.2.fill",
        "toggle-switch": "switch.2", "toggle-switch-off": "switch.2", "power-socket-eu": "powerplug.fill", "power-plug": "powerplug.fill", "power-plug-off": "powerplug",
        "fan": "fan.fill", "fan-off": "fan",
        "blinds": "blinds.horizontal.closed", "blinds-open": "blinds.horizontal.open", "curtains": "curtains.open", "curtains-closed": "curtains.closed",
        "garage": "door.garage.closed", "garage-open": "door.garage.open", "door-closed": "door.left.hand.closed", "door-open": "door.left.hand.open",
        "window-closed": "window.vertical.closed", "window-open": "window.vertical.open",
        "lock": "lock.fill", "lock-open-variant": "lock.open.fill",
        "thermostat": "thermometer.medium", "thermometer": "thermometer.medium", "water-percent": "humidity.fill", "water-boiler": "heater.vertical.fill",
        "air-humidifier": "humidifier.fill", "television": "tv", "speaker": "hifispeaker.fill", "play-circle": "play.circle.fill", "cast": "airplayvideo",
        "cctv": "video.fill", "shield-home": "shield.lefthalf.filled", "shield-off": "shield.slash", "shield-check": "checkmark.shield.fill", "shield-alert": "exclamationmark.shield.fill",
        "robot-vacuum": "sparkles", "robot-mower": "leaf.fill", "account": "person.fill", "cellphone": "iphone", "palette": "paintpalette.fill",
        "script-text-play": "play.rectangle.fill", "gesture-tap-button": "hand.tap.fill", "timer-outline": "timer", "calendar": "calendar",
        "clipboard-list": "checklist", "valve": "spigot.fill", "alarm-light": "light.beacon.max.fill", "package-up": "shippingbox.fill",
        "motion-sensor": "figure.walk.motion", "motion-sensor-off": "figure.stand", "water-alert": "drop.triangle.fill", "water-off": "drop",
        "smoke-detector": "smoke", "smoke-detector-alert": "smoke.fill", "alert": "exclamationmark.triangle.fill", "check-circle-outline": "checkmark.circle",
        "wifi": "wifi", "wifi-off": "wifi.slash", "battery-alert": "battery.0", "battery": "battery.100", "battery-30": "battery.25",
        "battery-60": "battery.50", "battery-80": "battery.75", "square": "square.fill", "square-outline": "square",
        "checkbox-marked-circle": "checkmark.circle.fill", "checkbox-blank-circle-outline": "circle",
        "flash": "bolt.fill", "lightning-bolt": "bolt.fill", "brightness-5": "sun.min.fill", "gauge": "gauge.medium", "molecule-co2": "carbon.dioxide.cloud.fill",
        "clock-outline": "clock", "cash": "banknote", "eye": "eye", "circle-medium": "circle.fill",
        "weather-sunny": "sun.max.fill", "weather-night": "moon.stars.fill", "weather-partly-cloudy": "cloud.sun.fill", "weather-night-partly-cloudy": "cloud.moon.fill",
        "weather-cloudy": "cloud.fill", "weather-rainy": "cloud.rain.fill", "weather-pouring": "cloud.heavyrain.fill", "weather-lightning": "cloud.bolt.fill",
        "weather-lightning-rainy": "cloud.bolt.rain.fill", "weather-snowy": "cloud.snow.fill", "weather-snowy-rainy": "cloud.sleet.fill", "weather-fog": "cloud.fog.fill",
        "weather-hail": "cloud.hail.fill", "weather-windy": "wind", "weather-windy-variant": "wind",
        "solar-power": "sun.max.fill", "transmission-tower": "powerline", "home-battery": "battery.100.bolt", "home-lightning-bolt": "house.fill",
        "car-electric": "car.fill", "ev-station": "ev.charger.fill", "trash-can": "trash.fill", "recycle": "arrow.3.trianglepath",
        "washing-machine": "washer.fill", "dishwasher": "dishwasher.fill", "tumble-dryer": "dryer.fill", "sofa": "sofa.fill",
    ]
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255, opacity: alpha)
    }
}

/// Numbers the widgets print: "21.5", "1234".
func fmt(_ v: Double) -> String { Rules.shared.fmt(v: v) }

/// "4 min ago".
func ago(_ date: Date?) -> String {
    guard let date else { return "never" }
    return Time.shared.ago(thenMillis: Int64(date.timeIntervalSince1970 * 1000), nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
}
