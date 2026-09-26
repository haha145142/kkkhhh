package com.goutoujunshi.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class SettingsActivity : android.app.Activity() {
    private val prefs by lazy { getSharedPreferences("goutoujunshi", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "🐶 狗头军师"
            textSize = 28f
        })

        root.addView(TextView(this).apply {
            text = "安卓微信版：打开微信聊天 → 右上角小狗 → 点“分析当前聊天” → 选择回复并复制/填入。不会自动发送。"
            textSize = 15f
            setPadding(0, 12, 0, 20)
        })

        val base = field(
            "模型线路（默认使用狗头军师内置线路）",
            prefs.getString("baseUrl", ApiClient.BUILTIN_GATEWAY)
                ?: ApiClient.BUILTIN_GATEWAY
        )

        val key = field(
            "备用 API Key（可留空）",
            prefs.getString("apiKey", "") ?: ""
        )
        key.inputType = 0x00000081

        val model = field(
            "备用模型名",
            prefs.getString("model", ApiClient.DEFAULT_MODEL)
                ?: ApiClient.DEFAULT_MODEL
        )

        root.addView(base)
        root.addView(key)
        root.addView(model)

        root.addView(button("保存配置") {
            save(base, key, model)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("测试狗头军师模型") {
            save(base, key, model)
            val config = ApiClient.Config(
                base.text.toString().trim(),
                key.text.toString().trim(),
                model.text.toString().trim()
            )

            Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()

            executor.execute {
                val result = ApiClient.test(config)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        result.fold(
                            { "模型返回：" + it },
                            { "失败：" + (it.message ?: "unknown") }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(button("开启 / 检查微信聊天读取") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(button("允许悬浮窗") {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + packageName)
                    )
                )
            }
        })

        root.addView(button("启用狗头军师输入法（可选）") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        root.addView(TextView(this).apply {
            text = "\n使用说明\n" +
                "1. 开启“狗头军师聊天读取”无障碍权限。\n" +
                "2. 允许“显示在其他应用上层”。\n" +
                "3. 打开微信并进入具体聊天窗口。\n" +
                "4. 右上角出现小狗头像后，点击它。\n" +
                "5. 点“分析当前聊天”，等待狗头军师给出 3 条候选回复。\n" +
                "6. 点“复制”后到微信粘贴，或点“填入”尝试直接填入输入框。\n\n" +
                "模型：默认走你之前项目中的狗头军师模型网关；也可填写自己的兼容 API。\n" +
                "隐私：只在你点击分析时，把识别到的聊天文字发送到当前模型线路；不会自动发送消息。\n" +
                "关闭悬浮窗：展开面板后点 ×，当前聊天内会保持关闭；切换到新的微信聊天时重新出现。"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 18, 0, 10)
        })

        setContentView(scroll)
    }

    private fun save(base: EditText, key: EditText, model: EditText) {
        prefs.edit()
            .putString(
                "baseUrl",
                base.text.toString().trim().ifBlank { ApiClient.BUILTIN_GATEWAY }
            )
            .putString("apiKey", key.text.toString().trim())
            .putString(
                "model",
                model.text.toString().trim().ifBlank { ApiClient.DEFAULT_MODEL }
            )
            .apply()
    }

    private fun field(hint: String, value: String): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun button(title: String, action: () -> Unit) =
        Button(this).apply {
            text = title
            setOnClickListener { action() }
        }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
