package com.goutoujunshi.keyboard.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.goutoujunshi.keyboard.ApiClient
import com.goutoujunshi.keyboard.overlay.GoutouOverlay
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class GoutouAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> main.post(command) }
    private val worker = Executors.newSingleThreadExecutor()
    private var overlay: GoutouOverlay? = null
    private var lastSignature = ""
    private var current: ChatSnapshot? = null
    private var analyzing = false

    private val prefs by lazy { getSharedPreferences("goutoujunshi", MODE_PRIVATE) }
    private val targetPkg = "com.tencent.mm"
    private val ocr = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = GoutouOverlay(this).also { ov ->
            ov.onAnalyze = { analyzeNow("怎么回") }
            ov.onMode = { mode -> analyzeNow(mode) }
            ov.onFill = { fillText(it) }
            ov.onHide = { overlay?.hide() }
        }
        main.postDelayed({ captureActive() }, 800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        if (pkg != targetPkg) {
            overlay?.showIdle("仅在微信中工作")
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> debounceCapture()
        }
    }

    private var captureRunnable: Runnable? = null
    private fun debounceCapture() {
        captureRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { captureActive() }
        captureRunnable = r
        main.postDelayed(r, 650)
    }

    private fun captureActive() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != targetPkg) return
        val nodeSnapshot = extractFromNodes(root)
        if (nodeSnapshot.messages.size >= 2) {
            acceptSnapshot(nodeSnapshot)
        } else if (Build.VERSION.SDK_INT >= 30) {
            overlay?.showStatus("正在读取聊天画面…")
            takeScreenShot(root)
        } else {
            overlay?.showStatus("当前安卓版本无法截图识别，请复制聊天文字到键盘")
        }
    }

    private fun acceptSnapshot(snapshot: ChatSnapshot) {
        val sig = snapshot.compact()
        if (sig == lastSignature && current != null) return
        lastSignature = sig
        current = snapshot
        overlay?.showIdle(snapshot.title ?: "微信会话")
    }

    private fun extractFromNodes(root: AccessibilityNodeInfo): ChatSnapshot {
        val dm = resources.displayMetrics
        val items = mutableListOf<CapturedMessage>()
        fun visit(n: AccessibilityNodeInfo) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val t = n.text?.toString()?.trim().orEmpty()
            val isLeaf = n.childCount == 0
            if (isLeaf && t.length >= 2 && r.bottom > dm.heightPixels * 0.12 && r.top < dm.heightPixels * 0.82) {
                val x = r.centerX()
                val side = if (x > dm.widthPixels * 0.55) "me" else "other"
                val banned = setOf("发送", "更多", "表情", "语音", "按住说话", "拍摄", "相册")
                if (t !in banned) items += CapturedMessage(side, t.take(500), r.top, x)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child ->
                    visit(child)
                    child.recycle()
                }
            }
        }
        visit(root)
        val msgs = items.sortedBy { it.y }
            .distinctBy { Triple(it.y / 8, it.xCenter / 40, it.text) }
            .takeLast(20)
        val title = items.firstOrNull { it.y < dm.heightPixels * 0.15 && it.text.length <= 24 }?.text
        return ChatSnapshot(targetPkg, title, msgs, "accessibility")
    }

    private fun takeScreenShot(root: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= 34 && root.windowId >= 0) {
            takeScreenshotOfWindow(root.windowId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    handleBitmap(screenshot)
                }
                override fun onFailure(errorCode: Int) {
                    takeDisplayScreenshot(mainExecutor)
                }
            })
        } else {
            takeDisplayScreenshot(mainExecutor)
        }
    }

    private fun takeDisplayScreenshot(executor: Executor) {
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                handleBitmap(screenshot)
            }
            override fun onFailure(errorCode: Int) {
                overlay?.showStatus("截图失败（代码 $errorCode），请复制聊天文字到键盘")
            }
        })
    }

    private fun handleBitmap(result: ScreenshotResult) {
        worker.execute {
            val hw = result.hardwareBuffer
            val bitmap = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
            hw.close()
            if (bitmap == null) {
                main.post { overlay?.showStatus("截图读取失败") }
                return@execute
            }

            ocr.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text: Text ->
                    val dm = resources.displayMetrics
                    val rows = text.textBlocks
                        .flatMap { block: Text.TextBlock -> block.lines }
                        .mapNotNull { line: Text.Line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            if (box.top < dm.heightPixels * 0.12 || box.top > dm.heightPixels * 0.80) return@mapNotNull null
                            val value = line.text.trim()
                            if (value.length < 2) return@mapNotNull null
                            val side = if (box.centerX() > dm.widthPixels * 0.55) "me" else "other"
                            CapturedMessage(side, value, box.top, box.centerX())
                        }
                        .sortedBy { it.y }
                        .takeLast(24)

                    if (rows.size >= 2) {
                        acceptSnapshot(ChatSnapshot(targetPkg, null, rows, "screenshot-ocr"))
                    } else {
                        overlay?.showStatus("OCR 没识别到足够聊天文字；请复制消息后在键盘中分析")
                    }
                    bitmap.recycle()
                }
                .addOnFailureListener(mainExecutor) { error ->
                    overlay?.showStatus("OCR 失败：\${error.localizedMessage ?: "unknown"}")
                    bitmap.recycle()
                }
        }
    }

    private fun analyzeNow(action: String) {
        val snapshot = current ?: run {
            overlay?.showStatus("还没有读到当前聊天")
            return
        }
        if (analyzing) return
        analyzing = true
        overlay?.showLoading()

        val cfg = ApiClient.Config(
            prefs.getString("baseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com",
            prefs.getString("apiKey", "") ?: "",
            prefs.getString("model", "deepseek-flash") ?: "deepseek-flash"
        )

        worker.execute {
            val result = ApiClient.analyze(cfg, snapshot, action)
            main.post {
                analyzing = false
                result.onSuccess { overlay?.showAnalysis(it) }
                    .onFailure { overlay?.showStatus("生成失败：\${it.message ?: "unknown"}") }
            }
        }
    }

    private fun fillText(text: String) {
        val root = rootInActiveWindow ?: return
        val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (input != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                overlay?.hide()
                return
            }
        }

        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("goutou_reply", text))
        overlay?.showStatus("已复制到剪贴板；请点微信输入框粘贴。")
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ocr.close()
        worker.shutdownNow()
        overlay?.hide()
        super.onDestroy()
    }
}
