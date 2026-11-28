import SwiftUI

struct ContentView: View {
    @AppStorage("setup_done") private var setupDone = false
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            LedgerView(openSettings: { tab = 2 })
                .tabItem { Label("账本", systemImage: "list.bullet.rectangle") }.tag(0)
            EventsView()
                .tabItem { Label("识别记录", systemImage: "photo.stack") }.tag(1)
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }.tag(2)
        }
        .onAppear {
            if !setupDone { tab = 2 }
            Notifier.requestPermission()
        }
    }
}
