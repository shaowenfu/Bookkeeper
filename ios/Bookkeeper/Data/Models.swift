import Foundation
import SwiftData

/// 一次截图或导入。识别失败可以重试，最多 3 次。
@Model
final class RawEvent {
    @Attribute(.unique) var id: UUID
    var createdAt: Date
    /// back_tap（快捷指令） / share（分享） / gallery（App 内导入）
    var source: String
    /// Documents/shots 下的文件名
    var imageName: String?
    /// pending / done / no_tx / failed
    var status: String
    var attempts: Int
    var lastError: String?
    var model: String?
    var resultJson: String?
    var latencyMs: Int?
    var processedAt: Date?

    init(source: String, imageName: String?, createdAt: Date = .now) {
        self.id = UUID()
        self.createdAt = createdAt
        self.source = source
        self.imageName = imageName
        self.status = RawEvent.pending
        self.attempts = 0
    }

    static let pending = "pending"
    static let done = "done"
    static let noTx = "no_tx"
    static let failed = "failed"
    static let maxAttempts = 3

    var isTodo: Bool { status == RawEvent.pending || (status == RawEvent.failed && attempts < RawEvent.maxAttempts) }

    var imageURL: URL? { imageName.map { Storage.shotsDir.appendingPathComponent($0) } }
}

/// 账本里的一笔。同一笔钱从多张截图进来时合并成一笔。
@Model
final class Tx {
    @Attribute(.unique) var id: UUID
    var createdAt: Date
    var updatedAt: Date
    var occurredAt: Date
    var timeEstimated: Bool
    var amountCents: Int
    /// expense / refund / income / transfer / repayment
    var direction: String
    var merchant: String?
    var app: String?
    var channel: String?
    var funding: String?
    var category: String?
    var isAutoDebit: Bool
    var needsReview: Bool
    /// confirmed / void
    var status: String
    var note: String?
    /// 来源的识别记录 id，逗号分隔（第一个是创建它的那条）
    var eventIds: String

    init(occurredAt: Date, timeEstimated: Bool, amountCents: Int, direction: String, eventId: UUID) {
        self.id = UUID()
        self.createdAt = .now
        self.updatedAt = .now
        self.occurredAt = occurredAt
        self.timeEstimated = timeEstimated
        self.amountCents = amountCents
        self.direction = direction
        self.isAutoDebit = false
        self.needsReview = false
        self.status = Tx.confirmed
        self.eventIds = eventId.uuidString
    }

    static let confirmed = "confirmed"
    static let voided = "void"

    var eventIdList: [String] { eventIds.split(separator: ",").map(String.init) }
    func link(_ id: UUID) {
        if !eventIdList.contains(id.uuidString) { eventIds += "," + id.uuidString }
    }
}

@Model
final class LineItem {
    @Attribute(.unique) var id: UUID
    var txId: UUID
    var name: String
    var qty: Double?
    var amountCents: Int?
    var rawEventId: UUID?
    var position: Int

    init(txId: UUID, name: String, qty: Double?, amountCents: Int?, rawEventId: UUID?, position: Int) {
        self.id = UUID()
        self.txId = txId
        self.name = name
        self.qty = qty
        self.amountCents = amountCents
        self.rawEventId = rawEventId
        self.position = position
    }
}

enum Storage {
    static var shotsDir: URL {
        let dir = URL.documentsDirectory.appendingPathComponent("shots", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @MainActor static let container: ModelContainer = {
        do {
            return try ModelContainer(for: RawEvent.self, Tx.self, LineItem.self)
        } catch {
            fatalError("数据库打不开：\(error)")
        }
    }()

    /// 保存一张截图，返回文件名
    static func saveImage(_ data: Data, prefix: String = "") throws -> String {
        let name = "\(prefix)\(Int(Date().timeIntervalSince1970 * 1000))-\(UUID().uuidString.prefix(6)).jpg"
        try data.write(to: shotsDir.appendingPathComponent(name), options: .atomic)
        return name
    }
}
