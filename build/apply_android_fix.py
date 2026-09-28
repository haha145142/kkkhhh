from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "upstream"
SRC = ROOT / "integrations" / "jev_android" / "app" / "src" / "main" / "java" / "com" / "jev" / "probe"
JEV = SRC / "jev"
CAP = SRC / "capture"

def replace_once(path, old, new):
    s = path.read_text(encoding="utf-8")
    if s.count(old) != 1:
        raise SystemExit(f"Expected exactly one match in {path}")
    path.write_text(s.replace(old, new), encoding="utf-8")

capture = CAP / "ChatCaptureService.kt"
replace_once(
    capture,
    "private val adapters = listOf(QQAdapter(), XAdapter(), FeishuAdapter()).associateBy { it.pkg }",
    """private val adapters = listOf(
        WeChatAdapter(),
        QQAdapter(),
        XAdapter(),
        FeishuAdapter()
    ).associateBy { it.pkg }"""
)

unified = Path(__file__).resolve().parent / "UnifiedLovePrompt.kt"
(JEV / "UnifiedLovePrompt.kt").write_text(unified.read_text(encoding="utf-8"), encoding="utf-8")

questions = JEV / "JevQuestions.kt"
replace_once(
    questions,
    'const val BACKGROUND_NOTE =\n        " Facts given in background are provided context, not off-topic."',
    'const val BACKGROUND_NOTE =\n        " Facts given in background are provided context, not off-topic." + UnifiedLovePrompt.JEV_NOTE'
)

reply = JEV / "ReplyClient.kt"
s = reply.read_text(encoding="utf-8")
start = s.index("    fun draft(")
end = s.index("    /**", start)
new_draft = """    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null,
              judgment: Analysis? = null): List<String> {
        if (GoutouGuidance.explicitBoundary(snapshot)) return emptyList()
        val sys = UnifiedLovePrompt.SYSTEM + GoutouGuidance.draftRules +
            "只输出一个 JSON 对象，不要解释，不要输出 Markdown。"
        val user = UnifiedLovePrompt.replyUser(snapshot, relationship, ctx, judgment)
        return parseThree(chat(sys, user, temperature = 0.8))
    }

"""
s = s[:start] + new_draft + s[end:]

old_parse = """                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())"""
new_parse = """                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    when (item) {
                        is JSONObject -> {
                            val text = item.optString("text").trim()
                            if (text.isNotBlank()) out.add(text)
                        }
                        is String -> out.add(item.trim())
                    }
                }"""
replace_once(reply, old_parse, new_parse)

marker = """        // Fallback: split lines."""
idx = s.rfind(marker)
if idx < 0:
    raise SystemExit("ReplyClient fallback marker not found")
object_fallback = """        try {
            val obj = JSONObject(content.trim())
            val arr = obj.optJSONArray("replies")
            if (arr != null) {
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val text = arr.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                    if (text.isNotBlank()) out.add(text)
                }
                if (out.isNotEmpty()) return out.distinct().take(3)
            }
        } catch (_: Exception) { }
"""
s = s[:idx] + object_fallback + s[idx:]
reply.write_text(s, encoding="utf-8")

print("Android patch applied")


# ---- WeChat capture fix -------------------------------------------------
# The upstream build shipped the WeChat adapter but did not register it in
# ChatCaptureService, and explicitly treated WeChat as unsupported. Register
# it, keep the bubble visible in WeChat, and use per-bubble OCR when WeChat's
# node tree hides message text.

replace_once(
    capture,
    """private val adapters = listOf(
        WeChatAdapter(),
        QQAdapter(),
        XAdapter(),
        FeishuAdapter()
    ).associateBy { it.pkg }""",
    """private val adapters = listOf(
        WeChatAdapter(),
        QQAdapter(),
        XAdapter(),
        FeishuAdapter()
    ).associateBy { it.pkg }"""
)

replace_once(
    capture,
    "                    fg == WECHAT_PACKAGE ||\n",
    ""
)

replace_once(
    capture,
    """        if (pkg == WECHAT_PACKAGE) {
            overlay?.toast("当前 Android 版无法截取微信聊天画面，暂不支持微信")
            return
        }
""",
    ""
)

# Replace the WeChat adapter with a version that retains bubble rectangles even
# when WeChat strips the text from the accessibility node. This lets the shared
# OCR path recognize one bubble at a time and infer me/other from position.
wechat = CAP / "ChatAppAdapter.kt"
ws = wechat.read_text(encoding="utf-8")
wstart = ws.index("class WeChatAdapter : ChatAppAdapter {")
wend = ws.index("\n/**\n * Mobile QQ", wstart)
new_wechat = """class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        val bubbleRects = ArrayList<BubbleRect>()
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 8000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0) {
                    isChat = true
                    if (b.top < firstBubbleTop) firstBubbleTop = b.top
                    val side = if (b.centerX() > width / 2) "me" else "other"
                    bubbleRects.add(BubbleRect(Rect(b), side))
                    if (!text.isNullOrBlank()) {
                        bubbles.add(Triple(b.top, b.centerX(), text))
                    }
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }

        if (!isChat) return null

        val title = findWeChatTitle(root, firstBubbleTop, width, res)
        if (bubbles.isEmpty()) {
            return ChatSnapshot(title, emptyList(), bubbleRects.sortedBy { it.rect.top })
        }

        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs, bubbleRects.sortedBy { it.rect.top })
    }

    companion object {
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
    }
}

"""
ws = ws[:wstart] + new_wechat + ws[wend:]
wechat.write_text(ws, encoding="utf-8")

settings = ROOT / "integrations" / "jev_android" / "app" / "src" / "main" / "java" / "com" / "jev" / "probe" / "SettingsActivity.kt"
ss = settings.read_text(encoding="utf-8")
old_help = "可见聊天画面的文字可用本地 OCR 识别；当前 Android 预览版无法截取微信聊天画面，暂不支持微信。"
new_help = "微信支持：聊天正文不可读时会自动按气泡截图 OCR；首次识别后请核对原文与说话人。"
if ss.count(old_help) != 1:
    raise SystemExit(f"Expected settings help text once, got {ss.count(old_help)}")
settings.write_text(ss.replace(old_help, new_help), encoding="utf-8")
print("WeChat fix added")
