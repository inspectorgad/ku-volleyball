import SwiftUI
import WebKit

// The TestFlight test build. Its job is to prove the path from GitHub to a
// phone - Apple's signing, the upload, TestFlight - before the real app
// arrives on it. Until then it shows the season dashboard, so it is useful
// rather than blank. The shared Android/iPhone app replaces this file.

private let dashboard = URL(string: "https://inspectorgad.github.io/ku-volleyball/")!

@main
struct KUVolleyballApp: App {
    var body: some Scene {
        WindowGroup {
            DashboardView(url: dashboard)
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

struct DashboardView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.allowsBackForwardNavigationGestures = true
        // Pull down to reload, as in the Android app.
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.reload(_:)), for: .valueChanged)
        view.scrollView.refreshControl = refresh
        context.coordinator.webView = view
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject {
        weak var webView: WKWebView?

        @objc func reload(_ sender: UIRefreshControl) {
            webView?.reload()
            sender.endRefreshing()
        }
    }
}
