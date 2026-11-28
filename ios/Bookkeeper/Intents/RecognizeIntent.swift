import AppIntents
import Foundation
import UniformTypeIdentifiers

/// 快捷指令动作“识别截图记账”。配合“截屏”动作和“轻点背面”使用：
/// 轻点两下手机背面 → 系统截屏 → 交给本 App 在后台识别 → 弹通知。App 不需要常驻后台。
struct RecognizeScreenshotIntent: AppIntent {
    static var title: LocalizedStringResource = "识别截图记账"
    static var description = IntentDescription("把一张截图（通常来自“截屏”动作）识别成账目，记进截图记账。")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "截图", supportedTypeIdentifiers: ["public.image"])
    var image: IntentFile

    static var parameterSummary: some ParameterSummary {
        Summary("识别 \(\.$image) 并记账")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let event = try Recognizer.shared.enqueue(imageData: image.data, source: "back_tap")
        // 快捷指令在后台只有几十秒，识别超时就留到打开 App 时再试
        let r = await Recognizer.shared.process(event, timeout: 25)
        let s = Notifier.summary(r)
        if await Notifier.notificationsAllowed() {
            // 详情和“撤销”按钮在通知里，这里只给一句
            Notifier.report(r)
            return .result(dialog: IntentDialog(stringLiteral: s.title))
        }
        return .result(dialog: IntentDialog(stringLiteral: "\(s.title)：\(s.body)"))
    }
}

/// 导入一张或多张图片（相册里的截图、账单长图），可以放进分享菜单
struct ImportImagesIntent: AppIntent {
    static var title: LocalizedStringResource = "导入图片记账"
    static var description = IntentDescription("把一张或多张图片加入识别队列，逐张识别。")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "图片", supportedTypeIdentifiers: ["public.image"])
    var images: [IntentFile]

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        var events: [RawEvent] = []
        for f in images { events.append(try Recognizer.shared.enqueue(imageData: f.data, source: "share")) }
        var done = 0
        for e in events {
            let r = await Recognizer.shared.process(e, timeout: 25)
            Notifier.report(r)
            done += r.outcomes.count
        }
        return .result(dialog: IntentDialog(stringLiteral: "处理了 \(events.count) 张图，记上 \(done) 笔"))
    }
}

struct BookkeeperShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ImportImagesIntent(),
            phrases: ["用\(.applicationName)导入图片"],
            shortTitle: "导入图片记账",
            systemImageName: "photo.on.rectangle"
        )
    }
}
