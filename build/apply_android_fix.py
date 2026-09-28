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
