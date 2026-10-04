import SwiftUI
import HomebaseCore

/// Onboarding, one step at a time: Welcome → Connect (servers found on the network, the address, or a
/// long-lived token) → Home Assistant's own login page → Setting up (a live checklist of what the scan
/// found) → the app's home with "For your home". Same steps and look as Android.
struct ConnectView: View {
    @EnvironmentObject var model: AppModel
    enum Step { case welcome, connect, setup }
    @State private var step: Step = Prefs.onboarded ? .connect : .welcome

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            switch step {
            case .welcome: WelcomeStep(start: { step = .connect }, demo: { model.setDemo(true) })
            case .connect: ConnectStep(back: backToWelcome, connected: {
                model.settingUp = true
                step = .setup
            })
            case .setup: SetupStep(back: { model.settingUp = false; step = .connect }, finish: {
                Prefs.onboarded = true
                model.settingUp = false
                model.reload()
                HomeData.reloadWidgets()
            })
            }
        }
        .animation(.easeInOut(duration: 0.25), value: step)
        .preferredColorScheme(.dark)
    }

    /// Back from Connect to Welcome only during the first run.
    private var backToWelcome: (() -> Void)? {
        if Prefs.onboarded { return nil }
        return { step = .welcome }
    }
}

/// Three pills: the current step is long and cyan.
private struct StepDots: View {
    let current: Int
    var body: some View {
        HStack(spacing: 6) {
            ForEach(1...3, id: \.self) { i in
                Capsule().fill(i <= current ? Theme.cyan : Theme.surface)
                    .opacity(i < current ? 0.45 : 1)
                    .frame(width: i == current ? 28 : 10, height: 6)
            }
        }
        .padding(.bottom, 18)
    }
}

private struct StepTitle: View {
    let title: String
    let sub: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 30, weight: .heavy)).foregroundStyle(Theme.ink)
            Text(sub).font(.system(size: 15)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased()).font(.system(size: 11, weight: .bold)).kerning(0.9).foregroundStyle(Theme.muted)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 18)
    }
}

// MARK: - 0 · Welcome

private struct WelcomeStep: View {
    let start: () -> Void
    let demo: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 24)
            ZStack {
                RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.gradient(Accent.cyan))
                Image(systemName: "house.fill").font(.system(size: 34, weight: .bold)).foregroundStyle(Color(hex: 0x06131b))
            }
            .frame(width: 76, height: 76)
            .padding(.bottom, 24)
            StepTitle(title: "Your home,\non your home screen",
                      sub: "Homebase turns your Home Assistant into widgets: your rooms, favorites, energy and doors, set up for you.")
            VStack(spacing: 4) {
                Feature(symbol: "wifi", color: Theme.cyan, title: "Finds Home Assistant", line: "on your Wi-Fi. You log in once, on its own page.")
                Feature(symbol: "wand.and.stars", color: Theme.lime, title: "Builds your widgets", line: "from your areas, favorites and energy dashboard.")
                Feature(symbol: "lock.shield", color: Theme.pink, title: "Stays private", line: "It talks to your server only. No account, no cloud, no tracking.")
            }
            .padding(.top, 18)
            Spacer(minLength: 24)
            Button(action: start) { Primary("Get started") }
            Button(action: demo) {
                Text("Look around with a demo home").font(.headline).foregroundStyle(Theme.cyan)
                    .frame(maxWidth: .infinity).padding(.vertical, 13)
                    .overlay(Capsule().stroke(Theme.border, lineWidth: 1))
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 22).padding(.bottom, 20)
    }
}

private struct Feature: View {
    let symbol: String
    let color: Color
    let title: String
    let line: String
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol).font(.system(size: 19, weight: .semibold)).foregroundStyle(color)
                .frame(width: 44, height: 44).background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.ink)
                Text(line).font(.system(size: 13.5)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
    }
}

// MARK: - 1 · Connect

