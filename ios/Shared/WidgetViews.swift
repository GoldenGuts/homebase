import SwiftUI
import WidgetKit
import AppIntents
import HomebaseCore

// MARK: - Building blocks

/// Card title on the left, muted meta on the right.
struct Header: View {
    let title: String
    var meta: String = ""
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.ink).lineLimit(1)
            Spacer(minLength: 6)
            Text(meta).font(.system(size: 11.5)).foregroundStyle(Theme.muted).lineLimit(1)
        }
    }
}

/// "updated 4 min ago" (tap = refresh), or the demo / offline notice.
struct Freshness: View {
    let snap: Snapshot
    var body: some View {
        Button(intent: RefreshIntent()) {
            HStack(spacing: 4) {
                Image(systemName: "arrow.clockwise").font(.system(size: 8, weight: .bold))
                Text(label).font(.system(size: 9, weight: .medium)).lineLimit(1)
            }
            .foregroundStyle(color)
        }
        .buttonStyle(.plain)
    }

    private var label: String {
        if snap.demo { return Prefs.demo ? "demo home" : "demo · open Homebase to connect" }
        if Prefs.lastError != nil { return "offline · updated \(ago(snap.fetchedAt))" }
        return "updated \(ago(snap.fetchedAt))"
    }

    private var color: Color {
        if snap.demo { return Theme.dim }
        if Prefs.lastError != nil { return Theme.pink }
        if let f = snap.fetchedAt, Date().timeIntervalSince(f) > 1800 { return Theme.orange }
        return Theme.dim
    }
}

/// One entity as a device tile: gradient when on, dark when off; tap = the shared rules' action.
struct Tile: View {
    let snap: Snapshot
    let id: String
    var showState = true
    var body: some View {
        let e = snap.state(id)
        Button(intent: TapEntityIntent(id)) { TileFace(snap: snap, id: id, e: e, showState: showState) }
            .buttonStyle(.plain)
    }
}

struct TileFace: View {
    let snap: Snapshot
    let id: String
    let e: EntityState?
    var showState = true

    var body: some View {
        let alert = e.map { Rules.shared.isAlert(e: $0) } ?? false
        let active = e.map { Rules.shared.isActive(e: $0) } ?? false || alert
        let accent: Accent = e.map { alert ? Accent.pink : Rules.shared.accent(e: $0) } ?? Accent.muted
        let ink = active ? Theme.inkOn(accent) : Theme.ink
        let call = e.flatMap { Rules.shared.tapAction(e: $0) }
        let armed = call.map { $0.confirm && HomeActions.isArmed(id, $0.service) } ?? false
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(active ? AnyShapeStyle(Theme.gradient(accent)) : AnyShapeStyle(Theme.surface))
            if armed { RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.pink, lineWidth: 2) }
            VStack(spacing: 3) {
                Image(systemName: e.map { Theme.symbol(for: $0) } ?? "questionmark.circle")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(active ? ink : Theme.color(accent))
                Text(e?.name ?? id).font(.system(size: 10.5, weight: .bold)).foregroundStyle(ink)
                    .lineLimit(2).multilineTextAlignment(.center).minimumScaleFactor(0.85)
                if showState {
                    Text(armed ? "tap again" : e.map { Rules.shared.stateLabel(e: $0, nowMillis: snap.now) } ?? "not found")
                        .font(.system(size: 9, weight: .medium)).foregroundStyle(ink.opacity(0.75)).lineLimit(1)
                }
            }
            .padding(5)
        }
    }
}

/// Tiles in rows of [columns], at most [rows] rows; the last row stretches.
struct TileGrid: View {
    let snap: Snapshot
    let ids: [String]
    let columns: Int
    let rows: Int
    var showState = true

    var body: some View {
        let shown = Array(ids.prefix(columns * rows))
        let lines = stride(from: 0, to: shown.count, by: columns).map { Array(shown[$0..<min($0 + columns, shown.count)]) }
        VStack(spacing: 6) {
            ForEach(lines.indices, id: \.self) { r in
                HStack(spacing: 6) {
                    ForEach(lines[r], id: \.self) { id in Tile(snap: snap, id: id, showState: showState) }
                }
                // a single row in a large widget would otherwise stretch into tall pillars
                .frame(maxHeight: 84)
            }
        }
    }
}

