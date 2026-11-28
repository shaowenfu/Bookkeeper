import SwiftData
import SwiftUI

struct SettingsView: View {
    @AppStorage("setup_done") private var setupDone = false
    @Environment(\.modelContext) private var ctx
    @State private var key = AppSettings.apiKey
    @State private var base = AppSettings.baseURL
    @State private var model = AppSettings.model
    @State private var saved = false
    @State private var notifAllowed = true
    @State private var exportURL: URL?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    step(1, "新建快捷指令", "打开“快捷指令”App → 右上角 + → 添加操作，搜“截屏”，加上“截屏”。")
                    step(2, "接上识别动作", "再添加操作，搜“截图记账”，选“识别截图记账”。它的“截图”参数会自动接上一步的截屏结果。给这个快捷指令起名“截图记账”。")
                    step(3, "设成轻点背面", "设置 → 辅助功能 → 触控 → 轻点背面 → 轻点两下 → 选“截图记账”。")
                    step(4, "试一下", "付完款停在支付成功页，轻点两下手机背面。几秒后会弹通知，可以直接“撤销”。")
                    Toggle("我已设置好", isOn: $setupDone)
                } header: {
                    Text("触发方式：轻点背面")
                } footer: {
                    Text("App 不需要常驻后台：每次轻点，系统会把截图交给它识别。第一次运行快捷指令时会问是否允许，选“始终允许”。")
                }

                Section("通知") {
                    if notifAllowed {
                        Label("已允许，记账后会弹通知", systemImage: "checkmark.circle").foregroundStyle(.green)
                    } else {
                        Button("没有通知权限，去系统设置打开") {
                            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                        }
                    }
                }

                Section {
                    SecureField("DeepSeek API Key", text: $key)
                    TextField("接口地址", text: $base).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("模型（需支持看图）", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button(saved ? "已保存" : "保存") {
                        AppSettings.apiKey = key
                        AppSettings.baseURL = base
                        AppSettings.model = model
                        base = AppSettings.baseURL
                        model = AppSettings.model
                        saved = true
                        Task { await Recognizer.shared.processAll() }
                    }
                } header: {
                    Text("识别")
                } footer: {
                    Text("截图会整张发给模型识别。API Key 存在钥匙串里。")
                }

                Section("导出") {
                    Button("生成 CSV（账本）") { exportURL = try? Exporter.csv(ctx) }
                    Button("生成 JSON（含识别记录）") { exportURL = try? Exporter.json(ctx) }
                    if let u = exportURL {
                        ShareLink(item: u) { Label("分享 \(u.lastPathComponent)", systemImage: "square.and.arrow.up") }
                    }
                }

                Section("版本") {
                    LabeledContent("版本", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                    LabeledContent("构建号", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "")
                }
            }
            .navigationTitle("设置")
            .onChange(of: key) { _, _ in saved = false }
            .task { notifAllowed = await Notifier.notificationsAllowed() }
        }
    }

    private func step(_ n: Int, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.headline).frame(width: 24, height: 24).background(Circle().fill(Color.accentColor.opacity(0.15)))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fontWeight(.medium)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}
