package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject

/** The brain: asks Claude what to do, runs the tools it picks, repeats until the task is done. */
class Agent(
    private val svc: JarvisService,
    private val apiKey: String,
    private val log: (String) -> Unit,
) {
    companion object {
        const val MAX_STEPS = 40
        private const val REJECTED = "User REJECTED this action. Do not do it. Tell the user and ask what to do next."

        // Safety net: even if Claude forgets to ask, tapping buttons with these words always needs approval.
        private val RISKY = Regex(
            "\\b(send|pay|payment|transfer|confirm|order|buy|purchase|delete|remove|call|submit|post|" +
                "book|checkout|withdraw|donate|subscribe|install|uninstall|erase|bayar|hantar)\\b",
            RegexOption.IGNORE_CASE
        )
        private val SENSITIVE_PKG = Regex(
            "pay|bank|wallet|paisa|phonepe|paytm|tngd|touchngo|maybank|cimb|rhb|hlb|grab|whatsapp|telegram|messaging",
            RegexOption.IGNORE_CASE
        )

        private val SYSTEM = """
You are J.A.R.V.I.S., the user's personal AI butler, operating their Android phone through tools.
Address the user as "sir". Reply in the language the user writes in (English, Tamil or Tanglish). Keep messages short.

How to work:
- Call screenshot first, then act step by step. Every action returns a fresh screenshot. Coordinates are in screenshot pixels.
- Prefer open_app (by app name) and open_link (https://, wa.me/<number>?text=..., tel:, sms:, geo:) to get to the right place fast.
- If open_app does not bring the app up, press home and tap the app icon.
- If you are missing information (who, how much, which account, what text), call ask_user. Never guess.
- If the screenshot is black, the app blocks screenshots (banks often do). Use hand_to_user and ask the user to do that step.
- A red "STOP" button on screen belongs to the user. Never tap it.

Safety rules (strict):
- For ANY irreversible action - sending a message, making a call, paying or transferring money, placing an order,
  posting, deleting - you MUST pass confirm_description on the final tap that triggers it, stating exactly what
  will happen (recipient, amount, message text). The user then approves or rejects.
- For payments, read the recipient name and amount from the screen and verify them before the confirm step.
- NEVER type PINs, passwords, OTPs, card numbers or CVVs. When the screen asks for one, call hand_to_user.
- If anything looks suspicious (unknown recipient, unexpected popup, scam-like page), stop and tell the user.
- When the task is finished, say what was done in one short sentence.
""".trimIndent()

        private fun prop(type: String, desc: String = ""): JSONObject {
            val o = JSONObject().put("type", type)
            if (desc.isNotEmpty()) o.put("description", desc)
            return o
        }

        private fun tool(
            name: String,
            desc: String,
            props: JSONObject = JSONObject(),
            required: List<String> = emptyList()
        ): JSONObject = JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(required))
            )

        private val CONFIRM = prop(
            "string",
            "REQUIRED when this action is irreversible (send/pay/call/order/delete). " +
                "Plain-language description of exactly what will happen."
        )

        private val TOOLS: JSONArray = JSONArray()
            .put(tool("screenshot", "Take a screenshot of the phone screen."))
            .put(
                tool(
                    "tap", "Tap at (x, y) in screenshot coordinates.",
                    JSONObject().put("x", prop("integer")).put("y", prop("integer"))
                        .put("confirm_description", CONFIRM),
                    listOf("x", "y")
                )
            )
            .put(
                tool(
                    "swipe", "Swipe or scroll from (x1,y1) to (x2,y2).",
                    JSONObject().put("x1", prop("integer")).put("y1", prop("integer"))
                        .put("x2", prop("integer")).put("y2", prop("integer")),
                    listOf("x1", "y1", "x2", "y2")
                )
            )
            .put(
                tool(
                    "type_text", "Type text into the focused field (tap the field first). Never for PINs/passwords/OTPs.",
                    JSONObject().put("text", prop("string")), listOf("text")
                )
            )
            .put(
                tool(
                    "press_key", "Press a system key.",
                    JSONObject()
                        .put(
                            "key",
                            JSONObject().put("type", "string")
                                .put("enum", JSONArray(listOf("home", "back", "recents", "enter", "notifications")))
                        )
                        .put("confirm_description", CONFIRM),
                    listOf("key")
                )
            )
            .put(
                tool(
                    "open_app", "Open an installed app by its name, e.g. WhatsApp, Maps, Grab.",
                    JSONObject().put("name", prop("string")), listOf("name")
                )
            )
            .put(
                tool(
                    "open_link", "Open a URL or deep link (https://, wa.me, tel:, sms:, geo:).",
                    JSONObject().put("url", prop("string")), listOf("url")
                )
            )
            .put(
                tool(
                    "ask_user", "Ask the user a question and wait for their typed answer.",
                    JSONObject().put("question", prop("string")), listOf("question")
                )
            )
            .put(
                tool(
                    "hand_to_user",
                    "Ask the user to do something themselves on the screen (enter PIN/password/OTP, fingerprint) " +
                        "and tap Done when finished.",
                    JSONObject().put("message", prop("string")), listOf("message")
                )
            )
            .put(
                tool(
                    "wait", "Wait a few seconds (for loading), then take a screenshot.",
                    JSONObject().put("seconds", prop("integer")), emptyList()
                )
            )
    }

    // ------------------------------------------------------------------ main loop

    fun run(task: String) {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", task))
        try {
            var lastText = ""
            for (step in 1..MAX_STEPS) {
                if (svc.stopRequested) return
                prune(messages)
                val resp = ClaudeApi.call(apiKey, SYSTEM, TOOLS, messages)
                val content = resp.getJSONArray("content")
                messages.put(JSONObject().put("role", "assistant").put("content", content))

                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    when (block.getString("type")) {
                        "text" -> {
                            val t = block.getString("text").trim()
                            if (t.isNotEmpty()) {
                                log("Jarvis: $t")
                                lastText = t
                            }
                        }
                        "tool_use" -> {
                            if (svc.stopRequested) return
                            val name = block.getString("name")
                            log("  > $name")
                            results.put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", block.getString("id"))
                                    .put("content", exec(name, block.getJSONObject("input")))
                            )
                        }
                    }
                }
                if (resp.optString("stop_reason") != "tool_use") {
                    if (lastText.isNotEmpty()) svc.speak(lastText)
                    return
                }
                messages.put(JSONObject().put("role", "user").put("content", results))
            }
            log("Jarvis: Step limit reached, sir. Tell me if you want me to continue.")
        } catch (e: Exception) {
            log("Error: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ tools

    private fun real(v: Int): Int = (v * svc.shotScale).toInt()

    private fun exec(name: String, a: JSONObject): JSONArray {
        return try {
            when (name) {
                "screenshot" -> shot("Current screen.", 0L)
                "tap" -> doTap(a)
                "swipe" -> {
                    svc.swipe(real(a.getInt("x1")), real(a.getInt("y1")), real(a.getInt("x2")), real(a.getInt("y2")))
                    shot("Swiped.")
                }
                "type_text" -> shot(svc.typeText(a.getString("text")))
                "press_key" -> doKey(a)
                "open_app" -> shot(svc.openApp(a.getString("name")), 2000L)
                "open_link" -> shot(svc.openLink(a.getString("url")), 2000L)
                "ask_user" -> {
                    val ans = svc.ask("Jarvis needs your input", a.getString("question"), AskMode.TEXT)
                    text(if (ans == null) "User cancelled." else "User answered: $ans")
                }
                "hand_to_user" -> {
                    val ok = svc.ask("Your turn, sir", a.getString("message"), AskMode.HANDOFF)
                    if (ok == null) text("User cancelled. Stop the task.") else shot("User says they are done.")
                }
                "wait" -> shot("Waited.", a.optInt("seconds", 3).coerceIn(1, 15) * 1000L)
                else -> text("Unknown tool: $name")
            }
        } catch (e: Exception) {
            text("Tool error: ${e.message}")
        }
    }

    private fun doTap(a: JSONObject): JSONArray {
        val x = real(a.getInt("x"))
        val y = real(a.getInt("y"))
        if (svc.isStopButton(x, y)) return text("That is the user's STOP button. Do not tap it.")

        var confirm = a.optString("confirm_description", "")
        if (confirm.isBlank()) {
            val label = svc.labelAt(x, y).replace(Regex("\\s+"), " ").trim()
            if (RISKY.containsMatchIn(label)) {
                confirm = "Tap \"${label.take(80)}\" in ${svc.foregroundPackage()}"
            }
        }
        if (confirm.isNotBlank() && !svc.approve(confirm)) return text(REJECTED)
        svc.tap(x, y)
        return shot("Tapped.")
    }

    private fun doKey(a: JSONObject): JSONArray {
        val key = a.getString("key")
        var confirm = a.optString("confirm_description", "")
        if (confirm.isBlank() && key == "enter" && SENSITIVE_PKG.containsMatchIn(svc.foregroundPackage())) {
            confirm = "Press Enter in ${svc.foregroundPackage()} (this may send or submit)"
        }
        if (confirm.isNotBlank() && !svc.approve(confirm)) return text(REJECTED)
        return shot(svc.pressKey(key))
    }

    // ------------------------------------------------------------------ helpers

    private fun textBlock(s: String) = JSONObject().put("type", "text").put("text", s)

    private fun text(s: String) = JSONArray().put(textBlock(s))

    private fun shot(note: String, waitMs: Long = 1500): JSONArray {
        if (waitMs > 0) Thread.sleep(waitMs)
        val b64 = svc.screenshotBase64()
            ?: return text("$note (Could not capture the screen. It may be a protected screen.)")
        return JSONArray()
            .put(textBlock(note))
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                )
            )
    }

    /** Keep only the 2 newest screenshots in the conversation so requests stay small and cheap. */
    private fun prune(messages: JSONArray, keep: Int = 2) {
        var seen = 0
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.getJSONObject(i)
            if (m.getString("role") != "user") continue
            val c = m.optJSONArray("content") ?: continue
            for (j in 0 until c.length()) {
                val block = c.getJSONObject(j)
                val inner = block.optJSONArray("content") ?: continue
                var hasImage = false
                for (k in 0 until inner.length()) {
                    if (inner.getJSONObject(k).optString("type") == "image") hasImage = true
                }
                if (hasImage) {
                    seen++
                    if (seen > keep) {
                        block.put("content", JSONArray().put(textBlock("(older screenshot removed)")))
                    }
                }
            }
        }
    }
}
