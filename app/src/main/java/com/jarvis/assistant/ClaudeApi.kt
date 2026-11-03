package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeApi {
    const val MODEL = "claude-sonnet-5-5"

    /** One blocking call to the Claude Messages API. Run it off the main thread. */
    fun call(apiKey: String, system: String, tools: JSONArray, messages: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("tools", tools)
            .put("messages", messages)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw RuntimeException("API error $code (no details)")
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("API error $code: ${text.take(300)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
