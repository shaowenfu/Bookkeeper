import Foundation
import SwiftData
import UserNotifications

/// 记账结果通知。记上了的带“撤销”按钮，不用打开 App。
enum Notifier {
    static let recordedCategory = "recorded"
    static let undoAction = "undo"

    static func registerCategories() {
        let undo = UNNotificationAction(identifier: undoAction, title: "撤销", options: [.destructive])
        let cat = UNNotificationCategory(identifier: recordedCategory, actions: [undo], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([cat])
    }

    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func notificationsAllowed() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
    }

    /// 一句话总结，快捷指令和通知都用
    @MainActor
    static func summary(_ r: Recognizer.Result) -> (title: String, body: String) {
        if let err = r.error {
            let final = r.event.attempts >= RawEvent.maxAttempts
            return ("识别失败", final ? "已重试 \(r.event.attempts) 次：\(err)" : "\(err)。打开 App 时会自动重试")
        }
        if r.outcomes.isEmpty { return ("没有识别到交易", "这一屏上没有找到付款信息") }
        let lines = r.outcomes.map { o -> String in
            let t = o.tx
            let who = t.merchant ?? t.app ?? "未知商户"
            return "\(Money.directionLabel(t.direction)) ¥\(Money.yuan(t.amountCents)) · \(who)" + (o.merged ? "（补充到已有的一笔）" : "")
        }
        return (r.outcomes.count == 1 ? "已记账" : "已记账 \(r.outcomes.count) 笔", lines.joined(separator: "\n"))
    }

    @MainActor
    static func report(_ r: Recognizer.Result) {
        // 失败但还会重试的不打扰
        if r.error != nil && r.event.attempts < RawEvent.maxAttempts { return }
        let s = summary(r)
        let c = UNMutableNotificationContent()
        c.title = s.title
        c.body = s.body
        let created = r.outcomes.filter { !$0.merged }.map { $0.tx.id.uuidString }
        if !created.isEmpty {
            c.categoryIdentifier = recordedCategory
            c.userInfo = ["tx_ids": created]
        }
        let req = UNNotificationRequest(identifier: r.event.id.uuidString, content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    @MainActor
    static func undo(txIds: [String]) {
        let ctx = Storage.container.mainContext
        for s in txIds {
            guard let id = UUID(uuidString: s) else { continue }
            let fd = FetchDescriptor<Tx>(predicate: #Predicate { $0.id == id })
            if let t = try? ctx.fetch(fd).first {
                t.status = Tx.voided
                t.updatedAt = .now
            }
        }
        try? ctx.save()
    }
}
