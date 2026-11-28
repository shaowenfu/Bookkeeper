import Foundation
import SwiftData

/// 与 Android 版 Exporter 的字段保持一致
@MainActor
enum Exporter {
    private static func stamp() -> String {
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd-HHmmss"; return f.string(from: .now)
    }

    static func csv(_ ctx: ModelContext) throws -> URL {
        let txs = try ctx.fetch(FetchDescriptor<Tx>(sortBy: [SortDescriptor(\.occurredAt)])).filter { $0.status == Tx.confirmed }
        let items = try ctx.fetch(FetchDescriptor<LineItem>(sortBy: [SortDescriptor(\.position)]))
        let byTx = Dictionary(grouping: items, by: \.txId)
        var out = "\u{FEFF}时间,金额,类型,商户,分类,渠道,付款方式,App,自动扣费,商品,备注\n"
        for t in txs {
            let goods = (byTx[t.id] ?? []).map { $0.name + ($0.amountCents.map { " ¥" + Money.yuan($0) } ?? "") }.joined(separator: "；")
            let cols: [String] = [
                Fmt.full.string(from: t.occurredAt), Money.yuan(t.amountCents), Money.directionLabel(t.direction),
                t.merchant ?? "", t.category ?? "", t.channel ?? "", t.funding ?? "", t.app ?? "",
                t.isAutoDebit ? "是" : "", goods, t.note ?? "",
            ]
            out += cols.map(esc).joined(separator: ",") + "\n"
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bookkeeper-\(stamp()).csv")
        try out.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    static func json(_ ctx: ModelContext) throws -> URL {
        let iso = ISO8601DateFormatter()
        let txs = try ctx.fetch(FetchDescriptor<Tx>(sortBy: [SortDescriptor(\.occurredAt)]))
        let items = try ctx.fetch(FetchDescriptor<LineItem>())
        let events = try ctx.fetch(FetchDescriptor<RawEvent>(sortBy: [SortDescriptor(\.createdAt)]))
        func n(_ v: Any?) -> Any { v ?? NSNull() }
        let obj: [String: Any] = [
            "exported_at": iso.string(from: .now),
            "platform": "ios",
            "transactions": txs.map { t in [
                "id": t.id.uuidString, "occurred_at": iso.string(from: t.occurredAt), "time_estimated": t.timeEstimated,
                "amount": Money.yuan(t.amountCents), "direction": t.direction, "merchant": n(t.merchant), "app": n(t.app),
                "channel": n(t.channel), "funding": n(t.funding), "category": n(t.category), "is_auto_debit": t.isAutoDebit,
                "needs_review": t.needsReview, "status": t.status, "note": n(t.note), "event_ids": t.eventIdList,
            ] as [String: Any] },
            "line_items": items.map { i in [
                "tx_id": i.txId.uuidString, "name": i.name, "qty": n(i.qty),
                "amount": n(i.amountCents.map { Money.yuan($0) }), "raw_event_id": n(i.rawEventId?.uuidString),
            ] as [String: Any] },
            "raw_events": events.map { e in [
                "id": e.id.uuidString, "created_at": iso.string(from: e.createdAt), "source": e.source, "status": e.status,
                "attempts": e.attempts, "last_error": n(e.lastError), "model": n(e.model), "latency_ms": n(e.latencyMs),
                "result": n(e.resultJson),
            ] as [String: Any] },
        ]
        let data = try JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bookkeeper-\(stamp()).json")
        try data.write(to: url)
        return url
    }

    private static func esc(_ s: String) -> String {
        s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
    }
}