/// Empty state in the middle of a widget.
struct EmptyNote: View {
    let icon: String
    let title: String
    let hint: String
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(Theme.muted)
            Text(title).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            Text(hint).font(.system(size: 10.5)).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension WidgetFamily {
    var gridColumns: Int {
        switch self {
        case .systemSmall: return 2
        case .systemExtraLarge: return 6
        default: return 4
        }
    }
    var gridRows: Int {
        switch self {
        case .systemSmall, .systemMedium: return 2
        case .systemLarge: return 4
        case .systemExtraLarge: return 4
        default: return 2
        }
    }
}

// MARK: - Favorites / Suggested

struct FavoritesView: View {
    let snap: Snapshot
    let ids: [String]
    let title: String
    let family: WidgetFamily

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if family != .systemSmall {
                Header(title: title, meta: "\(ids.filter { id in snap.state(id).map { Rules.shared.isActive(e: $0) } ?? false }.count) on")
            }
            if ids.isEmpty {
                EmptyNote(icon: "star", title: "No favorites yet", hint: "Star entities in Home Assistant, or pick them in the widget's settings")
            } else {
                TileGrid(snap: snap, ids: ids, columns: family.gridColumns, rows: family == .systemSmall ? 2 : family.gridRows, showState: family != .systemSmall)
            }
            if family == .systemLarge || family == .systemExtraLarge { Freshness(snap: snap).frame(maxWidth: .infinity) }
        }
    }
}

// MARK: - Room

struct RoomView: View {
    let snap: Snapshot
    let spec: WidgetSpec?
    let family: WidgetFamily

