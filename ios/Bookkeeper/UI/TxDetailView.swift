import SwiftData
import SwiftUI

struct TxDetailView: View {
    @Bindable var tx: Tx
    @Environment(\.modelContext) private var ctx
    @Environment(\.dismiss) private var dismiss
    @Query private var items: [LineItem]
    @State private var amount = ""

    init(tx: Tx) {
        self.tx = tx
        let id = tx.id
        _items = Query(filter: #Predicate<LineItem> { $0.txId == id }, sort: \LineItem.position)
    }

    var body: some View {
        Form {
            Section("金额") {
                TextField("金额（元）", text: $amount)
                    .keyboardType(.decimalPad)
                    .onAppear { amount = Money.yuan(tx.amountCents) }
                Picker("类型", selection: $tx.direction) {
                    ForEach(Money.directions, id: \.self) { Text(Money.directionLabel($0)).tag($0) }
                }
                DatePicker("时间", selection: $tx.occurredAt)
            }
            Section("信息") {
                TextField("商户", text: optional($tx.merchant))
                Picker("分类", selection: optional($tx.category)) {
                    Text("未分类").tag("")
                    ForEach(Prompts.categories, id: \.self) { Text($0).tag($0) }
                }
                TextField("备注", text: optional($tx.note), axis: .vertical)
                LabeledContent("渠道", value: tx.channel ?? "—")
                LabeledContent("付款方式", value: tx.funding ?? "—")
                LabeledContent("App", value: tx.app ?? "—")
                Toggle("自动扣费", isOn: $tx.isAutoDebit)
                if tx.needsReview { Toggle("需核对", isOn: $tx.needsReview) }
            }
            if !items.isEmpty {
                Section("商品") {
                    ForEach(items) { it in
                        HStack {
                            Text(it.name)
                            if let q = it.qty { Text("×\(q.formatted())").foregroundStyle(.secondary) }
                            Spacer()
                            if let c = it.amountCents { Text("¥\(Money.yuan(c))").monospacedDigit() }
                        }
                    }
                }
            }
            Section {
                Button("删除这笔", role: .destructive) {
                    tx.status = Tx.voided
                    tx.updatedAt = .now
                    try? ctx.save()
                    dismiss()
                }
            }
        }
        .navigationTitle("¥\(Money.yuan(tx.amountCents))")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            if let c = Ledger.parseCents(amount), c > 0 { tx.amountCents = c }
            tx.updatedAt = .now
            try? ctx.save()
        }
    }

    private func optional(_ b: Binding<String?>) -> Binding<String> {
        Binding(get: { b.wrappedValue ?? "" }, set: { b.wrappedValue = $0.isEmpty ? nil : $0 })
    }
}
