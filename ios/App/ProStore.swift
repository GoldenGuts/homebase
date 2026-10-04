import SwiftUI
import StoreKit
import WidgetKit

/// StoreKit 2: the one-time Homebase Pro purchase, restore, and updates (a purchase on another device,
/// Family Sharing, a refund). The result goes to the App Group, where the widgets read it.
@MainActor
final class ProStore: ObservableObject {
    static let shared = ProStore()

    @Published private(set) var product: Product?
    @Published private(set) var unlocked = Pro.unlocked
    @Published var message = ""
    @Published var busy = false
    private var updates: Task<Void, Never>?

    func start() {
        guard updates == nil else { return }
        updates = Task.detached {
            for await result in Transaction.updates {
                if case .verified(let t) = result { await t.finish() }
                await ProStore.shared.refresh()
            }
        }
        Task { await load(); await refresh() }
    }

    func load() async {
        if product != nil { return }
        product = try? await Product.products(for: [Pro.productID]).first
    }

    /// What this Apple ID owns right now.
    func refresh() async {
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let t) = result, t.productID == Pro.productID, t.revocationDate == nil { owned = true }
        }
        set(owned)
    }

    func buy() async {
        await load()
        guard let product else { message = "The App Store is not reachable right now. Try again in a moment."; return }
        busy = true
        defer { busy = false }
        do {
            switch try await product.purchase() {
            case .success(let result):
                if case .verified(let t) = result { await t.finish(); set(true); message = "" }
                else { message = "The App Store could not verify the purchase." }
            case .pending: message = "Waiting for approval. Homebase unlocks as soon as it is approved."
            case .userCancelled: break
            @unknown default: break
            }
        } catch { message = error.localizedDescription }
    }

    func restore() async {
        busy = true
        defer { busy = false }
        try? await AppStore.sync()
        await refresh()
        message = unlocked ? "" : "No purchase found for this Apple ID."
    }

    private func set(_ owned: Bool) {
        let before = Pro.unlocked
        Pro.purchased = owned
        unlocked = Pro.unlocked
        if before != Pro.unlocked { HomeData.reloadWidgets() }
    }
}

/// What is free, what the purchase unlocks, buy / restore, and the custom-setup offer.
struct ProView: View {
    @ObservedObject var store = ProStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Every widget, for good. One purchase, no subscription.").font(.subheadline).foregroundStyle(Theme.muted)
                    VStack(alignment: .leading, spacing: 12) {
                        Text("INCLUDED").font(.system(size: 11, weight: .bold)).kerning(0.9).foregroundStyle(Theme.muted)
                        Perk(title: "Every widget", line: "Home dashboard, Rooms, Energy, Security, Suggested, Now playing, Sensor graph and Weather in every size")
                        Perk(title: "Every size", line: "From the Lock Screen to a full-page dashboard")
                        Perk(title: "Everything that comes next", line: "New widgets arrive as updates, at no extra cost")
                        Text("FREE FOREVER").font(.system(size: 11, weight: .bold)).kerning(0.9).foregroundStyle(Theme.muted).padding(.top, 6)
                        Text("Favorites, Tile and Weather (small), plus the demo home with every widget.").font(.footnote).foregroundStyle(Theme.muted)
                    }
                    .padding(18).background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.border, lineWidth: 1))

                    if !store.message.isEmpty { Text(store.message).font(.footnote.weight(.semibold)).foregroundStyle(Theme.pink) }
                    if store.unlocked {
                        Text("Unlocked. Thank you! Every widget is yours.").font(.headline).foregroundStyle(Theme.lime)
                        Button { dismiss() } label: { Primary("Done") }
                    } else {
                        Button { Task { await store.buy() } } label: {
                            Primary(store.busy ? "…" : "Unlock everything" + (store.product.map { " · \($0.displayPrice)" } ?? ""))
                        }
                        .disabled(store.busy)
                        Button { Task { await store.restore() } } label: { Secondary("Restore purchase") }
                        Text("Paid once through the App Store and tied to your Apple ID; Family Sharing works too.")
                            .font(.caption).foregroundStyle(Theme.dim).frame(maxWidth: .infinity)
                    }
                    SupportCard()
                }
                .padding(20)
            }
            .background(Theme.bg)
            .navigationTitle("Homebase Pro")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .task { await store.load(); await store.refresh() }
        }
        .preferredColorScheme(.dark)
    }
}

private struct Perk: View {
    let title: String
    let line: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.lime)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.ink)
                Text(line).font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// "Tell me what you want": feedback by e-mail, at the bottom of the app. Opens the user's own mail
/// app with three short questions and the app and iOS versions; the app itself sends nothing.
struct FeedbackCard: View {
    @Environment(\.openURL) private var openURL

    static var mail: URL {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let body = "What do you like?\n\n\nWhat is missing (a widget, a device, a setting)?\n\n\nWhat does not work?\n\n\n" +
            "--\nHomebase \(version) · iOS \(UIDevice.current.systemVersion) · \(UIDevice.current.model)"
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = "support@weenja.in"
        c.queryItems = [URLQueryItem(name: "subject", value: "Homebase feedback"), URLQueryItem(name: "body", value: body)]
        return c.url ?? URL(string: "mailto:support@weenja.in")!
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Tell me what you want").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.ink)
            Text("A widget you miss, a device it should show, something that does not work. I read every e-mail.")
                .font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            Button { openURL(Self.mail) } label: { Secondary("Send feedback") }.padding(.top, 6)
        }
        .padding(16).background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.border, lineWidth: 1))
    }
}

/// "Want something custom?" — the offer to set things up, at the bottom of the app and in Pro.
struct SupportCard: View {
    /// Where "Get in touch" leads.
    static let contact = URL(string: "mailto:support@weenja.in?subject=Homebase%3A%20custom%20setup")!
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Want something custom?").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.ink)
            Text("A widget for your setup, an automation, a dashboard, a sensor that needs YAML: I'll set it up for you.")
                .font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            Button { openURL(Self.contact) } label: { Secondary("Get in touch") }.padding(.top, 6)
            Text("support@weenja.in").font(.caption).foregroundStyle(Theme.dim).textSelection(.enabled).frame(maxWidth: .infinity)
        }
        .padding(16).background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.border, lineWidth: 1))
    }
}
