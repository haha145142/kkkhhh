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
    var onCopy: ((String) -> Unit)? = null
    var onFill: ((String) -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onCollapse: (() -> Unit)? = null

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var body: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var currentTitle: String? = null
    private var currentCount: Int = 0

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun bg(radius: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun ensure() {
        if (root != null) return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = bg(dp(20).toFloat(), 0xF7FFFFFF.toInt())
            elevation = dp(10).toFloat()
        }

        root = container

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            -3
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(100)
        }

        runCatching { wm.addView(container, params) }
            .onFailure { root = null; params = null }
    }

    private fun updateLayout(width: Int, height: Int, gravityValue: Int, x: Int, y: Int) {
        val r = root ?: return
        val p = params ?: return
        p.width = width
        p.height = height
        p.gravity = gravityValue
        p.x = x
        p.y = y
        runCatching { wm.updateViewLayout(r, p) }
    }

    fun showIdle(title: String?, count: Int = 0) {
        currentTitle = title
        currentCount = count
        expanded = false
        ensure()
        val r = root ?: return

        updateLayout(
            dp(56), dp(56),
            Gravity.TOP or Gravity.END,
            dp(10), dp(100)
        )

        r.removeAllViews()

        val b = TextView(ctx).apply {
            text = "🐶"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = bg(dp(18).toFloat(), 0xF7FFFFFF.toInt())
            setOnClickListener { expand(currentTitle, currentCount) }
        }
        r.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)))
    }

    private fun header(): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(ctx).apply {
            text = "🐶 狗头军师"
            textSize = 17f
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        addView(Button(ctx).apply {
            text = "—"
            setOnClickListener { collapse() }
        }, LinearLayout.LayoutParams(dp(48), dp(42)))
        addView(Button(ctx).apply {
            text = "×"
            setOnClickListener { onHide?.invoke() }
        }, LinearLayout.LayoutParams(dp(48), dp(42)))
    }

    fun expand(title: String?, count: Int = 0) {
        currentTitle = title ?: currentTitle
        currentCount = count
        ensure()
        val r = root ?: return
        expanded = true

        updateLayout(
            dp(350),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
            dp(8), dp(90)
        )

        r.removeAllViews()
        r.addView(header())

        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(ctx).apply { addView(body) }
        r.addView(scroll, LinearLayout.LayoutParams(dp(330), dp(430)))

        renderIdle(currentTitle, currentCount)
    }

    private fun renderIdle(title: String?, count: Int) {
        val b = body ?: return
        b.removeAllViews()

        b.addView(TextView(ctx).apply {
            text = (title?.let { "当前会话：$it\n" } ?: "") +
                if (count > 0) "已读到 $count 条消息" else "点击分析当前聊天"
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(10))
        })

        b.addView(Button(ctx).apply {
            text = "分析当前聊天 · 怎么回"
            setOnClickListener { onAnalyze?.invoke() }
        })

        b.addView(modeRow())

        b.addView(TextView(ctx).apply {
            text = "不会自动发送 · 复制后由你决定什么时候发"
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(2))
        })
    }

    private fun modeRow(): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        listOf("温柔", "幽默", "坚定", "分析").forEach { mode ->
            addView(Button(ctx).apply {
                text = mode
                textSize = 11f
                setOnClickListener { onMode?.invoke(mode) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
    }

    fun showLoading(message: String = "正在分析…") {
        if (!expanded) expand(currentTitle, currentCount)
        val b = body ?: return
        b.removeAllViews()
        b.addView(TextView(ctx).apply {
            text = message
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(dp(6), dp(36), dp(6), dp(36))
        })
    }

    fun showStatus(msg: String) {
        if (!expanded) expand(currentTitle, currentCount)
        val b = body ?: return
        b.removeAllViews()

        b.addView(TextView(ctx).apply {
            text = msg
            textSize = 14f
            setTextColor(0xFF444444.toInt())
            setPadding(dp(6), dp(20), dp(6), dp(18))
        })

        b.addView(Button(ctx).apply {
            text = "重新分析"
            setOnClickListener { onAnalyze?.invoke() }
        })
    }

    fun showAnalysis(a: GoutouAnalysis) {
        if (!expanded) expand(currentTitle, currentCount)
        val b = body ?: return
        b.removeAllViews()

        b.addView(TextView(ctx).apply {
            text = "对方在表达：" + a.intent
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(4))
        })

        b.addView(TextView(ctx).apply {
            text = "当前情绪：" + a.emotion
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(3), 0, dp(3))
        })

        b.addView(TextView(ctx).apply {
            text = "建议：" + a.strategy
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(3), 0, dp(10))
        })

        b.addView(TextView(ctx).apply {
            text = "怎么回"
            textSize = 14f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(2), 0, dp(6))
        })

        a.replies.take(3).forEachIndexed { i, reply ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(9), dp(8), dp(8))
                background = bg(
                    dp(12).toFloat(),
                    if (i == 0) 0xFFEAF2FF.toInt() else 0xFFF4F4F5.toInt()
                )
            }

            card.addView(TextView(ctx).apply {
                text = reply
                textSize = 15f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, dp(7))
            })

            val buttons = LinearLayout(ctx).apply { gravity = Gravity.END }

            buttons.addView(Button(ctx).apply {
                text = "复制"
                textSize = 12f
                setOnClickListener { onCopy?.invoke(reply) }
            }, LinearLayout.LayoutParams(dp(72), dp(44)))

            buttons.addView(Button(ctx).apply {
                text = "填入"
                textSize = 12f
                setOnClickListener { onFill?.invoke(reply) }
            }, LinearLayout.LayoutParams(dp(72), dp(44)))

            card.addView(buttons)
            b.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            )
        }

        b.addView(Button(ctx).apply {
            text = "重新分析当前聊天"
            setOnClickListener { onAnalyze?.invoke() }
        })
    }

    fun collapse() {
        val r = root ?: return
        expanded = false

        updateLayout(
            dp(56), dp(56),
            Gravity.TOP or Gravity.END,
            dp(10), dp(100)
        )

        r.removeAllViews()
        val b = TextView(ctx).apply {
            text = "🐶"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = bg(dp(18).toFloat(), 0xF7FFFFFF.toInt())
            setOnClickListener { expand(currentTitle, currentCount) }
        }
        r.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)))
        onCollapse?.invoke()
    }

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null
        body = null
        params = null
        expanded = false
    }
}
