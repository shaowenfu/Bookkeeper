import PhotosUI
import SwiftData
import SwiftUI

struct LedgerView: View {
    var openSettings: () -> Void

    @Query(filter: #Predicate<Tx> { $0.status == "confirmed" }, sort: \Tx.occurredAt, order: .reverse)
    private var txs: [Tx]
    @AppStorage("setup_done") private var setupDone = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var importing = false

    private static let dayFmt: DateFormatter = {
        let f = DateFormatter(); f.locale = Locale(identifier: "zh_CN"); f.dateFormat = "M月d日 EEEE"; return f
    }()

    private var monthTxs: [Tx] {
        let cal = Calendar.current
        return txs.filter { cal.isDate($0.occurredAt, equalTo: .now, toGranularity: .month) }
    }

    private var byDay: [(String, [Tx])] {
        var order: [String] = []
        var groups: [String: [Tx]] = [:]
        for t in txs {
            let k = Self.dayFmt.string(from: t.occurredAt)
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(t)
        }
        return order.map { ($0, groups[$0]!) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    let expense = monthTxs.filter { $0.direction == "expense" }.reduce(0) { $0 + $1.amountCents }
                    let refund = monthTxs.filter { $0.direction == "refund" }.reduce(0) { $0 + $1.amountCents }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("本月支出").font(.subheadline).foregroundStyle(.secondary)
                        Text("¥\(Money.yuan(expense - refund))").font(.largeTitle.weight(.semibold))
                        Text("共 \(monthTxs.count) 笔" + (refund > 0 ? "，已扣除退款 ¥\(Money.yuan(refund))" : ""))
                            .font(.caption).foregroundStyle(.secondary)
                        PhotosPicker(selection: $picked, maxSelectionCount: 20, matching: .images) {
                            Label(importing ? "正在导入…" : "导入截图", systemImage: "photo.badge.plus")
                        }
                        .buttonStyle(.bordered)
                        .disabled(importing)
                        .padding(.top, 6)
                    }
                }
                if !setupDone || AppSettings.apiKey.isEmpty {
                    Section {
                        Button(action: openSettings) {
                            Label(AppSettings.apiKey.isEmpty ? "还没填 DeepSeek API Key，去设置" : "还没设置“轻点背面”记账，去设置",
                                  systemImage: "exclamationmark.circle")
                        }
                        .foregroundStyle(.red)
                    }
                }
                if txs.isEmpty {
                    Text("还没有记录。付完款停在支付成功页，轻点两下手机背面试试。").foregroundStyle(.secondary)
                }
                ForEach(byDay, id: \.0) { day, list in
                    Section {
                        ForEach(list) { t in
                            NavigationLink { TxDetailView(tx: t) } label: { TxRow(tx: t) }
                        }
                    } header: {
                        let sum = list.reduce(0) { $0 + ($1.direction == "expense" ? $1.amountCents : $1.direction == "refund" ? -$1.amountCents : 0) }
                        HStack { Text(day); Spacer(); Text("支出 ¥\(Money.yuan(sum))") }
                    }
                }
            }
            .navigationTitle("截图记账")
            .onChange(of: picked) { _, items in
                guard !items.isEmpty else { return }
                importing = true
                Task {
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self) {
                            _ = try? Recognizer.shared.enqueue(imageData: data, source: "gallery")
                        }
                    }
                    picked = []
                    importing = false
                    await Recognizer.shared.processAll()
                }
            }
        }
    }
}

struct TxRow: View {
    let tx: Tx
    private static let timeFmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "HH:mm"; return f }()

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(tx.merchant ?? tx.app ?? "未知商户").fontWeight(.medium)
                    if tx.needsReview { Text("需核对").font(.caption2).foregroundStyle(.orange) }
                    if tx.isAutoDebit { Text("自动扣费").font(.caption2).foregroundStyle(.secondary) }
                }
                Text([Self.timeFmt.string(from: tx.occurredAt) + (tx.timeEstimated ? "（估）" : ""), tx.category, tx.channel ?? tx.app]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Text((tx.direction == "expense" ? "-" : tx.direction == "refund" || tx.direction == "income" ? "+" : "") + "¥" + Money.yuan(tx.amountCents))
                .monospacedDigit()
                .foregroundStyle(tx.direction == "expense" ? Color.primary : Color.green)
        }
    }
}
