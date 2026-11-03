package com.jarvis.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var taskInput: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Turn on phone control"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        val keyInput = EditText(this).apply {
            hint = "2. Paste your Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                prefs.edit().putString("api_key", keyInput.text.toString().trim()).apply()
                toast("Saved")
            }
        })

        taskInput = EditText(this).apply {
            hint = "3. Tell Jarvis what to do"
            minLines = 2
        }
        root.addView(taskInput)

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Mic: Tamil") { listen("ta-IN") })
        row.addView(btn("Mic: English") { listen("en-IN") })
        row.addView(btn("Run") { runTask(taskInput.text.toString()) })
        root.addView(row)

        root.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                JarvisService.instance?.stopRequested = true
                JarvisService.instance?.hideStop()
                JarvisService.instance?.log("Stopped by you.")
            }
        })

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        logView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (JarvisService.instance != null) {
            "Phone control is ON"
        } else {
            "Phone control is OFF - tap button 1 and turn on Jarvis"
        }
        synchronized(JarvisService.history) { logView.text = JarvisService.history.toString() }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        JarvisService.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        JarvisService.listener = null
    }

    private fun runTask(task: String) {
        if (task.isBlank()) return
        val svc = JarvisService.instance
        if (svc == null) {
            toast("Turn on phone control first (button 1)")
            return
        }
        svc.runTask(task.trim())
        taskInput.setText("")
    }

    private fun listen(lang: String) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        try {
            startActivityForResult(i, 42)
        } catch (e: Exception) {
            toast("Speech input is not available on this device")
        }
    }

    @Deprecated("Simple approach, fine for this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                taskInput.setText(spoken)
                runTask(spoken)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
