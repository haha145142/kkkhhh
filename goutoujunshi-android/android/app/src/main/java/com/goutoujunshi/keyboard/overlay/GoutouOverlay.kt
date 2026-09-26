package com.goutoujunshi.keyboard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.goutoujunshi.keyboard.capture.GoutouAnalysis

class GoutouOverlay(private val ctx: Context) {
    var onAnalyze: (() -> Unit)? = null
    var onMode: ((String) -> Unit)? = null
    var onFill: ((String) -> Unit)? = null
    var onHide: (() -> Unit)? = null

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var body: LinearLayout? = null

    private fun bg(radius: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun ensure() {
        if (root != null) return
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = bg(dp(18).toFloat(), 0xF8FFFFFF.toInt())
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            TextView(ctx).apply {
                text = "🐶 狗头军师"
                textSize = 16f
                setTextColor(Color.BLACK)
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        header.addView(Button(ctx).apply {
            text = "×"
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(48), dp(42)))
        container.addView(header)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(body) }
        container.addView(scroll, LinearLayout.LayoutParams(dp(320), dp(360)))
        root = container
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            dp(340),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            -3
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(90)
        }
        runCatching { wm.addView(container, lp) }
    }

    fun showIdle(title: String?) {
        ensure()
        setBody(
            TextView(ctx).apply {
                text = (title?.let { "当前：$it\n" } ?: "") + "点击分析当前对话"
                textSize = 15f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            },
            Button(ctx).apply {
                text = "分析当前对话"
                setOnClickListener { onAnalyze?.invoke() }
            },
            modeRow()
        )
    }

    private fun modeRow(): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        listOf("怎么回", "温柔", "幽默", "坚定", "分析").forEach { mode ->
            addView(Button(ctx).apply {
                text = mode
                textSize = 11f
                setOnClickListener { onMode?.invoke(mode) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }

    fun showLoading() {
        ensure()
        setBody(TextView(ctx).apply {
            text = "正在读懂这段聊天…"
            textSize = 15f
            setPadding(dp(4), dp(30), dp(4), dp(30))
        })
    }

    fun showStatus(msg: String) {
        ensure()
        setBody(
            TextView(ctx).apply {
                text = msg
                textSize = 14f
                setPadding(dp(4), dp(18), dp(4), dp(18))
            },
            Button(ctx).apply {
                text = "重新分析"
                setOnClickListener { onAnalyze?.invoke() }
            }
        )
    }

    fun showAnalysis(a: GoutouAnalysis) {
        ensure()
        val views = mutableListOf<android.view.View>()
        views += TextView(ctx).apply {
            text = "对方意图：\${a.intent}"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(4))
        }
        views += TextView(ctx).apply {
            text = "情绪：\${a.emotion}"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        views += TextView(ctx).apply {
            text = "建议：\${a.strategy}"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        views += TextView(ctx).apply {
            text = "候选回复"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(6), 0, dp(4))
        }
        a.replies.take(3).forEachIndexed { i, r ->
            views += TextView(ctx).apply {
                text = "\${i + 1}. $r"
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = bg(
                    dp(12).toFloat(),
                    if (i == 0) 0xFFEAF1FF.toInt() else 0xFFF4F4F5.toInt()
                )
                setOnClickListener { onFill?.invoke(r) }
            }
        }
        views += Button(ctx).apply {
            text = "返回 / 重新分析"
            setOnClickListener { onAnalyze?.invoke() }
        }
        setBody(*views.toTypedArray())
    }

    private fun setBody(vararg views: android.view.View) {
        body?.removeAllViews()
        views.forEach {
            body?.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
            )
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        body = null
    }
}
