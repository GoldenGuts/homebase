import Foundation
import HomebaseCore

struct HAError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Home Assistant over REST and the WebSocket API. The primary address is tried first, then the
/// second one. Nothing here needs an admin user.
final class HAClient {
    static let shared = HAClient()

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 12
        c.timeoutIntervalForResource = 40
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    // MARK: - Login

    /// client_id is Home Assistant's own address, so its login page can redirect back to the same host,
    /// where the in-app web view catches the code. No external client page is needed.
    func clientId(_ base: String) -> String { base + "/" }
    func redirectURI(_ base: String) -> String { base + "/homebase/callback" }

    func authorizeURL(_ base: String) -> URL? {
        var c = URLComponents(string: base + "/auth/authorize")
        c?.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: clientId(base)),
            URLQueryItem(name: "redirect_uri", value: redirectURI(base)),
            URLQueryItem(name: "state", value: "homebase"),
        ]
        return c?.url
    }

    func exchange(code: String, base: String) async throws {
        let (data, status) = try await raw(base, "POST", "/auth/token", body: form(["grant_type": "authorization_code", "code": code, "client_id": clientId(base)]), form: true, token: nil)
        guard (200..<300).contains(status),
              let j = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let refresh = j["refresh_token"] as? String, let access = j["access_token"] as? String
        else { throw HAError("Login failed (\(status))") }
        Auth(longLived: nil, refreshToken: refresh, clientId: clientId(base), accessToken: access,
             expiresAt: Date().addingTimeInterval((j["expires_in"] as? Double) ?? 1800)).save()
        Prefs.baseURL = base
    }

    /// The bearer token: a long-lived token when set, else a fresh OAuth access token.
    func token(_ base: String, force: Bool = false) async throws -> String {
        guard var a = Auth.load() else { throw HAError("Not logged in") }
        if let ll = a.longLived, !ll.isEmpty { return ll }
        if !force, let t = a.accessToken, let exp = a.expiresAt, exp > Date().addingTimeInterval(60) { return t }
        guard let refresh = a.refreshToken else { throw HAError("Not logged in") }
        let (data, status) = try await raw(base, "POST", "/auth/token",
                                           body: form(["grant_type": "refresh_token", "refresh_token": refresh, "client_id": a.clientId ?? clientId(base)]), form: true, token: nil)
        if status == 400 || status == 401 { Auth.clear(); throw HAError("Login expired. Open Homebase to log in again.") }
        guard (200..<300).contains(status), let j = try JSONSerialization.jsonObject(with: data) as? [String: Any], let access = j["access_token"] as? String
        else { throw HAError("Token refresh failed (\(status))") }
        a.accessToken = access
        a.expiresAt = Date().addingTimeInterval((j["expires_in"] as? Double) ?? 1800)
        a.save()
        return access
    }

    // MARK: - REST

    /// A request against the first address that answers. HTTP errors are not retried on the other address.
    func request(_ method: String, _ path: String, json: Any? = nil) async throws -> Data {
        var last: Error = HAError("No Home Assistant address")
        let body = try json.map { try JSONSerialization.data(withJSONObject: $0) }
        for base in Prefs.bases {
            do {
                var (data, status) = try await raw(base, method, path, body: body, form: false, token: try await token(base))
                if status == 401, Auth.load()?.longLived == nil {
                    (data, status) = try await raw(base, method, path, body: body, form: false, token: try await token(base, force: true))
                }
                guard (200..<300).contains(status) else { throw HAError("\(path.split(separator: "?").first ?? ""): HTTP \(status)") }
                return data
            } catch let e as HAError {
                throw e
            } catch {
                last = error
            }
        }
        throw last
    }

    func states() async throws -> Data { try await request("GET", "/api/states") }

    func callService(_ domain: String, _ service: String, _ data: [String: Any]) async throws {
        _ = try await request("POST", "/api/services/\(domain)/\(service)", json: data)
    }

    /// Services that answer with data (weather.get_forecasts): the service_response object.
    func callServiceResponse(_ domain: String, _ service: String, _ data: [String: Any]) async throws -> [String: Any]? {
        let d = try await request("POST", "/api/services/\(domain)/\(service)?return_response", json: data)
        return (try JSONSerialization.jsonObject(with: d) as? [String: Any])?["service_response"] as? [String: Any]
    }

    func history(_ ids: [String], since: Date) async throws -> Data {
        let start = ISO8601DateFormatter().string(from: since).addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return try await request("GET", "/api/history/period/\(start)?filter_entity_id=\(ids.joined(separator: ","))&minimal_response&no_attributes")
    }

    /// Camera snapshots and artwork (entity_picture paths need the token).
    func image(_ pathOrURL: String) async -> Data? {
        for base in Prefs.bases {
            let full = pathOrURL.hasPrefix("http") ? pathOrURL : base + pathOrURL
            guard let url = URL(string: full) else { continue }
            var r = URLRequest(url: url)
            if !pathOrURL.hasPrefix("http") || pathOrURL.hasPrefix(base), let t = try? await token(base) { r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
            if let (d, resp) = try? await session.data(for: r), (resp as? HTTPURLResponse)?.statusCode == 200 { return d }
        }
        return nil
    }

    /// Does anything answer at this address? (401 counts: it is Home Assistant, just not logged in.)
    func ping(_ base: String) async -> Bool {
        guard let url = URL(string: base + "/api/") else { return false }
        guard let (_, resp) = try? await session.data(from: url) else { return false }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        return code == 200 || code == 401
    }

    func raw(_ base: String, _ method: String, _ path: String, body: Data?, form: Bool, token: String?) async throws -> (Data, Int) {
        guard let url = URL(string: base + path) else { throw HAError("Bad address \(base)") }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.httpBody = body
        r.setValue(form ? "application/x-www-form-urlencoded" : "application/json", forHTTPHeaderField: "Content-Type")
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, resp) = try await session.data(for: r)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func form(_ m: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return m.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }.joined(separator: "&").data(using: .utf8) ?? Data()
    }

    // MARK: - WebSocket

    /// Open `/api/websocket`, authenticate, run [body], close.
    func withSocket<T>(_ body: (WSSession) async throws -> T) async throws -> T {
        var last: Error = HAError("No Home Assistant address")
        for base in Prefs.bases {
            guard var comps = URLComponents(string: base + "/api/websocket") else { continue }
            comps.scheme = comps.scheme == "https" ? "wss" : "ws"
            guard let url = comps.url else { continue }
            let task = session.webSocketTask(with: url)
            task.maximumMessageSize = 64 * 1024 * 1024
            task.resume()
            let ws = WSSession(task: task)
            do {
                try await ws.authenticate(token: try await token(base))
            } catch let e as HAError {
                task.cancel(with: .goingAway, reason: nil); throw e
            } catch {
                task.cancel(with: .goingAway, reason: nil); last = error; continue
            }
            defer { task.cancel(with: .normalClosure, reason: nil) }
            return try await body(ws)
        }
        throw last
    }
}

