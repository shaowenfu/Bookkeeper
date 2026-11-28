import Foundation
import SwiftData

/// 把一次识别结果写进账本。与 Android 版 Ledger.kt 规则一致：
/// 同金额、同方向、时间相差 15 分钟内（时间是估计的放宽到 30 分钟）合并成一笔，只补空缺字段。
@MainActor
enum Ledger {
    struct Outcome {
        let tx: Tx
        let merged: Bool
    }

    private static let directions: Set<String> = ["expense", "refund", "income", "transfer", "repayment"]

    static func apply(_ ctx: ModelContext, raw: RawEvent, result: [String: Any]) throws -> [Outcome] {
        guard let arr = result["transactions"] as? [[String: Any]] else { return [] }
        let pageApp = clean(result["app"]).flatMap { $0 == "其他" ? nil : $0 }
        var outcomes: [Outcome] = []
        var touched = Set<UUID>()

        for o in arr {
            guard let cents = parseCents(o["amount"]), cents > 0 else { continue }
            let direction = (o["direction"] as? String).flatMap { directions.contains($0) ? $0 : nil } ?? "expense"
            let parsed = parseTime(clean(o["time"]))
            let estimated = parsed == nil
            let occurredAt = parsed ?? raw.createdAt
            let confidence = (o["confidence"] as? NSNumber)?.doubleValue ?? 1.0
            let items = parseItems(o["items"])
            let window: TimeInterval = estimated ? 30 * 60 : 15 * 60
            let from = occurredAt.addingTimeInterval(-window), to = occurredAt.addingTimeInterval(window)
            let confirmed = Tx.confirmed
            let rawId = raw.id.uuidString

            let fd = FetchDescriptor<Tx>(predicate: #Predicate {
                $0.amountCents == cents && $0.direction == direction && $0.status == confirmed &&
                    $0.occurredAt >= from && $0.occurredAt <= to
            })
            let cand = try ctx.fetch(fd)
                .filter { !touched.contains($0.id) && !$0.eventIdList.contains(rawId) }
                .min { abs($0.occurredAt.timeIntervalSince(occurredAt)) < abs($1.occurredAt.timeIntervalSince(occurredAt)) }

            if let t = cand {
                if t.timeEstimated && !estimated { t.occurredAt = occurredAt }
                t.timeEstimated = t.timeEstimated && estimated
                t.merchant = t.merchant ?? clean(o["merchant"])
                t.app = t.app ?? pageApp
                t.channel = t.channel ?? clean(o["channel"])
                t.funding = t.funding ?? clean(o["funding"])
                t.category = t.category ?? clean(o["category"])
                t.isAutoDebit = t.isAutoDebit || ((o["is_auto_debit"] as? Bool) ?? false)
                t.updatedAt = .now
                t.link(raw.id)
                let tid = t.id
                let existing = try ctx.fetchCount(FetchDescriptor<LineItem>(predicate: #Predicate { $0.txId == tid }))
                if existing == 0 { insert(items, txId: t.id, rawId: raw.id, ctx) }
                touched.insert(t.id)
                outcomes.append(Outcome(tx: t, merged: true))
            } else {
                let t = Tx(occurredAt: occurredAt, timeEstimated: estimated, amountCents: cents, direction: direction, eventId: raw.id)
                t.merchant = clean(o["merchant"])
                t.app = pageApp
                t.channel = clean(o["channel"])
                t.funding = clean(o["funding"])
                t.category = clean(o["category"])
                t.isAutoDebit = (o["is_auto_debit"] as? Bool) ?? false
                t.needsReview = confidence < 0.6
                ctx.insert(t)
                insert(items, txId: t.id, rawId: raw.id, ctx)
                touched.insert(t.id)
                outcomes.append(Outcome(tx: t, merged: false))
            }
        }
        try ctx.save()
        return outcomes
    }

    private static func insert(_ items: [(String, Double?, Int?)], txId: UUID, rawId: UUID, _ ctx: ModelContext) {
        for (i, it) in items.enumerated() {
            ctx.insert(LineItem(txId: txId, name: it.0, qty: it.1, amountCents: it.2, rawEventId: rawId, position: i))
        }
    }

    static func parseCents(_ v: Any?) -> Int? {
        var s: String
        if let n = v as? NSNumber { s = n.stringValue } else if let t = v as? String { s = t } else { return nil }
        s = s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "¥", with: "")
            .replacingOccurrences(of: "￥", with: "").trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("-") || s.hasPrefix("+") { s.removeFirst() }
        guard !s.isEmpty, s != "null", let d = Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")) else { return nil }
        var x = d * 100
        var r = Decimal()
        NSDecimalRound(&r, &x, 0, .plain)
        return NSDecimalNumber(decimal: r).intValue
    }

    static func parseTime(_ s: String?) -> Date? {
        guard let s else { return nil }
        for p in ["yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"] {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = .current
            f.dateFormat = p
            if let d = f.date(from: s) { return d }
        }
        return nil
    }

    private static func parseItems(_ v: Any?) -> [(String, Double?, Int?)] {
        guard let arr = v as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let name = clean(o["name"]) else { return nil }
            return (name, (o["qty"] as? NSNumber)?.doubleValue, parseCents(o["amount"]))
        }
    }

    static func clean(_ v: Any?) -> String? {
        guard let s = (v as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty, s != "null" else { return nil }
        return s
    }
}
