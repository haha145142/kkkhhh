package com.goutoujunshi.keyboard

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

class GoutouJunshiImeService : InputMethodService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suggestionBar: LinearLayout? = null
    private var loading: TextView? = null

    private val actions = listOf("怎么回", "温柔", "幽默", "坚定", "分析")

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 10)
            setBackgroundColor(0xFFF4F4F8.toInt())
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "🐶 狗头军师"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "⚙"
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@GoutouJunshiImeService, SettingsActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }, LinearLayout.LayoutParams(52, 50))
        root.addView(header)

        loading = TextView(this).apply {
            text = "先在上方设置 API，然后点一个动作"
            textSize = 14f
            setPadding(8, 8, 8, 8)
        }
        root.addView(loading)

        suggestionBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(suggestionBar) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (action in actions) {
            buttons.addView(Button(this).apply {
                text = action
                textSize = 13f
                setOnClickListener { runAction(action) }
            }, LinearLayout.LayoutParams(0, 50, 1f))
        }
        root.addView(buttons)
        return root
    }

    private fun runAction(action: String) {
        val before = currentInputConnection?.getTextBeforeCursor(1800, 0)?.toString().orEmpty()
        val after = currentInputConnection?.getTextAfterCursor(300, 0)?.toString().orEmpty()
        val context = (before + if (after.isNotBlank()) "\n[光标后]\n$after" else "").trim()
        if (context.isBlank()) {
            toast("输入框里还没有文字。先输入/粘贴聊天内容，再点 $action")
            return
        }
        loading?.text = "正在分析…"
        val prefs = getSharedPreferences("goutoujunshi", MODE_PRIVATE)
        val config = ApiClient.Config(
            prefs.getString("baseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com",
            prefs.getString("apiKey", "") ?: "",
            prefs.getString("model", "deepseek-flash") ?: "deepseek-flash"
        )
        executor.execute {
            val result = ApiClient.chat(
                config,
                PromptCore.build(action, context),
                if (action == "分析") "请分析下面这段聊天，不要直接替我发送。" else "请围绕下面聊天内容生成回复。"
            )
            mainHandler.post {
                result.onSuccess { showSuggestions(it) }
                    .onFailure { loading?.text = "生成失败：\${it.message}" }
            }
        }
    }

    private fun showSuggestions(raw: String) {
        loading?.text = "点下面任意一句，直接插入当前聊天框"
        suggestionBar?.removeAllViews()
        val lines = raw.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.length < 240 }
            .take(6)
        if (lines.isEmpty()) {
            addSuggestion(raw.take(240))
        } else {
            lines.forEach { addSuggestion(it.removePrefix("- ").removePrefix("• ")) }
        }
    }

    private fun addSuggestion(text: String) {
        suggestionBar?.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                currentInputConnection?.commitText(text, 1)
                toast("已插入")
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
