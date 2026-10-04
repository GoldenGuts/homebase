import Foundation
import Network

/// Finds Home Assistant on the local network (Bonjour `_home-assistant._tcp`). The TXT record carries
/// location_name, internal_url, external_url and base_url.
@MainActor
final class Discovery: ObservableObject {
    struct Server: Identifiable, Hashable {
        let id: String
        let name: String
        let urls: [String]
        let version: String
    }

    @Published private(set) var servers: [Server] = []
    @Published private(set) var searching = false
    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_home-assistant._tcp", domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let found: [Server] = results.compactMap { r in
                guard case let .service(name, _, _, _) = r.endpoint else { return nil }
                var txt: [String: String] = [:]
                if case let .bonjour(record) = r.metadata { txt = record.dictionary }
                let urls = [txt["internal_url"], txt["base_url"], txt["external_url"]]
                    .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
                    .map { $0.hasSuffix("/") ? String($0.dropLast()) : $0 }
                    .filter { $0.hasPrefix("http") }
                var seen = Set<String>()
                let unique = urls.filter { seen.insert($0).inserted }
                guard !unique.isEmpty else { return nil }
                return Server(id: txt["uuid"] ?? name, name: txt["location_name"] ?? name, urls: unique, version: txt["version"] ?? "")
            }
            Task { @MainActor in self?.servers = found }
        }
        b.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready: self?.searching = true
                case .failed, .cancelled: self?.searching = false
                default: break
                }
            }
        }
        b.start(queue: .main)
        browser = b
        searching = true
    }

    func stop() {
        browser?.cancel()
        browser = nil
        searching = false
    }
}
