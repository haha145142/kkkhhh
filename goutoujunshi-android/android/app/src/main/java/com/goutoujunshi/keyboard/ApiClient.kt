package com.goutoujunshi.keyboard

import com.goutoujunshi.keyboard.capture.ChatSnapshot
import com.goutoujunshi.keyboard.capture.GoutouAnalysis
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    fun analyze(config: Config, snapshot: ChatSnapshot, action: String): Result<GoutouAnalysis> = runCatching {
        require(config.apiKey.isNotBlank()) { "未填写 DeepSeek API Key" }
        require(config.model.isNotBlank()) { "未填写模型" }
        require(snapshot.messages.isNotEmpty()) { "没有读取到聊天内容" }
        val endpoint = normalizeEndpoint(config.baseUrl)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 35000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val system = """
你是“狗头军师”聊天副驾。你的任务是：读取用户提供的当前聊天记录，先判断对方在表达什么，再给出 3 条自然、可直接发送的候选回复。
规则：只根据可见聊天内容判断；把事实、推测、未知分开；不要读心；不要把可能说成一定。
回复必须符合真实聊天语气，简短、口语化，不写小作文。一次消息只完成一个主动作：承接、降压、调侃、轻推、邀约、澄清、收线。
明确拒绝或不适时，不继续推动。禁止羞辱、嫉妒操控、测试、威胁、跟踪、性施压、诈骗、冒充。
输出必须是 JSON：{"intent":"...","emotion":"...","strategy":"...","replies":["...","...","..."]}
不要 Markdown，不要额外解释。
""".trimIndent()
        val user = "本轮动作：$action\n当前会话标题：${snapshot.title ?: "未知"}\n读取来源：${snapshot.source}\n\n聊天：\n${snapshot.compact()}"
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("temperature", 0.7)
            .put("max_tokens", 700)
            .put("response_format", JSONObject().put("type", "json_object"))

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) error("DeepSeek API $code：$raw")

        val content = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val out = JSONObject(content)
        val repliesJson = out.optJSONArray("replies")
        val replies = buildList {
            if (repliesJson != null) for (i in 0 until minOf(3, repliesJson.length())) {
                val s = repliesJson.optString(i).trim()
                if (s.isNotBlank()) add(s)
            }
        }
        GoutouAnalysis(
            intent = out.optString("intent", "未知"),
            emotion = out.optString("emotion", "未知"),
            strategy = out.optString("strategy", "先观察反馈"),
            replies = replies
        )
    }

    fun chat(config: Config, systemPrompt: String, userPrompt: String): Result<String> = runCatching {
        require(config.apiKey.isNotBlank()) { "未填写 DeepSeek API Key" }
        require(config.model.isNotBlank()) { "未填写模型" }
        val endpoint = normalizeEndpoint(config.baseUrl)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 35000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPrompt)))
            .put("temperature", 0.7)
            .put("max_tokens", 700)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) error("DeepSeek API $code：$raw")
        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }

    fun test(config: Config): Result<String> = runCatching {
        val fake = ChatSnapshot("manual", "连接测试", listOf(com.goutoujunshi.keyboard.capture.CapturedMessage("other", "你好，在吗？")), "test")
        analyze(config, fake, "怎么回").getOrThrow().replies.firstOrNull() ?: "连接成功"
    }

    private fun normalizeEndpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/chat/completions"
        }
    }
}
