package com.shaowenfu.bookkeeper.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 Chat Completions 调用（DeepSeek）。只用标准库，不引入 OkHttp。
 * 图片用 image_url + base64 data URL 传入。
 */
class DeepSeekClient(private val baseUrl: String, private val apiKey: String) {

    class ApiException(val code: Int, message: String) : IOException(message)

    /** 返回模型输出的文字（通常是 JSON 字符串）。 */
    fun chat(model: String, system: String, userText: String, imagesBase64Jpeg: List<String> = emptyList()): String {
        val userContent: Any = if (imagesBase64Jpeg.isEmpty()) {
            userText
        } else {
            JSONArray().apply {
                imagesBase64Jpeg.forEach { b64 ->
                    put(JSONObject().put("type", "image_url").put(
                        "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64").put("detail", "high"),
                    ))
                }
                put(JSONObject().put("type", "text").put("text", userText))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0)
            .put("max_tokens", 4000)
            .put("response_format", JSONObject().put("type", "json_object"))

        return try {
            post(body)
        } catch (e: ApiException) {
            // 有的模型/输入组合不支持 json_object，去掉后重试一次
            if (e.code == 400 && e.message.orEmpty().contains("response_format", ignoreCase = true)) {
                body.remove("response_format")
                post(body)
            } else throw e
        }
    }

    private fun post(body: JSONObject): String {
        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(code, "HTTP $code: ${text.take(500)}")
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
            return stripFences(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun stripFences(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