private struct ConnectStep: View {
    let back: (() -> Void)?
    let connected: () -> Void
    @StateObject private var discovery = Discovery()
    @State private var address = Prefs.baseURL
    @State private var second = Prefs.altURL
    @State private var token = ""
    @State private var more = false
    @State private var loginBase: String?
    @State private var message = ""
    @State private var slow = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    StepDots(current: 1)
                    Spacer()
                    if let back { Button("Back", action: back).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.muted).padding(.bottom, 18) }
                }
                StepTitle(title: "Connect", sub: "Pick your Home Assistant. You log in on its own page; Homebase never sees your password.")
                if !message.isEmpty { Text(message).font(.footnote.weight(.semibold)).foregroundStyle(Theme.pink).padding(.top, 6) }

                SectionLabel("On this Wi-Fi")
                if discovery.servers.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView().tint(Theme.cyan)
                        Text(slow ? "Nothing found yet. Is this phone on the same Wi-Fi? You can type the address below." : "Looking for Home Assistant…")
                            .font(.footnote).foregroundStyle(Theme.muted)
                    }
                    .padding(.vertical, 6)
                }
                ForEach(discovery.servers) { s in
                    Button { use(s.urls.first ?? "", alt: s.urls.dropFirst().first ?? "", name: s.name) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "house").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.cyan)
                                .frame(width: 40, height: 40).background(Theme.cyan.opacity(0.15), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.name).font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.ink)
                                Text(s.urls.first.map { $0.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") } ?? "")
                                    .font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
                        }
                        .padding(14)
                        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.border, lineWidth: 1))
                    }
                }

                SectionLabel("Or type its address")
                Field("192.168.1.10:8123 or home.example.com", text: $address)
                Button { use(normalize(address), alt: normalize(second), name: "") } label: { Primary("Continue") }.padding(.top, 4)

                if more {
                    SectionLabel("Second address (optional)")
                    Text("Used when the first one does not answer, e.g. your LAN address next to a remote one.").font(.footnote).foregroundStyle(Theme.muted)
                    Field("http://192.168.1.10:8123", text: $second)
                    SectionLabel("Long-lived token")
                    Text("In Home Assistant: your profile → Security → Long-lived access tokens → Create token. Scan its QR code with the Camera app and copy it, then paste it here with the address above.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                    SecureField("Token", text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
                    HStack {
                        Button { token = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" } label: { Secondary("Paste") }
                        Button { useToken() } label: { Primary("Use token") }
                    }
                } else {
                    Button { more = true } label: {
                        Label("Other ways to connect", systemImage: "key").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.cyan)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .overlay(Capsule().stroke(Theme.border, lineWidth: 1))
                    }
                    .padding(.top, 14)
                }
            }
            .padding(.horizontal, 22).padding(.vertical, 16)
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop() }
        .task { try? await Task.sleep(nanoseconds: 8_000_000_000); slow = true }
        .sheet(item: Binding(get: { loginBase.map { LoginTarget(base: $0) } }, set: { loginBase = $0?.base })) { t in
            LoginView(base: t.base) { ok in
                loginBase = nil
                if ok { connected() }
            }
        }
    }

    private struct LoginTarget: Identifiable { let base: String; var id: String { base } }

    private func use(_ base: String, alt: String, name: String) {
        guard !base.isEmpty else { message = "Enter the address you open Home Assistant with"; return }
        message = ""
        Prefs.baseURL = base
        Prefs.altURL = alt
        if !name.isEmpty { Prefs.serverName = name }
        loginBase = base
    }

    private func useToken() {
        let base = normalize(address)
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else { message = "Enter the address too"; return }
        guard t.split(separator: ".").count >= 3 else { message = "That does not look like a token"; return }
        Prefs.baseURL = base
        Prefs.altURL = normalize(second)
        Auth(longLived: t, refreshToken: nil, clientId: nil, accessToken: nil, expiresAt: nil).save()
        connected()
    }

    private func normalize(_ s: String) -> String {
        var u = s.trimmingCharacters(in: .whitespaces)
        while u.hasSuffix("/") { u.removeLast() }
        if u.isEmpty { return "" }
        if !u.hasPrefix("http") {
            let local = u.range(of: #"^\d+\.\d+\.\d+\.\d+"#, options: .regularExpression) != nil || u.contains(".local")
            u = (local ? "http://" : "https://") + u
        }
        return u
    }
}

// MARK: - 2 · Setting up

private struct SetupStep: View {
    let back: () -> Void
    let finish: () -> Void

    enum Phase { case waiting, running, done, warn }
    struct Check { var phase: Phase = .waiting; var detail = "" }

    @State private var checks = [Check(), Check(), Check(), Check()]
    @State private var widgets = 0
    @State private var finished = false
    @State private var failure: String?

