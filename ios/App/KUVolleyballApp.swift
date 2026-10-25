import SwiftUI
import UserNotifications
import Shared

// The iPhone app. Everything on screen is the shared Kotlin code - the same
// screens, data and season sync as the Android app - built into the Shared
// framework from shared/ and shown here full screen. MainViewController is
// in shared/src/iosMain/kotlin/com/example/MainViewController.kt.

@main
struct KUVolleyballApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            // The shared screens lay themselves out around the notch, the
            // home indicator and the keyboard, so they get the whole screen.
            SharedScreens().ignoresSafeArea()
        }
    }
}

struct SharedScreens: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Match alerts: Apple's push service reports this iPhone's alert code (its
/// device token) only to the app delegate, so this passes it on to the
/// shared code (IosAlerts.kt), which shows it on the Matches tab.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        IosAlertsKt.registerForAlertsIfOn()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let code = deviceToken.map { String(format: "%02x", $0) }.joined()
        IosAlertsKt.pushTokenReceived(token: code)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        IosAlertsKt.pushRegistrationFailed(reason: error.localizedDescription)
    }

    // An alert that arrives while the app is open still shows as a banner.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