    var body: some View {
        let temp = snap.state(spec?.first(slot: "temperature"))
        let hum = snap.state(spec?.first(slot: "humidity"))
        let climate = snap.state(spec?.first(slot: "climate"))
        let items = spec?.list(slot: "items") ?? []
        VStack(alignment: .leading, spacing: 8) {
            Header(title: spec?.title ?? "Room", meta: "\(items.filter { snap.state($0).map { Rules.shared.isActive(e: $0) } ?? false }.count) on")
            if spec == nil {
                EmptyNote(icon: "sofa.fill", title: "Pick a room", hint: "Edit the widget to choose an area")
            } else {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 0) {
                        if let t = temp?.number?.doubleValue ?? climate.map({ $0.attrNumberOr(key: "current_temperature", def: .nan) }), !t.isNaN {
                            Text("\(fmt(t))°").font(.system(size: family == .systemSmall ? 26 : 30, weight: .bold)).foregroundStyle(Theme.ink)
                        }
                        if let h = hum?.number?.doubleValue {
                            Label("\(Int(h.rounded()))%", systemImage: "humidity.fill").font(.system(size: 11, weight: .medium)).foregroundStyle(Theme.muted)
                        }
                    }
                    Spacer()
                    if let c = climate, family != .systemSmall { Thermostat(e: c) }
                }
                if family != .systemSmall, !items.isEmpty {
                    TileGrid(snap: snap, ids: items, columns: 4, rows: family == .systemMedium ? 1 : 3, showState: family != .systemMedium)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

struct Thermostat: View {
    let e: EntityState
    var body: some View {
        let target = e.attrNumberOr(key: "temperature", def: .nan)
        let step = e.attrNumberOr(key: "target_temp_step", def: target > 45 ? 1 : 0.5)
        let action = e.attrStringOr(key: "hvac_action", def: e.state)
        let color: Color = action == "cooling" || e.state == "cool" ? Theme.cyan : (action == "heating" || e.state == "heat") ? Theme.orange : e.state == "off" ? Theme.muted : Theme.lime
        HStack(spacing: 6) {
            if !target.isNaN {
                Button(intent: ClimateStepIntent(e.id, target: target - step)) { Circle().fill(color.opacity(0.18)).overlay(Image(systemName: "minus").font(.system(size: 12, weight: .bold)).foregroundStyle(color)).frame(width: 30, height: 30) }.buttonStyle(.plain)
                VStack(spacing: 0) {
                    Text("\(fmt(target))°").font(.system(size: 16, weight: .bold)).foregroundStyle(color)
                    Text(Rules.shared.pretty(s: action == "idle" ? e.state : action)).font(.system(size: 9, weight: .semibold)).foregroundStyle(Theme.muted)
                }
                .frame(minWidth: 46)
                Button(intent: ClimateStepIntent(e.id, target: target + step)) { Circle().fill(color.opacity(0.18)).overlay(Image(systemName: "plus").font(.system(size: 12, weight: .bold)).foregroundStyle(color)).frame(width: 30, height: 30) }.buttonStyle(.plain)
            } else {
                Text(Rules.shared.stateLabel(e: e, nowMillis: 0)).font(.system(size: 11, weight: .semibold)).foregroundStyle(color)
            }
        }
    }
}

// MARK: - Energy

struct EnergyView: View {
    let snap: Snapshot
    let family: WidgetFamily

    var body: some View {
        let setup = snap.home.energy
        let today = EnergyToday.companion.of(home: snap.home)
        VStack(alignment: .leading, spacing: 8) {
            Header(title: "Energy", meta: today.home.map { "today · " + EnergyToday.companion.energy(kwh: $0.doubleValue) + " used" } ?? "today")
            if setup.isEmpty {
                EmptyNote(icon: "bolt.fill", title: "No Energy dashboard yet", hint: "Set it up in Home Assistant → Dashboards → Energy")
            } else {
                HStack(spacing: 6) {
                    if setup.hasSolar { EnergyCell(icon: "sun.max.fill", label: "Solar", live: today.solarW, total: today.solar, color: Theme.orange) }
                    EnergyCell(icon: "house.fill", label: "Home", live: today.homeW, total: today.home, color: Theme.cyan)
                    if setup.hasGrid && family != .systemSmall { EnergyCell(icon: "powerline", label: "Grid", live: today.gridW, total: today.gridIn, color: Theme.purple) }
                    if setup.hasBattery && family != .systemSmall {
                        EnergyCell(icon: "battery.100.bolt", label: "Battery", live: nil, total: nil, color: Theme.lime,
                                   big: today.batteryPct.map { "\(Int($0.doubleValue.rounded()))%" })
                    }
                }
            }
        }
    }
}

struct EnergyCell: View {
    let icon: String
    let label: String
    let live: KotlinDouble?
    let total: KotlinDouble?
    let color: Color
    var big: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 12, weight: .semibold)).foregroundStyle(color)
                Text(label).font(.system(size: 10, weight: .semibold)).foregroundStyle(Theme.muted).lineLimit(1)
            }
            Text(big ?? live.map { EnergyToday.companion.power(w: abs($0.doubleValue)) } ?? "—")
                .font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.ink).minimumScaleFactor(0.6).lineLimit(1)
            if let total { Text(EnergyToday.companion.energy(kwh: total.doubleValue)).font(.system(size: 9.5)).foregroundStyle(Theme.muted).lineLimit(1) }
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(color.opacity(0.14)))
    }
}

// MARK: - Security

struct SecurityView: View {
    let snap: Snapshot
    let family: WidgetFamily