    private let titles = ["Connecting to Home Assistant", "Rooms and devices", "Favorites and suggestions", "Energy dashboard"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                StepDots(current: 2)
                StepTitle(title: "Setting up", sub: "Reading your home. This takes a few seconds.")
                VStack(spacing: 0) {
                    ForEach(0..<4, id: \.self) { i in row(i) }
                }
                .padding(.top, 14)
                if let failure {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(failure).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.pink)
                        Button(action: back) { Secondary("Back to Connect") }
                    }
                    .padding(16).background(Theme.card, in: RoundedRectangle(cornerRadius: 20)).padding(.top, 20)
                } else if finished {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(widgets > 0 ? "\(widgets) widgets ready for your home" : "You're connected").font(.system(size: 19, weight: .heavy)).foregroundStyle(Theme.ink)
                        Text("Next: the widgets built from your home, with previews. To add one, touch and hold the Home Screen → Edit → Add Widget → Homebase. Touch and hold a widget → Edit Widget to change it.")
                            .font(.footnote).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
                        Button(action: finish) { Primary("Show my widgets") }.padding(.top, 6)
                    }
                    .padding(18).background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.border, lineWidth: 1))
                    .padding(.top, 22)
                }
            }
            .padding(.horizontal, 22).padding(.vertical, 16)
        }
        .task { await run() }
    }

    @ViewBuilder private func row(_ i: Int) -> some View {
        let c = checks[i]
        HStack(alignment: .center, spacing: 14) {
            ZStack {
                switch c.phase {
                case .waiting: Circle().stroke(Theme.surface, lineWidth: 2).frame(width: 20, height: 20)
                case .running: ProgressView().tint(Theme.cyan)
                case .done: Image(systemName: "checkmark.circle.fill").font(.system(size: 22)).foregroundStyle(Theme.lime)
                case .warn: Image(systemName: "info.circle.fill").font(.system(size: 22)).foregroundStyle(Theme.orange)
                }
            }
            .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(titles[i]).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.ink)
                if !c.detail.isEmpty { Text(c.detail).font(.footnote).foregroundStyle(Theme.muted) }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
        .opacity(c.phase == .waiting ? 0.35 : 1)
    }

    private func set(_ i: Int, _ phase: Phase, _ detail: String = "") {
        withAnimation(.easeOut(duration: 0.2)) { checks[i] = Check(phase: phase, detail: detail) }
    }

    private func plural(_ n: Int, _ word: String) -> String { "\(n) \(word)" + (n == 1 ? "" : "s") }

    private func run() async {
        Prefs.demo = false
        set(0, .running)
        let snap = await HomeData.refresh(Needs.all, force: true)
        if let e = Prefs.lastError {
            set(0, .warn, e)
            failure = "Could not load your home: \(e)"
            return
        }
        let server = Prefs.serverName.isEmpty ? Prefs.baseURL.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "") : Prefs.serverName
        set(0, .done, "\(server) · \(snap.home.states.count) entities")
        set(1, .running)
        do {
            try await HomeData.scan()
        } catch {
            set(1, .warn, "The scan did not finish; widgets still work, and it tries again later.")
            set(2, .warn); set(3, .warn)
            finished = true
            return
        }
        let home = HomeData.load().home
        let r = home.registry
        let parts = [plural(r.areas.count, "area"), plural(r.floors.count, "floor"), plural(r.devices.count, "device")].filter { !$0.hasPrefix("0 ") }
        set(1, r.areas.isEmpty ? .warn : .done, parts.isEmpty ? "No areas yet: widgets group by device type instead" : parts.joined(separator: " · "))
        set(2, .done, "\(plural(home.favorites.count, "favorite")) · \(plural(home.suggested.count, "suggestion"))")
        let e = home.energy
        let sources = [e.gridImport.isEmpty ? nil : "grid", e.solarEnergy.isEmpty ? nil : "solar", e.batteryIn.isEmpty ? nil : "battery"].compactMap { $0 }
        set(3, e.todayStats.isEmpty ? .warn : .done, e.todayStats.isEmpty ? "Not set up (optional)" : sources.joined(separator: " · "))
        widgets = Planner.shared.plan(home: home).count
        withAnimation { finished = true }
    }
}

// MARK: - Small shared pieces

struct Block<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.title3.weight(.bold)).foregroundStyle(Theme.ink)
            content
        }
    }
}

struct Field: View {
    let placeholder: String
    @Binding var text: String
    init(_ placeholder: String, text: Binding<String>) { self.placeholder = placeholder; self._text = text }
    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct Primary: View {
    let label: String
    init(_ label: String) { self.label = label }
    var body: some View {
        Text(label).font(.headline).foregroundStyle(Color(hex: 0x06131b)).frame(maxWidth: .infinity).padding(.vertical, 13)
            .background(Theme.cyan, in: Capsule())
    }
}

struct Secondary: View {
    let label: String
    init(_ label: String) { self.label = label }
    var body: some View {
        Text(label).font(.headline).foregroundStyle(Theme.ink).frame(maxWidth: .infinity).padding(.vertical, 13)
            .background(Theme.surface, in: Capsule())
    }
}
