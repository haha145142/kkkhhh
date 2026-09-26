package com.goutoujunshi.keyboard

import com.goutoujunshi.keyboard.capture.ChatSnapshot
import com.goutoujunshi.keyboard.capture.GoutouAnalysis
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    const val BUILTIN_GATEWAY = "https://fund-ai-gateway.276353662.workers.dev"
    const val DEFAULT_MODEL = "deepseek-chat"

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    fun analyze(config: Config, snapshot: ChatSnapshot, action: String): Result<GoutouAnalysis> = runCatching {
        require(snapshot.messages.isNotEmpty()) { "没有读取到可分析的聊天内容" }

        val system = PromptCore.buildChatAnalysis(action)
        val user = buildString {
            append("当前会话：").append(snapshot.title ?: "未知").append('\n')
            append("来源：").append(snapshot.source).append('\n')
            append("最近聊天：").append('\n')
            append(snapshot.compact())
        }

        val raw = request(config, system, user)
        val jsonText = extractJsonObject(raw)
        val out = JSONObject(jsonText)
        val repliesJson = out.optJSONArray("replies") ?: JSONArray()
        val replies = buildList {
            for (i in 0 until minOf(3, repliesJson.length())) {
                val text = repliesJson.optString(i).trim()
                if (text.isNotBlank()) add(text)
            }
        }

        if (replies.isEmpty()) {
            error("模型没有返回可用的候选回复：${raw.take(500)}")
        }

        GoutouAnalysis(
            intent = out.optString("intent", "未知"),
            emotion = out.optString("emotion", "未知"),
            strategy = out.optString("strategy", "先观察反馈"),
            replies = replies
        )
    }

    fun chat(config: Config, systemPrompt: String, userPrompt: String): Result<String> =
        runCatching { request(config, systemPrompt, userPrompt) }

    fun test(config: Config): Result<String> = runCatching {
        request(config, "你是连接测试助手，只回复：狗头在线。", "连接测试")
            .ifBlank { error("模型服务返回空内容") }
    }

    private fun request(config: Config, systemPrompt: String, userPrompt: String): String {
        val base = config.baseUrl.trim().trimEnd('/').ifBlank { BUILTIN_GATEWAY }
        val builtIn = base.equals(BUILTIN_GATEWAY, ignoreCase = true) ||
            (base.contains("workers.dev", ignoreCase = true) && config.apiKey.isBlank())

        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/chat/completions"
        }

        val payload = JSONObject()
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )
            .put("temperature", 0.72)
            .put("max_tokens", 900)

        if (!builtIn) {
            payload.put("model", config.model.ifBlank { DEFAULT_MODEL })
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 40000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank() && !builtIn) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }

        conn.outputStream.use {
            it.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        conn.disconnect()

        if (raw.isBlank()) {
            error("模型服务返回空内容（HTTP ${code}）")
        }
        if (code !in 200..299) {
            error("模型服务 HTTP ${code}：${raw.take(700)}")
        }

        val trimmed = raw.trim()

        return try {
            val root = JSONObject(trimmed)
            when {
                root.optString("content").isNotBlank() -> root.optString("content")
                root.optJSONObject("message")?.optString("content").orEmpty().isNotBlank() ->
                    root.optJSONObject("message")!!.optString("content")
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .isNotBlank() ->
                    root.optJSONArray("choices")!!.optJSONObject(0)!!
                        .optJSONObject("message")!!.optString("content")
                root.optString("text").isNotBlank() -> root.optString("text")
                else -> trimmed
            }
        } catch (_: Exception) {
            trimmed
        }.trim()
    }

    private fun extractJsonObject(raw: String): String {
        val cleaned = raw.trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            error("模型返回不是有效 JSON：" + raw.take(600))
        }
        return cleaned.substring(start, end + 1)
    }
}
