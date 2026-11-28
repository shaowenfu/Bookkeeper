import Foundation
import SwiftData

/// 识别队列：逐条处理待识别的截图，失败的最多重试 3 次。全部在主线程上跑（网络请求是异步的，不会卡界面）。
@MainActor
final class Recognizer {
    static let shared = Recognizer()

    private var running = false
    private var again = false

    struct Result {
        let event: RawEvent
        let outcomes: [Ledger.Outcome]
        let error: String?
    }

    /// 处理所有待识别的截图（打开 App 时调用）
    func processAll() async {
        if running { again = true; return }
        running = true
        defer { running = false }
        guard !AppSettings.apiKey.isEmpty else { return }
        repeat {
            again = false
            let ctx = Storage.container.mainContext
            let pending = RawEvent.pending, failed = RawEvent.failed, maxA = RawEvent.maxAttempts
            let todo = (try? ctx.fetch(FetchDescriptor<RawEvent>(
                predicate: #Predicate { $0.status == pending || ($0.status == failed && $0.attempts < maxA) },
                sortBy: [SortDescriptor(\.createdAt)]
            ))) ?? []
            for e in todo {
                let r = await process(e)
                Notifier.report(r)
            }
        } while again
    }

    /// 识别一条。timeout 用于快捷指令在后台运行时，系统只给几十秒。
    @discardableResult
    func process(_ e: RawEvent, timeout: TimeInterval = 90) async -> Result {
        let ctx = Storage.container.mainContext
        let key = AppSettings.apiKey
        guard !key.isEmpty else {
            return Result(event: e, outcomes: [], error: "还没填 DeepSeek API Key")
        }
        guard let url = e.imageURL, FileManager.default.fileExists(atPath: url.path) else {
            e.status = RawEvent.noTx
            e.lastError = "截图文件不见了"
            e.processedAt = .now
            try? ctx.save()
            return Result(event: e, outcomes: [], error: nil)
        }

        let start = Date()
        let model = AppSettings.model
        e.attempts += 1
        e.model = model
        try? ctx.save()
        do {
            let parts = try ImagePrep.prepareForUpload(url)
            let client = DeepSeekClient(baseURL: AppSettings.baseURL, apiKey: key, timeout: timeout)
            let out = try await client.chat(
                model: model, system: Prompts.system,
                user: Prompts.user(createdAt: e.createdAt, imageParts: parts.count), imagesBase64: parts
            )
            e.latencyMs = Int(Date().timeIntervalSince(start) * 1000)
            e.processedAt = .now
            e.resultJson = out
            if out.isEmpty {
                // 模型偶尔对非付款页返回空内容，重试也一样，按“没有交易”处理
                e.status = RawEvent.noTx
                e.lastError = "模型返回空内容"
                try? ctx.save()
                return Result(event: e, outcomes: [], error: nil)
            }
            guard let json = try JSONSerialization.jsonObject(with: Data(out.utf8)) as? [String: Any] else {
                throw DeepSeekError(message: "模型输出不是 JSON：\(out.prefix(200))")
            }
            let outcomes = try Ledger.apply(ctx, raw: e, result: json)
            e.status = outcomes.isEmpty ? RawEvent.noTx : RawEvent.done
            e.lastError = nil
            try? ctx.save()
            return Result(event: e, outcomes: outcomes, error: nil)
        } catch {
            e.status = RawEvent.failed
            e.lastError = error.localizedDescription
            e.latencyMs = Int(Date().timeIntervalSince(start) * 1000)
            try? ctx.save()
            return Result(event: e, outcomes: [], error: error.localizedDescription)
        }
    }

    /// 保存一张图并建一条识别记录
    func enqueue(imageData: Data, source: String) throws -> RawEvent {
        let jpeg = ImagePrep.jpegData(from: imageData) ?? imageData
        let name = try Storage.saveImage(jpeg, prefix: source == "back_tap" ? "" : "import-")
        let e = RawEvent(source: source, imageName: name)
        let ctx = Storage.container.mainContext
        ctx.insert(e)
        try ctx.save()
        return e
    }
}
