import Foundation
import Security

/// 设置。API Key 存在钥匙串里，其他放 UserDefaults。
enum AppSettings {
    static let defaultBaseURL = "https://api.deepseek.com"
    static let defaultModel = "deepseek-flash"

    static var baseURL: String {
        get { UserDefaults.standard.string(forKey: "base_url") ?? defaultBaseURL }
        set {
            var v = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            while v.hasSuffix("/") { v.removeLast() }
            UserDefaults.standard.set(v.isEmpty ? defaultBaseURL : v, forKey: "base_url")
        }
    }

    static var model: String {
        get { UserDefaults.standard.string(forKey: "model") ?? defaultModel }
        set {
            let v = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            UserDefaults.standard.set(v.isEmpty ? defaultModel : v, forKey: "model")
        }
    }

    static var apiKey: String {
        get { Keychain.read("deepseek_api_key") ?? "" }
        set { Keychain.write("deepseek_api_key", newValue.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
}

enum Keychain {
    private static let service = "com.shaowenfu.bookkeeper"

    static func read(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ account: String, _ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        // 快捷指令在锁屏后也可能运行，解锁过一次之后就能读
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }
}
