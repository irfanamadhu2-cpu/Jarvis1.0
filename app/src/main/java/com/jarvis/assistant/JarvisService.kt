package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class AskMode { APPROVE, TEXT, HANDOFF }

/**
 * The "hands and eyes" of Jarvis. Android lets an accessibility service see the screen,
 * tap, swipe and type. The Agent (Agent.kt) is the brain that decides what to do.
 */
class JarvisService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisService? = null
        val history = StringBuilder()
        @Volatile var listener: ((String) -> Unit)? = null

        private val SECRET_FIELD = Regex(
            "otp|\\bpin\\b|passcode|password|cvv|cvc|card.?number|kata laluan",
            RegexOption.IGNORE_CASE
        )
    }

    @Volatile var running = false
    @Volatile var stopRequested = false
    @Volatile var shotScale = 1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var stopView: TextView? = null
    @Volatile private var stopRect = Rect()
    private val wm: WindowManager get() = getSystemService(WINDOW_SERVICE) as WindowManager

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this) { }
        log("Phone control is ON. Jarvis is ready, sir.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopRequested = true
        return super.onUnbind(intent)
    }

    fun log(line: String) {
        synchronized(history) { history.append(line).append('\n') }
        listener?.invoke(line)
    }

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
        }

    // ---------------------------------------------------------------- running a task

    fun runTask(task: String) {
        if (running) {
            log("Already working on a task. Press STOP first.")
            return
        }
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("api_key", "") ?: ""
        if (key.isBlank()) {
            log("Please save your API key first.")
            return
        }
        running = true
        stopRequested = false
        log("\nYou: $task")
        showStop()
        Thread {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(900)
                Agent(this, key) { line -> log(line) }.run(task)
            } finally {
                running = false
                hideStop()
            }
        }.start()
    }

    fun speak(text: String) {
        val t = tts ?: return
        val tamil = text.any { it in '\u0B80'..'\u0BFF' }
        t.setLanguage(if (tamil) Locale("ta", "IN") else Locale.getDefault())
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------------------------------------------------------------- eyes

    private fun captureScreen(): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            }
        )
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    /** Screenshot as base64 JPEG, shrunk so the longest side is at most 1024 px. */
    fun screenshotBase64(): String? {
        repeat(3) {
            val bmp = captureScreen()
            if (bmp != null) {
                val longest = maxOf(bmp.width, bmp.height)
                shotScale = if (longest > 1024) longest / 1024f else 1f
                val out = if (shotScale > 1f) {
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width / shotScale).toInt(),
                        (bmp.height / shotScale).toInt(),
                        true
                    )
                } else bmp
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }
            Thread.sleep(1200) // Android limits screenshots to about one per second
        }
        return null
    }

    fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString() ?: "unknown"

    /** Text/description/id of the clickable things under a screen point (used as a safety net). */
    fun labelAt(x: Int, y: Int): String {
        val out = StringBuilder()
        for (w in windows) {
            val root = w.root ?: continue
            collect(root, x, y, out)
        }
        return out.toString()
    }

    private fun collect(n: AccessibilityNodeInfo, x: Int, y: Int, out: StringBuilder) {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (!r.contains(x, y)) return
        if (n.isClickable || n.childCount == 0) {
            out.append(n.text ?: "").append(' ')
                .append(n.contentDescription ?: "").append(' ')
                .append(n.viewIdResourceName ?: "").append(" | ")
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collect(child, x, y, out)
        }
    }

    // ---------------------------------------------------------------- hands

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val latch = CountDownLatch(1)
        var ok = false
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    fun tap(x: Int, y: Int): Boolean = gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        gesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 350)

    /** Types into the focused field (Tamil and other Unicode text works). Refuses secret fields. */
    fun typeText(text: String): String {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "No text field is focused. Tap a text field first."
        val meta = "${node.hintText ?: ""} ${node.viewIdResourceName ?: ""} ${node.contentDescription ?: ""}"
        if (node.isPassword || SECRET_FIELD.containsMatchIn(meta)) {
            return "REFUSED: this looks like a password/PIN/OTP field. Use hand_to_user so the user types it."
        }
        val old = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old + text)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "Typed." else "Could not type into this field."
    }

    fun pressKey(key: String): String {
        return when (key) {
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "Home pressed." }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "Back pressed." }
            "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); "Recents opened." }
            "notifications" -> { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); "Notifications opened." }
            "enter" -> {
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val ok = node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
                if (ok) "Enter pressed." else "Enter did not work. Tap the on-screen search/send key instead."
            }
            else -> "Unknown key."
        }
    }

    fun openApp(query: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = query.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: return "No installed app matches '$query'."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Cannot launch that app."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            "Opened ${match.loadLabel(pm)}."
        } catch (e: Exception) {
            "Could not open the app (${e.message}). Press home and tap its icon instead."
        }
    }

    fun openLink(url: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            "Opened $url"
        } catch (e: ActivityNotFoundException) {
            "No app can open that link."
        } catch (e: Exception) {
            "Could not open link: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- user in the loop

    fun approve(description: String): Boolean =
        ask("Jarvis wants to:", description, AskMode.APPROVE) == "yes"

    /**
     * Shows a box over whatever app is open and waits for the user.
     * APPROVE = Approve/Reject. TEXT = type an answer. HANDOFF = user does something on screen, then taps Done.
     * Returns null if the user rejected/cancelled or nothing happened in 5 minutes.
     */
    fun ask(title: String, body: String, mode: AskMode): String? {
        val latch = CountDownLatch(1)
        var answer: String? = null
        var shown: View? = null
        onMain {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(12))
                background = rounded(Color.WHITE)
                elevation = dp(8).toFloat()
            }
            box.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = body
                textSize = 16f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(8), 0, dp(12))
            })
            val input = EditText(this)
            if (mode == AskMode.TEXT) box.addView(input)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(Button(this).apply {
                text = if (mode == AskMode.APPROVE) "Reject" else "Cancel"
                setOnClickListener { answer = null; latch.countDown() }
            })
            row.addView(Button(this).apply {
                text = when (mode) {
                    AskMode.APPROVE -> "Approve"
                    AskMode.TEXT -> "Send"
                    AskMode.HANDOFF -> "Done"
                }
                setOnClickListener {
                    answer = if (mode == AskMode.TEXT) input.text.toString() else "yes"
                    latch.countDown()
                }
            })
            box.addView(row)

            val handoff = mode == AskMode.HANDOFF
            val lp = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                if (handoff) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                } else 0,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (handoff) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                if (handoff) y = dp(48)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            wm.addView(box, lp)
            shown = box
        }
        latch.await(5, TimeUnit.MINUTES)
        onMain {
            shown?.let { v ->
                try { wm.removeView(v) } catch (e: Exception) { }
            }
        }
        return answer
    }

    // ---------------------------------------------------------------- big red STOP button

    private fun showStop() {
        onMain {
            if (stopView != null) return@onMain
            val tv = TextView(this).apply {
                text = "■ STOP"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(Color.RED)
                setOnClickListener {
                    stopRequested = true
                    log("Stopped by you.")
                    hideStop()
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(8)
                y = dp(48)
            }
            wm.addView(tv, lp)
            stopView = tv
            tv.post {
                val loc = IntArray(2)
                tv.getLocationOnScreen(loc)
                stopRect = Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height)
            }
        }
    }

    fun hideStop() {
        onMain {
            stopView?.let { v ->
                try { wm.removeView(v) } catch (e: Exception) { }
            }
            stopView = null
        }
    }

    /** Jarvis must never press the user's own STOP button. */
    fun isStopButton(x: Int, y: Int): Boolean = stopRect.contains(x, y)
}