    var body: some View {
        let ids = Planner.shared.security(home: snap.home, n: 12)
        let alerts = ids.compactMap { snap.state($0) }.filter { Rules.shared.isAlert(e: $0) }
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Security").font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.ink)
                Spacer()
                Label(alerts.isEmpty ? "All secure" : "\(alerts.count) need attention", systemImage: alerts.isEmpty ? "checkmark.shield.fill" : "exclamationmark.shield.fill")
                    .font(.system(size: 11, weight: .bold)).foregroundStyle(alerts.isEmpty ? Theme.lime : Theme.pink)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill((alerts.isEmpty ? Theme.lime : Theme.pink).opacity(0.16)))
            }
            if ids.isEmpty {
                EmptyNote(icon: "shield", title: "No doors, locks or alarm found", hint: "They appear once Home Assistant has them")
            } else if family == .systemSmall {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach((alerts.isEmpty ? ids.compactMap { snap.state($0) } : alerts).prefix(4), id: \.id) { e in
                        Label(e.name, systemImage: Theme.symbol(for: e)).font(.system(size: 11, weight: .medium))
                            .foregroundStyle(Rules.shared.isAlert(e: e) ? Theme.pink : Theme.muted).lineLimit(1)
                    }
                }
            } else {
                TileGrid(snap: snap, ids: ids, columns: family.gridColumns, rows: family.gridRows, showState: family != .systemMedium)
            }
        }
    }
}

// MARK: - Weather

struct WeatherView: View {
    let snap: Snapshot
    let family: WidgetFamily

    var body: some View {
        let w = snap.home.ofDomain(domain: "weather").first
        let daily = w.map { Weather.shared.forecasts(e: $0, daily: true) } ?? []
        VStack(alignment: .leading, spacing: 6) {
            if let w {
                let cond = w.state
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(w.name).font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.muted).lineLimit(1)
                        Text("\(fmt(w.attrNumberOr(key: "temperature", def: 0)))°").font(.system(size: family == .systemSmall ? 34 : 40, weight: .bold)).foregroundStyle(Theme.ink)
                        Text(Weather.shared.label(c: cond)).font(.system(size: 13, weight: .bold)).foregroundStyle(Theme.ink)
                        if let d = daily.first {
                            Text([d.temperature.map { "H \(fmt($0.doubleValue))°" }, d.templow.map { "L \(fmt($0.doubleValue))°" }].compactMap { $0 }.joined(separator: "  "))
                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                        }
                    }
                    Spacer()
                    Image(systemName: Theme.symbol(Weather.shared.icon(c: cond, day: !cond.contains("night"))))
                        .symbolRenderingMode(.multicolor).font(.system(size: family == .systemSmall ? 30 : 40))
                }
                if family != .systemSmall {
                    HStack(spacing: 0) {
                        ForEach(Array(daily.dropFirst().prefix(family == .systemMedium ? 4 : 6).enumerated()), id: \.offset) { _, f in
                            VStack(spacing: 3) {
                                Text(Date(timeIntervalSince1970: Double(f.atMillis) / 1000).formatted(.dateTime.weekday(.abbreviated)))
                                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(Theme.muted)
                                Image(systemName: Theme.symbol(Weather.shared.icon(c: f.condition, day: true))).symbolRenderingMode(.multicolor).font(.system(size: 15))
                                Text(f.temperature.map { "\(fmt($0.doubleValue))°" } ?? "–").font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.ink)
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                }
            } else {
                EmptyNote(icon: "cloud.sun", title: "No weather entity", hint: "Add a weather integration in Home Assistant")
            }
        }
    }
}

// MARK: - Now playing

struct NowPlayingView: View {
    let snap: Snapshot
    let playerId: String?
    let family: WidgetFamily

    var body: some View {
        let players = snap.home.ofDomain(domain: "media_player")
        let e = playerId.flatMap { snap.state($0) } ?? players.first { $0.state == "playing" } ?? players.first { $0.state == "paused" } ?? players.first
        let playing = e?.state == "playing"
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 12).fill(Theme.surface).frame(width: 46, height: 46)
                    .overlay(Image(systemName: "music.note").foregroundStyle(Theme.muted))
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.map { $0.attrStringOr(key: "media_title", def: $0.attrStringOr(key: "app_name", def: $0.name)) } ?? "Nothing playing")
                        .font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.ink).lineLimit(1)
                    Text(e.map { $0.attrStringOr(key: "media_artist", def: $0.attrStringOr(key: "media_series_title", def: $0.name)) } ?? "")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted).lineLimit(1)
                }
            }
            if let e {
                HStack(spacing: 0) {
                    Spacer()
                    Button(intent: MediaIntent(e.id, "media_previous_track")) { Image(systemName: "backward.fill").font(.system(size: 18)) }.buttonStyle(.plain)
                    Spacer()
                    Button(intent: MediaIntent(e.id, "media_play_pause")) { Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: 24)) }.buttonStyle(.plain)
                    Spacer()
                    Button(intent: MediaIntent(e.id, "media_next_track")) { Image(systemName: "forward.fill").font(.system(size: 18)) }.buttonStyle(.plain)
                    Spacer()
                }
                .foregroundStyle(Theme.ink)
            }
        }
    }
}

