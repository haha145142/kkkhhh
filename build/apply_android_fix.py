from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "upstream"
SRC = ROOT / "integrations" / "jev_android" / "app" / "src" / "main" / "java" / "com" / "jev" / "probe"
JEV = SRC / "jev"
CAP = SRC / "capture"

def replace_once(path, old, new):
    s = path.read_text(encoding="utf-8")
    count = s.count(old)
    if count == 0 and new in s:
        return
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, got {count}")
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
        val contentIds = setOf(
            "com.tencent.mm:id/bkl",
            "com.tencent.mm:id/bkf",
            "com.tencent.mm:id/ao9",
            "com.tencent.mm:id/iof",
            "com.tencent.mm:id/bkm"
        )
        val rowIds = setOf(
            "com.tencent.mm:id/bn1",
            "com.tencent.mm:id/bot",
            "com.tencent.mm:id/bop",
            "com.tencent.mm:id/igc"
        )
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
            if (contentIds.contains(id) || rowIds.contains(id)) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.width() > 0 && b.height() > 0) {
                    isChat = true
                    if (b.top < firstBubbleTop) firstBubbleTop = b.top
                    val side = if (b.centerX() > width / 2) "me" else "other"
                    bubbleRects.add(BubbleRect(Rect(b), side))
                    if (!text.isNullOrBlank() && (contentIds.contains(id) || id == "com.tencent.mm:id/bkl")) {
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



# ---- Robust WeChat fallback --------------------------------------------
# Latest 1.4 upstream can no longer rely on WeChat's accessibility text.
# Keep the proven disguised-service path from 332_lab-jev-chat, but add a
# screenshot/OCR fallback that works even when bkl/text is completely hidden.

replace_once(
    capture,
    """    private var foregroundPkg: String? = null
""",
    """    private var foregroundPkg: String? = null
    private var lastWindowClass: String = ""
    private var wechatVisualQueued = false
"""
)

replace_once(
    capture,
    """        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
""",
    """        if (event == null) return
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        lastWindowClass = event.className?.toString() ?: lastWindowClass
        val type = event.eventType
"""
)

replace_once(
    capture,
    """        val adapter = adapters[pkg] ?: return
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources) ?: return
""",
    """        val adapter = adapters[pkg]
        if (adapter == null) {
            if (pkg == WECHAT_PACKAGE && prefs.ocrFallback && isLikelyWeChatChat(root)) {
                scheduleWeChatVisualCapture(root)
            }
            return
        }
        // Only act inside a chat window (the adapter returns null elsewhere).
        val rawSnapshot = adapter.extract(root, resources) ?: run {
            if (pkg == WECHAT_PACKAGE && prefs.ocrFallback && isLikelyWeChatChat(root)) {
                scheduleWeChatVisualCapture(root)
            }
            return
        }
"""
)

replace_once(
    capture,
    """    // ------------------------------------------------------------------ OCR
""",
    """    // ------------------------------------------------------------------ WeChat visual fallback

    /**
     * True when the foreground WeChat window looks like a chat thread even if
     * the message nodes have been completely hidden. The lower editable field
     * is a stronger signal than a generic search box; the known ChattingUI
     * activity name is accepted as an additional fast path.
     */
    private fun isLikelyWeChatChat(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName?.toString() != WECHAT_PACKAGE) return false
        if (lastWindowClass.contains("ChattingUI", ignoreCase = true)) return true
        val title = findTitleInActionBar(
            root, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.12, 0.88
        )
        // A real ChattingUI activity is enough. Otherwise use the lower input
        // as the primary signal; the title may itself be hidden by WeChat.
        val height = resources.displayMetrics.heightPixels
        val minY = (height * 0.42f).toInt()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            if (node.isVisibleToUser && node.isEditable) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.bottom > minY && b.width() >= 80 && b.height() >= 24) return true
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    /**
     * Coalesce WeChat's burst of content-changed events into one screenshot.
     * The shared ScreenCapture throttle/backoff handles OEM screenshot limits;
     * finishOcrSnapshot then deduplicates identical recognized transcripts.
     */
    private fun scheduleWeChatVisualCapture(root: AccessibilityNodeInfo) {
        if (wechatVisualQueued || ocrBusy || reviewPending || analyzing) return
        wechatVisualQueued = true
        main.postDelayed({
            wechatVisualQueued = false
            if (!prefs.enabled) return@postDelayed
            val live = rootInActiveWindow ?: return@postDelayed
            if (live.packageName?.toString() != WECHAT_PACKAGE) return@postDelayed
            if (!isLikelyWeChatChat(live)) return@postDelayed
            val title = findTitleInActionBar(
                live, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.12, 0.88
            )
            overlay?.showIdle(title)
            ocrCapture(title, emptyList(), WECHAT_PACKAGE, manual = false)
        }, 850)
    }

    // ------------------------------------------------------------------ OCR
"""
)

# Manual OCR is now explicitly allowed for WeChat; remove the old early return
# Whole-screen OCR should infer sender from bubble side when the visible layout
# is the usual left=incoming/right=outgoing WeChat arrangement.
capture_text = capture.read_text(encoding="utf-8")
start = capture_text.index("    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {")
end = capture_text.index("\n    /** Strip the read receipt", start)
new_group = """    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        data class Group(val text: String, val centerX: Double, val count: Int)
        val groups = ArrayList<Group>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        var sumX = 0.0
        var count = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                groups.add(Group(buf.toString(), if (count == 0) 0.0 else sumX / count, count))
                buf.setLength(0)
                sumX = 0.0
                count = 0
            }
        }

        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) flush()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            sumX += l.bounds.exactCenterX().toDouble()
            count++
            prev = l
        }
        flush()

        val width = resources.displayMetrics.widthPixels.toDouble()
        return groups.map { g ->
            val side = if (g.centerX > width / 2.0) "me" else "other"
            Msg(side, g.text)
        }
    }
"""
capture_text = capture_text[:start] + new_group + capture_text[end:]
capture.write_text(capture_text, encoding="utf-8")

# clear the queued flag in failure path as an extra safety guard
replace_once(
    capture,
    """                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
""",
    """                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    wechatVisualQueued = false
"""
)

print("Robust WeChat OCR fallback applied")


# ---- Bundled Goutoujunshi kernel + auto topic routing -----------------
builtin_src = Path(__file__).resolve().parent / "BuiltinLoveKnowledge.kt"
kernel_dst = SRC / "core" / "kb" / "BuiltinLoveKnowledge.kt"
kernel_dst.write_text(builtin_src.read_text(encoding="utf-8"), encoding="utf-8")

ctx = SRC / "core" / "kb" / "ContextBuilder.kt"
cs = ctx.read_text(encoding="utf-8")
if "BuiltinLoveKnowledge.forSnapshot(snapshot)" not in cs:
    old = """        // 3. Notes — always-on ones plus keyword hits.
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = enabled.filter { it.alwaysOn }
        val hits = matchNotes(enabled.filter { !it.alwaysOn }, snapshot)
"""
    new = """        // 3. System knowledge is always present; topic modules are auto-routed
        // from the current conversation. User notes are additional context.
        val builtin = BuiltinLoveKnowledge.forSnapshot(snapshot)
        val enabled = store.notes().filter { it.enabled }
        val alwaysOn = builtin.filter { it.alwaysOn } + enabled.filter { it.alwaysOn }
        val hits = builtin.filter { !it.alwaysOn } +
            matchNotes(enabled.filter { !it.alwaysOn }, snapshot)
"""
    if cs.count(old) != 1:
        raise SystemExit("ContextBuilder notes block not found")
    cs = cs.replace(old, new, 1)
    ctx.write_text(cs, encoding="utf-8")

ka = ROOT / "integrations" / "jev_android" / "app" / "src" / "main" / "java" / "com" / "jev" / "probe" / "KnowledgeActivity.kt"
ks = ka.read_text(encoding="utf-8")
if "import com.jev.probe.core.kb.BuiltinLoveKnowledge" not in ks:
    ks = ks.replace(
        "import com.jev.probe.core.kb.Contact",
        "import com.jev.probe.core.kb.BuiltinLoveKnowledge\nimport com.jev.probe.core.kb.Contact",
        1
    )
if "renderBuiltinKernel()" not in ks:
    ks = ks.replace(
        "        container.addView(tabs())\n        if (tab == 0) renderNotes() else renderContacts()",
        "        container.addView(tabs())\n        if (tab == 0) { renderBuiltinKernel(); renderNotes() } else renderContacts()",
        1
    )
    marker = "    // ----------------------------------------------------------------- notes\n"
    kernel_ui = String.raw"""    private fun renderBuiltinKernel() {
        val topics = BuiltinLoveKnowledge.allTopics()
        container.addView(card().apply {
            addView(text(
                "狗头军师系统知识内核 · 已内置 " + BuiltinLoveKnowledge.builtinCount() + " 个模块",
                15f, ink, bold = true
            ))
            addView(text(
                "这些不是你的私人笔记，而是每次分析都会参与的恋爱判断方法。系统会根据当前微信聊天自动匹配相关模块。",
                12f, sub
            ).apply { setPadding(0, dp(4), 0, dp(8)) })
            topics.forEach { t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(7), 0, dp(7))
                }
                row.addView(text(t.title, 13.5f, ink, bold = true))
                row.addView(text(t.content, 11.5f, sub).apply {
                    setPadding(0, dp(2), 0, 0)
                })
                addView(row)
            }
        })
    }

"""
    if (!ks.includes(marker)) throw new Error("KnowledgeActivity notes marker not found");
    ks = ks.replace(marker, kernel_ui + marker, 1)
    ks = ks.replace(
        "container.addView(emptyCard(\"还没有笔记。写点该记住的事实：习惯、忌口、项目代号、约定过的时间。\"))",
        "container.addView(emptyCard(\"你还没有私人笔记。系统知识内核已经内置；这里的笔记用于补充你自己和对方的具体事实。\"))",
        1
    )
    ka.write_text(ks, encoding="utf-8")

fi = ROOT / "integrations" / "jev_android" / "app" / "src" / "main" / "java" / "com" / "jev" / "probe" / "core" / "Prefs.kt"
ps = fi.read_text(encoding="utf-8")
ps = ps.replace(
    'const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"',
    'const val DEFAULT_REL = "关系未指定；from=me 是我，from=other 是对方；先根据聊天行为判断关系阶段"'
)
fi.write_text(ps, encoding="utf-8")

print("Bundled kernel and system knowledge routing applied")
