import SwiftData
import SwiftUI
import UserNotifications

@main
struct BookkeeperApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(Storage.container)
        .onChange(of: scenePhase) { _, phase in
            // 打开 App 时把没识别完的补上
            if phase == .active { Task { await Recognizer.shared.processAll() } }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Notifier.registerCategories()
        return true
    }

    // App 在前台时也显示通知
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == Notifier.undoAction,
              let ids = response.notification.request.content.userInfo["tx_ids"] as? [String] else { return }
        await MainActor.run { Notifier.undo(txIds: ids) }
    }
}