// MARK: - Sensor graph

struct GraphView: View {
    let snap: Snapshot
    let sensorId: String?
    let family: WidgetFamily

    var body: some View {
        let e = snap.state(sensorId) ?? Planner.shared.graphCandidate(home: snap.home, taken: Set<String>())
        let series = e?.attr(key: Extra.shared.HISTORY).flatMap { Series.companion.fromJson(j: $0) }
        VStack(alignment: .leading, spacing: 6) {
            Header(title: e?.name ?? "Sensor graph", meta: e.map { Rules.shared.stateLabel(e: $0, nowMillis: snap.now) } ?? "")
            if let series, series.points.count > 1 {
                let now = snap.now
                let values = series.bucketsNaN(n: 48, fromMillis: now - 24 * 3_600_000, toMillis: now).map { $0.doubleValue }
                let known = values.filter { !$0.isNaN }
                let lo = known.min() ?? 0, hi = known.max() ?? 1
                let accent = e.map { Theme.color(Rules.shared.accent(e: $0)) } ?? Theme.cyan
                GeometryReader { g in
                    let span = max(hi - lo, 0.0001)
                    let pts: [CGPoint] = values.enumerated().compactMap { i, v in
                        v.isNaN ? nil : CGPoint(x: g.size.width * CGFloat(i) / CGFloat(max(values.count - 1, 1)), y: g.size.height * CGFloat(1 - (v - lo) / span))
                    }
                    ZStack(alignment: .topLeading) {
                        Path { p in
                            guard let f = pts.first else { return }
                            p.move(to: CGPoint(x: f.x, y: g.size.height)); pts.forEach { p.addLine(to: $0) }
                            p.addLine(to: CGPoint(x: pts.last!.x, y: g.size.height)); p.closeSubpath()
                        }
                        .fill(LinearGradient(colors: [accent.opacity(0.35), accent.opacity(0)], startPoint: .top, endPoint: .bottom))
                        Path { p in guard let f = pts.first else { return }; p.move(to: f); pts.dropFirst().forEach { p.addLine(to: $0) } }
                            .stroke(accent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                        Text("\(fmt(hi))").font(.system(size: 9, weight: .semibold)).foregroundStyle(Theme.muted)
                        Text("\(fmt(lo))").font(.system(size: 9, weight: .semibold)).foregroundStyle(Theme.muted).offset(y: g.size.height - 12)
                    }
                }
            } else {
                EmptyNote(icon: "chart.xyaxis.line", title: e == nil ? "Pick a sensor" : "Collecting history…", hint: e == nil ? "Edit the widget to choose one" : "The graph fills in after the next refresh")
            }
        }
    }
}

// MARK: - Dashboard (full page)

/// The full-page dashboard: weather and energy up top, favorites, then every room's temperature and the security state.
struct DashboardView: View {
    let snap: Snapshot
    let family: WidgetFamily

