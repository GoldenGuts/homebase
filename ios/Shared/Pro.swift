import Foundation
import SwiftUI
import WidgetKit

/// Homebase Pro: three widgets are free (Favorites, Tile, Weather in the small size); one App Store
/// purchase unlocks every widget, for good. The demo home shows everything, unlocked.
enum Pro {
    /// App Store Connect → In-App Purchases: a non-consumable with this product id.
    static let productID = "in.weenja.homebase.pro"

    /// Written by the app after StoreKit confirms the purchase; the widgets read it from the App Group.
    static var purchased: Bool {
        get { HB.defaults.bool(forKey: "pro_unlocked") }
        set { HB.defaults.set(newValue, forKey: "pro_unlocked") }
    }

    /// Bought (store codes redeemed in the App Store count as bought).
    static var unlocked: Bool { purchased }

    static func allowed(kind: String, family: WidgetFamily) -> Bool {
        if unlocked || Prefs.demo || !Prefs.isConnected { return true }
        switch kind {
        case "favorites", "tile": return true
        case "weather": return family == .systemSmall
        default: return false
        }
    }
}

/// A Pro widget on a free install: what it is, and a tap that opens the unlock screen in the app.
struct Gated<Content: View>: View {
    let kind: String
    let title: String
    @ViewBuilder let content: Content
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if Pro.allowed(kind: kind, family: family) {
            content
        } else {
            VStack(spacing: 6) {
                if family != .systemSmall {
                    Text(title).font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.muted).frame(maxWidth: .infinity, alignment: .leading)
                    Spacer(minLength: 0)
                }
                Image(systemName: "lock.fill").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.cyan)
                    .frame(width: 38, height: 38).background(Theme.cyan.opacity(0.16), in: Circle())
                Text("Homebase Pro").font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.ink)
                Text(family == .systemSmall ? "Tap to unlock" : "Tap to unlock every widget, once").font(.system(size: 11)).foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                if family != .systemSmall { Spacer(minLength: 0) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .widgetURL(URL(string: "homebase://pro"))
        }
    }
}
