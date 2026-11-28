import SwiftData
import SwiftUI

struct EventsView: View {
    @Query(sort: \RawEvent.createdAt, order: .reverse) private var events: [RawEvent]

    var body: some View {
        NavigationStack {
            List {
                if events.isEmpty { Text("还没有识别记录").foregroundStyle(.secondary) }
                ForEach(events) { e in
                    NavigationLink { EventDetailView(event: e) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(Fmt.full.string(from: e.createdAt)).monospacedDigit()
                                Spacer()
                                Text(EventsView.statusLabel(e)).foregroundStyle(e.status == RawEvent.failed ? .red : .secondary)
                            }
                            Text(EventsView.sourceLabel(e.source) + (e.latencyMs.map { String(format: " · %.1f 秒", Double($0) / 1000) } ?? ""))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("识别记录")
        }
    }

    static func statusLabel(_ e: RawEvent) -> String {
        switch e.status {
        case RawEvent.pending: return "等待识别"
        case RawEvent.done: return "已记账"
        case RawEvent.noTx: return "没有交易"
        case RawEvent.failed: return e.attempts >= RawEvent.maxAttempts ? "失败" : "失败，待重试"
        default: return e.status
        }
    }

    static func sourceLabel(_ s: String) -> String {
        switch s {
        case "back_tap": return "轻点背面"
        case "share": return "分享导入"
        case "gallery": return "相册导入"
        default: return s
        }
    }
}

enum Fmt {
    static let full: DateFormatter = { let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd HH:mm:ss"; return f }()
}

struct EventDetailView: View {
    let event: RawEvent
    @Environment(\.modelContext) private var ctx
    @State private var working = false

    var body: some View {
        List {
            Section {
                LabeledContent("状态", value: EventsView.statusLabel(event))
                LabeledContent("来源", value: EventsView.sourceLabel(event.source))
                LabeledContent("尝试次数", value: "\(event.attempts)")
                if let m = event.model { LabeledContent("模型", value: m) }
                if let err = event.lastError { Text(err).font(.caption).foregroundStyle(.red) }
                Button(working ? "识别中…" : "重新识别") {
                    working = true
                    Task {
                        event.status = RawEvent.pending
                        event.attempts = 0
                        try? ctx.save()
                        let r = await Recognizer.shared.process(event)
                        Notifier.report(r)
                        working = false
                    }
                }
                .disabled(working)
            }
            if let url = event.imageURL, let img = UIImage(contentsOfFile: url.path) {
                Section("截图") {
                    Image(uiImage: img).resizable().scaledToFit()
                }
            }
            if let j = event.resultJson {
                Section("模型输出") {
                    Text(j).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
        }
        .navigationTitle(Fmt.full.string(from: event.createdAt))
        .navigationBarTitleDisplayMode(.inline)
    }
}