    var body: some View {
        let favorites = snap.home.favorites.isEmpty ? Planner.shared.fillTaps(home: snap.home, n: 12, exclude: Set<String>()) : snap.home.favorites
        let rooms = Planner.shared.rooms(home: snap.home)
        let secure = Planner.shared.security(home: snap.home, n: 12).compactMap { snap.state($0) }.filter { Rules.shared.isAlert(e: $0) }
        let w = snap.home.ofDomain(domain: "weather").first
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(snap.home.locationName.isEmpty ? "Home" : snap.home.locationName).font(.system(size: 18, weight: .heavy)).foregroundStyle(Theme.ink)
                Spacer()
                if let w {
                    Image(systemName: Theme.symbol(Weather.shared.icon(c: w.state, day: true))).symbolRenderingMode(.multicolor)
                    Text("\(fmt(w.attrNumberOr(key: "temperature", def: 0)))° · \(Weather.shared.label(c: w.state))").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.ink)
                }
            }
            HStack(spacing: 6) {
                Label(secure.isEmpty ? "All secure" : "\(secure.count) open", systemImage: secure.isEmpty ? "checkmark.shield.fill" : "exclamationmark.shield.fill")
                    .foregroundStyle(secure.isEmpty ? Theme.lime : Theme.pink)
                if !snap.home.energy.isEmpty, let p = snap.state(snap.home.energy.gridPower)?.number?.doubleValue {
                    Label("grid " + EnergyToday.companion.power(w: p), systemImage: "bolt.fill").foregroundStyle(Theme.purple)
                }
                Spacer()
            }
            .font(.system(size: 11, weight: .bold))
            TileGrid(snap: snap, ids: favorites, columns: family == .systemExtraLarge ? 6 : 4, rows: family == .systemExtraLarge ? 2 : 3)
            Text("ROOMS").font(.system(size: 10, weight: .bold)).kerning(1).foregroundStyle(Theme.muted)
            let columns = family == .systemExtraLarge ? 4 : 3
            let shown = Array(rooms.prefix(columns * 2))
            VStack(spacing: 6) {
                ForEach(stride(from: 0, to: shown.count, by: columns).map { $0 }, id: \.self) { start in
                    HStack(spacing: 6) {
                        ForEach(shown[start..<min(start + columns, shown.count)], id: \.title) { r in
                            let t = snap.state(r.first(slot: "temperature"))?.number?.doubleValue
                            let on = r.list(slot: "items").filter { snap.state($0).map { Rules.shared.isActive(e: $0) } ?? false }.count
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.title).font(.system(size: 11, weight: .bold)).foregroundStyle(Theme.ink).lineLimit(1)
                                Text([t.map { "\(fmt($0))°" }, on > 0 ? "\(on) on" : nil].compactMap { $0 }.joined(separator: " · "))
                                    .font(.system(size: 10)).foregroundStyle(Theme.muted).lineLimit(1)
                            }
                            .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.surface))
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            Freshness(snap: snap).frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Accessory (lock screen)

struct AccessoryView: View {
    let snap: Snapshot
    let id: String?
    let family: WidgetFamily

    var body: some View {
        let e = snap.state(id)
        switch family {
        case .accessoryCircular:
            Button(intent: TapEntityIntent(id ?? "")) {
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 1) {
                        Image(systemName: e.map { Theme.symbol(for: $0) } ?? "house.fill").font(.system(size: 16, weight: .semibold))
                        Text(e.map { Rules.shared.isActive(e: $0) ? "on" : Rules.shared.stateLabel(e: $0, nowMillis: 0) } ?? "").font(.system(size: 9)).lineLimit(1)
                    }
                }
            }.buttonStyle(.plain)
        case .accessoryInline:
            Label(e.map { "\($0.name) · \(Rules.shared.stateLabel(e: $0, nowMillis: 0))" } ?? "Homebase", systemImage: e.map { Theme.symbol(for: $0) } ?? "house.fill")
        default:
            Button(intent: TapEntityIntent(id ?? "")) {
                HStack {
                    Image(systemName: e.map { Theme.symbol(for: $0) } ?? "house.fill").font(.system(size: 20, weight: .semibold))
                    VStack(alignment: .leading) {
                        Text(e?.name ?? "Pick an entity").font(.headline).lineLimit(1)
                        Text(e.map { Rules.shared.stateLabel(e: $0, nowMillis: 0) } ?? "").font(.caption).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
            }.buttonStyle(.plain)
        }
    }
}
