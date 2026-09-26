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
            setPadding(36, 42, 36, 36)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "🐶 狗头军师 AI 键盘"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "在微信、QQ 等聊天输入框中切换到本键盘，直接调用狗头军师生成回复。"
            textSize = 16f
            setPadding(0, 14, 0, 24)
        })

        val base = field("API Base URL（默认：https://api.deepseek.com）", prefs.getString("baseUrl", "https://api.deepseek.com")!!)
        val key = field("API Key", prefs.getString("apiKey", "")!!)
        key.inputType = 0x00000081
        val model = field("模型（默认：deepseek-flash）", prefs.getString("model", "deepseek-flash")!!)
        root.addView(base); root.addView(key); root.addView(model)

        root.addView(button("保存配置") {
            prefs.edit().putString("baseUrl", base.text.toString().trim())
                .putString("apiKey", key.text.toString().trim())
                .putString("model", model.text.toString().trim()).apply()
            Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("测试 API 连接") {
            val config = ApiClient.Config(base.text.toString().trim(), key.text.toString().trim(), model.text.toString().trim())
            executor.execute {
                val result = ApiClient.chat(config, "你是一个连接测试助手。只回复：连接成功。", "连接测试")
                runOnUiThread { Toast.makeText(this, result.fold({ "API：$it" }, { "失败：\${it.message}" }), Toast.LENGTH_LONG).show() }
            }
        })

        root.addView(button("启用 / 切换系统输入法") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        root.addView(button("开启“聊天读取 / 悬浮窗”") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "在列表里开启“狗头军师聊天读取”；同时允许“显示在其他应用上层”。", Toast.LENGTH_LONG).show()
        })

        root.addView(button("允许悬浮窗") {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }
        })

        root.addView(TextView(this).apply {
            text = "\n隐私：键盘只读取当前输入框附近文字用于本次 AI 请求；不会替你自动发送。API Key 仅存本机。"
            textSize = 14f
        })
        setContentView(scroll)
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 16f
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
    }
}
