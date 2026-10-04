import Foundation
import Security

/// Where the app and the widget extension share data: an App Group for settings and caches, the
/// Keychain (with the App Group as access group) for tokens.
enum HB {
    static let appGroup = "group.in.weenja.homebase"

    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static func file(_ name: String) -> URL {
        let dir = container.appendingPathComponent("Homebase", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name)
    }

    static func read(_ name: String) -> Data? { try? Data(contentsOf: file(name)) }

    static func write(_ name: String, _ data: Data) { try? data.write(to: file(name), options: .atomic) }

    static func remove(_ name: String) { try? FileManager.default.removeItem(at: file(name)) }
}

/// App settings (not secrets) in the App Group.
enum Prefs {
    private static var d: UserDefaults { HB.defaults }

    static var baseURL: String {
        get { d.string(forKey: "base_url") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "base_url") }
    }
    static var altURL: String {
        get { d.string(forKey: "alt_url") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "alt_url") }
    }
    static var serverName: String {
        get { d.string(forKey: "server_name") ?? "" }
        set { d.set(newValue, forKey: "server_name") }
    }
    /// Demo mode: the made-up home from HomebaseCore (App Review, trying the app without Home Assistant).
    static var demo: Bool {
        get { d.bool(forKey: "demo") }
        set { d.set(newValue, forKey: "demo") }
    }
    /// The welcome screens were seen (connected once, or chose the demo home).
    static var onboarded: Bool {
        get { d.bool(forKey: "onboarded") || isConnected }
        set { d.set(newValue, forKey: "onboarded") }
    }
    static var lastFetch: Date? {
        get { d.object(forKey: "last_fetch") as? Date }
        set { d.set(newValue, forKey: "last_fetch") }
    }
    static var lastScan: Date? {
        get { d.object(forKey: "last_scan") as? Date }
        set { d.set(newValue, forKey: "last_scan") }
    }
    static var lastEnergy: Date? {
        get { d.object(forKey: "last_energy") as? Date }
        set { d.set(newValue, forKey: "last_energy") }
    }
    static var lastError: String? {
        get { d.string(forKey: "last_error") }
        set { d.set(newValue, forKey: "last_error") }
    }

    /// Two-tap confirmation (unlock, open the garage): when the first tap armed a key.
    static func armedAt(_ key: String) -> Date? { d.object(forKey: "armed_" + key) as? Date }
    static func arm(_ key: String, _ date: Date?) { d.set(date, forKey: "armed_" + key) }

    static var bases: [String] { [baseURL, altURL].filter { !$0.isEmpty }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } } }

    static var isConnected: Bool { !baseURL.isEmpty && Auth.load() != nil }
}

/// OAuth tokens or a long-lived token, stored in the Keychain and shared with the widgets.
struct Auth: Codable {
    var longLived: String?
    var refreshToken: String?
    var clientId: String?
    var accessToken: String?
    var expiresAt: Date?

    private static let account = "auth"

    static func load() -> Auth? {
        guard let data = Keychain.get(account) else { return nil }
        return try? JSONDecoder().decode(Auth.self, from: data)
    }

    func save() { if let data = try? JSONEncoder().encode(self) { Keychain.set(data, for: Auth.account) } }

    static func clear() { Keychain.set(nil, for: account) }
}

enum Keychain {
    private static let service = "in.weenja.homebase"

    private static func query(_ key: String, group: Bool) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        if group { q[kSecAttrAccessGroup as String] = HB.appGroup }
        return q
    }

    static func set(_ data: Data?, for key: String) {
        for group in [true, false] { SecItemDelete(query(key, group: group) as CFDictionary) }
        guard let data else { return }
        var add = query(key, group: true)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        if SecItemAdd(add as CFDictionary, nil) != errSecSuccess {
            // unsigned simulator builds have no access group: fall back to the app's own keychain
            var plain = query(key, group: false)
            plain[kSecValueData as String] = data
            plain[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(plain as CFDictionary, nil)
        }
    }

    static func get(_ key: String) -> Data? {
        for group in [true, false] {
            var q = query(key, group: group)
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            if SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data { return d }
        }
        return nil
    }
}
