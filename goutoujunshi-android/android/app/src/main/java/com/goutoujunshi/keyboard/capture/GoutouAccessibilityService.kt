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
    private var current: ChatSnapshot? = null
    private var lastSignature = ""
    private var lastTitle: String? = null
    private var hiddenByUser = false
    private var analyzing = false
    private val prefs by lazy { getSharedPreferences("goutoujunshi", MODE_PRIVATE) }

    private val targetPkg = "com.tencent.mm"
    private val wechatBubbleId = "com.tencent.mm:id/bkl"
    private val ocr = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        overlay = GoutouOverlay(this).also { ov ->
            ov.onAnalyze = { analyzeCurrent("怎么回") }
            ov.onMode = { mode -> analyzeCurrent(mode) }
            ov.onCopy = { copyText(it) }
            ov.onFill = { fillText(it) }
            ov.onHide = {
                hiddenByUser = true
                ov.hide()
            }
            ov.onCollapse = { }
        }

        main.postDelayed({ captureActive() }, 600)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val root = rootInActiveWindow ?: run {
            overlay?.hide()
            return
        }

        val pkg = root.packageName?.toString() ?: run {
            overlay?.hide()
            return
        }

        if (pkg != targetPkg) {
            overlay?.hide()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            hiddenByUser = false
            current = null
            lastSignature = ""
            lastTitle = null
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
        captureRunnable?.let(main::removeCallbacks)
        val r = Runnable { captureActive() }
        captureRunnable = r
        main.postDelayed(r, 450)
    }

    /**
     * WeChat-specific detection restored from the previous working architecture:
     * only a real message bubble node is treated as an opened conversation.
     * The bubble can expose text or hide it; both cases are handled.
     */
    private fun extractWeChat(root: AccessibilityNodeInfo): ChatSnapshot? {
        val width = resources.displayMetrics.widthPixels
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        val rows = ArrayList<CapturedMessage>()
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false
        var guard = 0

        while (stack.isNotEmpty() && guard++ < 8000) {
            val node = stack.removeLast()
            val id = node.viewIdResourceName

            if (id == wechatBubbleId) {
                isChat = true
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                firstBubbleTop = minOf(firstBubbleTop, bounds.top)

                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    val side = if (bounds.centerX() > width / 2) "me" else "other"
                    rows += CapturedMessage(
                        side = side,
                        text = text.take(500),
                        y = bounds.top,
                        xCenter = bounds.centerX()
                    )
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }
        }

        if (!isChat) return null

        rows.sortBy { it.y }

        val title = findWeChatTitle(root, firstBubbleTop) ?: lastTitle
        lastTitle = title

        return ChatSnapshot(
            packageName = targetPkg,
            title = title,
            messages = rows.takeLast(24),
            source = "wechat-accessibility"
        )
    }

    private fun findWeChatTitle(
        root: AccessibilityNodeInfo,
        firstBubbleTop: Int
    ): String? {
        val dm = resources.displayMetrics
        val actionBarMax = minOf(
            firstBubbleTop,
            (dm.heightPixels * 0.15).toInt()
        )
        val minX = (dm.widthPixels * 0.25).toInt()
        val maxX = (dm.widthPixels * 0.75).toInt()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        var best: String? = null
        var bestTop = Int.MAX_VALUE
        var guard = 0

        while (stack.isNotEmpty() && guard++ < 5000) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim()

            if (
                !text.isNullOrBlank() &&
                text.length <= 24 &&
                !Regex("[，。？！、]").containsMatchIn(text)
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (
                    bounds.bottom in 1 until actionBarMax &&
                    bounds.centerX() in minX..maxX &&
                    bounds.top < bestTop
                ) {
                    bestTop = bounds.top
                    best = text
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }
        }

        return best
    }

    private fun captureActive() {
        val root = rootInActiveWindow ?: run {
            overlay?.hide()
            return
        }

        if (root.packageName?.toString() != targetPkg) {
            overlay?.hide()
            return
        }

        val snapshot = extractWeChat(root) ?: run {
            overlay?.hide()
            return
        }

        current = snapshot

        val signature = snapshot.title.orEmpty() + "|" + snapshot.compact()

        if (signature != lastSignature) {
            lastSignature = signature
            if (!hiddenByUser) {
                overlay?.showIdle(snapshot.title, snapshot.messages.size)
            }
        } else if (!hiddenByUser) {
            overlay?.showIdle(snapshot.title, snapshot.messages.size)
        }
    }

    private fun analyzeCurrent(action: String = "怎么回") {
        if (analyzing) return

        val snapshot = current
        if (snapshot == null) {
            overlay?.showStatus("还没有识别到当前微信聊天。")
            return
        }

        if (snapshot.messages.isEmpty()) {
            if (Build.VERSION.SDK_INT < 30) {
                overlay?.showStatus("微信隐藏了聊天文字；当前安卓版本不能截图识别。请复制聊天文字到输入法。")
                return
            }

            analyzing = true
            overlay?.showLoading("正在识别微信聊天画面…")
            captureForAnalysis(snapshot.title, action)
            return
        }

        requestModel(snapshot, action)
    }

    private fun captureForAnalysis(title: String?, action: String) {
        val root = rootInActiveWindow ?: run {
            analyzing = false
            overlay?.showStatus("当前微信页面已经离开。")
            return
        }

        if (Build.VERSION.SDK_INT < 30) {
            analyzing = false
            overlay?.showStatus("当前安卓版本不支持截图识别。")
            return
        }

        if (Build.VERSION.SDK_INT >= 34 && root.windowId >= 0) {
            takeScreenshotOfWindow(
                root.windowId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        handleBitmap(screenshot, title, action)
                    }

                    override fun onFailure(errorCode: Int) {
                        takeScreenshotFallback(action)
                    }
                }
            )
        } else {
            takeScreenshotFallback(action)
        }
    }

    private fun takeScreenshotFallback(action: String) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    handleBitmap(screenshot, current?.title, action)
                }

                override fun onFailure(errorCode: Int) {
                    analyzing = false
                    overlay?.showStatus(
                        "微信截图被系统拒绝（代码 " + errorCode +
                            "）。请复制聊天文字后再分析。"
                    )
                }
            }
        )
    }

    private fun handleBitmap(
        result: ScreenshotResult,
        title: String?,
        action: String
    ) {
        worker.execute {
            val hardwareBuffer = result.hardwareBuffer
            val bitmap = Bitmap
                .wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)

            hardwareBuffer.close()

            if (bitmap == null) {
                main.post {
                    analyzing = false
                    overlay?.showStatus("截图读取失败，请复制聊天文字。")
                }
                return@execute
            }

            ocr.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text: Text ->
                    val dm = resources.displayMetrics

                    val rows = text.textBlocks
                        .flatMap { block -> block.lines }
                        .mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null

                            if (
                                box.top < dm.heightPixels * 0.12 ||
                                box.top > dm.heightPixels * 0.82
                            ) {
                                return@mapNotNull null
                            }

                            val value = line.text.trim()
                            if (value.length < 2) return@mapNotNull null

                            val chrome = setOf(
                                "发送",
                                "更多",
                                "表情",
                                "语音",
                                "按住说话",
                                "相册",
                                "拍摄"
                            )
                            if (value in chrome) return@mapNotNull null

                            val side =
                                if (box.centerX() > dm.widthPixels * 0.55) "me"
                                else "other"

                            CapturedMessage(
                                side = side,
                                text = value.take(500),
                                y = box.top,
                                xCenter = box.centerX()
                            )
                        }
                        .sortedBy { it.y }
                        .takeLast(24)

                    if (rows.isEmpty()) {
                        analyzing = false
                        overlay?.showStatus(
                            "没有识别到聊天文字。可以把消息复制到输入框，再点分析。"
                        )
                    } else {
                        val snapshot = ChatSnapshot(
                            packageName = targetPkg,
                            title = title,
                            messages = rows,
                            source = "wechat-ocr"
                        )
                        current = snapshot
                        lastSignature = snapshot.title.orEmpty() + "|" + snapshot.compact()
                        requestModel(snapshot, action)
                    }

                    bitmap.recycle()
                }
                .addOnFailureListener(mainExecutor) { error ->
                    analyzing = false
                    overlay?.showStatus(
                        "OCR 失败：" + (error.localizedMessage ?: "unknown")
                    )
                    bitmap.recycle()
                }
        }
    }

    private fun requestModel(snapshot: ChatSnapshot, action: String) {
        if (analyzing) return
        analyzing = true
        overlay?.showLoading()

        val baseUrl = prefs.getString(
            "baseUrl",
            ApiClient.BUILTIN_GATEWAY
        ) ?: ApiClient.BUILTIN_GATEWAY

        val apiKey = prefs.getString("apiKey", "").orEmpty()
        val model = prefs.getString(
            "model",
            ApiClient.DEFAULT_MODEL
        ) ?: ApiClient.DEFAULT_MODEL

        val config = ApiClient.Config(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model
        )

        worker.execute {
            val result = ApiClient.analyze(config, snapshot, action)

            main.post {
                analyzing = false
                result
                    .onSuccess { overlay?.showAnalysis(it) }
                    .onFailure {
                        overlay?.showStatus(
                            "生成失败：" + (it.message ?: "模型没有返回有效内容")
                        )
                    }
            }
        }
    }

    private fun copyText(text: String) {
        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("狗头军师回复", text)
        )
        overlay?.toast("已复制，可以粘贴到微信。")
    }

    private fun fillText(text: String) {
        val root = rootInActiveWindow ?: run {
            copyText(text)
            return
        }

        val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (input != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                overlay?.toast("已填入微信输入框。")
                overlay?.collapse()
                return
            }
        }

        copyText(text)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ocr.close()
        worker.shutdownNow()
        overlay?.hide()
        super.onDestroy()
    }
}