/// One authenticated WebSocket connection; commands run one after another.
final class WSSession {
    private let task: URLSessionWebSocketTask
    private var nextId: Int32 = 1

    init(task: URLSessionWebSocketTask) { self.task = task }

    /// The next message; a socket silent for [timeout] seconds is closed, so the wait ends with an error
    /// instead of hanging a widget refresh.
    private func receiveText(timeout: TimeInterval = 30) async throws -> String {
        let socket = task
        let watchdog = Task {
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            if !Task.isCancelled { socket.cancel(with: .goingAway, reason: nil) }
        }
        defer { watchdog.cancel() }
        switch try await task.receive() {
        case .string(let s): return s
        case .data(let d): return String(decoding: d, as: UTF8.self)
        @unknown default: throw HAError("Unexpected WebSocket message")
        }
    }

    func authenticate(token: String) async throws {
        while true {
            for m in Ws.shared.parse(text: try await receiveText(timeout: 15)) {
                if m.isAuthRequired { try await task.send(.string(Ws.shared.auth(accessToken: token))) }
                else if m.isAuthOk { return }
                else if m.isAuthInvalid { throw HAError("Home Assistant rejected the login") }
            }
        }
    }

    /// Send a command and wait for its result.
    func call(_ type: String, _ fields: [String: Any] = [:]) async throws -> Json? {
        let id = nextId
        nextId += 1
        var obj = fields
        obj["id"] = Int(id)
        obj["type"] = type
        let data = try JSONSerialization.data(withJSONObject: obj)
        try await task.send(.string(String(decoding: data, as: UTF8.self)))
        while true {
            for m in Ws.shared.parse(text: try await receiveText()) where m.isResult && m.id?.int32Value == id {
                if !m.success { throw HAError("\(type): \(m.errorMessage ?? "failed")") }
                return m.result
            }
        }
    }

    /// Like [call], nil when Home Assistant answers with an error (a command it does not know).
    func tryCall(_ type: String, _ fields: [String: Any] = [:]) async -> Json? { try? await call(type, fields) }
}
