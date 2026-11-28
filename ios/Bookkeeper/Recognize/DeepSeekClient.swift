import Foundation

struct DeepSeekError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// OpenAI 兼容的 chat/completions 接口，图片用 base64 data URL。
struct DeepSeekClient {
    let baseURL: String
    let apiKey: String
    var timeout: TimeInterval = 90

    func chat(model: String, system: String, user: String, imagesBase64: [String]) async throws -> String {
        do {
            return try await send(model: model, system: system, user: user, images: imagesBase64, jsonMode: true)
        } catch let e as HTTPStatusError where e.code == 400 {
            // 有的模型不支持 response_format，去掉再试一次
            return try await send(model: model, system: system, user: user, images: imagesBase64, jsonMode: false)
        }
    }

    private struct HTTPStatusError: Error { let code: Int; let body: String }

    private func send(model: String, system: String, user: String, images: [String], jsonMode: Bool) async throws -> String {
        var content: [[String: Any]] = [["type": "text", "text": user]]
        for b64 in images {
            content.append(["type": "image_url", "image_url": ["url": "data:image/jpeg;base64,\(b64)"]])
        }
        var body: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": content],
            ],
            "temperature": 0,
            "max_tokens": 4000,
        ]
        if jsonMode { body["response_format"] = ["type": "json_object"] }

        guard let url = URL(string: baseURL + "/chat/completions") else { throw DeepSeekError(message: "接口地址不对：\(baseURL)") }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(data: data, encoding: .utf8) ?? ""
        guard (200..<300).contains(code) else {
            if code == 400 && jsonMode { throw HTTPStatusError(code: code, body: text) }
            throw DeepSeekError(message: "HTTP \(code)：\(text.prefix(300))")
        }
        guard
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let choices = obj["choices"] as? [[String: Any]],
            let msg = choices.first?["message"] as? [String: Any]
        else { throw DeepSeekError(message: "返回格式不对：\(text.prefix(300))") }
        return Self.stripFences((msg["content"] as? String) ?? "")
    }

    static func stripFences(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            if let nl = t.firstIndex(of: "\n") { t = String(t[t.index(after: nl)...]) }
            if t.hasSuffix("```") { t = String(t.dropLast(3)) }
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
