package com.goutoujunshi.keyboard.capture

data class CapturedMessage(val side: String, val text: String, val y: Int = 0, val xCenter: Int = 0)

data class ChatSnapshot(
    val packageName: String,
    val title: String?,
    val messages: List<CapturedMessage>,
    val source: String
) {
    fun compact(): String = messages.takeLast(18).joinToString("\n") {
        (if (it.side == "me") "我" else "对方") + "：" + it.text
    }
}

data class GoutouAnalysis(
    val intent: String,
    val emotion: String,
    val strategy: String,
    val replies: List<String>
)
