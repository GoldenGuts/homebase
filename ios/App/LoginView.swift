import SwiftUI
import WebKit

/// Home Assistant's own login page in a web view. HA redirects to <base>/homebase/callback?code=…, which
/// the web view catches before it loads; the code is swapped for tokens. The client id is Home
/// Assistant's own address, so no outside page is involved.
struct LoginView: View {
    let base: String
    let onDone: (Bool) -> Void
    @State private var status = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            ZStack {
                LoginWebView(base: base, onCode: exchange, onError: { status = $0 })
                if busy { ProgressView("Connecting…").padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12)) }
            }
            .safeAreaInset(edge: .bottom) {
                if !status.isEmpty { Text(status).font(.footnote).foregroundStyle(Theme.pink).padding() }
            }
            .navigationTitle("Log in")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { onDone(false) } } }
        }
    }

    private func exchange(_ code: String) {
        busy = true
        Task {
            do {
                try await HAClient.shared.exchange(code: code, base: base)
                onDone(true)
            } catch {
                status = error.localizedDescription
                busy = false
            }
        }
    }
}

struct LoginWebView: UIViewRepresentable {
    let base: String
    let onCode: (String) -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        web.navigationDelegate = context.coordinator
        web.isOpaque = false
        web.backgroundColor = UIColor(Theme.bg)
        if let url = HAClient.shared.authorizeURL(base) { web.load(URLRequest(url: url)) }
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let parent: LoginWebView
        private var done = false

        init(_ parent: LoginWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, url.absoluteString.hasPrefix(HAClient.shared.redirectURI(parent.base)) else { decisionHandler(.allow); return }
            decisionHandler(.cancel)
            guard !done else { return }
            if let code = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value {
                done = true
                parent.onCode(code)
            } else {
                parent.onError("Login was cancelled")
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            parent.onError("Cannot reach \(parent.base): \(error.localizedDescription)")
        }
    }
}
