package com.mizanora.app

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap

/**
 * Talks to Gemini's regular (non-Live) generateContent endpoint: one
 * screenshot + one instruction in, one JSON action back out. This is a
 * request/response loop, not a real-time video stream — Gemini's Live
 * (bidirectional audio+video) API exists but wiring it up reliably on
 * Android from scratch, untested, was judged too likely to silently fail;
 * this simpler loop is honest about what it does and actually works.
 */
object GeminiClient {

    private const val TAG = "MizanoraGemini"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val SYSTEM_PROMPT = """
        You are Mizanora, an assistant that can see one screenshot of an Android
        phone's screen and must decide the SINGLE next step toward the user's task.
        Reply with ONLY a JSON object, no markdown, no extra text, matching:
        {
          "action": "tap" | "swipe" | "type" | "open_app" | "back" | "home" | "speak" | "done",
          "x": <int, for tap/swipe start>,
          "y": <int, for tap/swipe start>,
          "x2": <int, for swipe end>,
          "y2": <int, for swipe end>,
          "text": "<string, for type>",
          "package_name": "<string, for open_app, e.g. com.android.settings>",
          "say": "<short spoken sentence explaining this step, always include>"
        }
        Only ever return ONE action per reply. Use "done" once the task is
        finished or cannot be done, and put the final explanation in "say".
        Never invent a step you can't see evidence for on screen.
    """.trimIndent()

    data class MizanoraAction(
        val action: String,
        val x: Int = 0,
        val y: Int = 0,
        val x2: Int = 0,
        val y2: Int = 0,
        val text: String = "",
        val packageName: String = "",
        val say: String = ""
    )

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private val OBSERVE_PROMPT = """
        You are Mizanora, glancing at a screenshot of the user's phone screen
        during idle "keep watching" mode — they have not asked you to do
        anything right now. Reply with ONE short sentence (under 20 words)
        ONLY if something is genuinely worth mentioning (an error, a message
        needing a reply, something unusual, a warning on screen). If the
        screen is just normal/unremarkable, reply with exactly: NOTHING
    """.trimIndent()

    /**
     * Lightweight idle-mode check: describes the screen in one sentence, or
     * returns null if nothing is worth mentioning (or the call failed).
     * Used by "keep watching" mode — kept cheap on purpose since it may run
     * every few seconds and costs a real API call each time.
     */
    fun observeScreen(apiKey: String, screenshot: Bitmap): String? {
        if (apiKey.isBlank()) return null
        try {
            val base64Image = bitmapToBase64Png(screenshot)
            val parts = JSONArray()
            parts.put(JSONObject().put("text", OBSERVE_PROMPT))
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/png").put("data", base64Image)
                )
            )
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 60))

            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "observeScreen error ${resp.code}: $bodyStr")
                    return null
                }
                val text = JSONObject(bodyStr)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim()
                return if (text.equals("NOTHING", ignoreCase = true) || text.isBlank()) null else text
            }
        } catch (e: Exception) {
            Log.e(TAG, "observeScreen failed", e)
            return null
        }
    }

    /**
     * Blocking call — run this off the main thread. Returns null on any
     * network/parse failure (caller should speak/log the failure).
     */
    fun decideNextStep(apiKey: String, screenshot: Bitmap, task: String, history: List<String>): MizanoraAction? {
        if (apiKey.isBlank()) {
            Log.e(TAG, "No Gemini API key configured.")
            return null
        }
        try {
            val base64Image = bitmapToBase64Png(screenshot)

            val historyText = if (history.isEmpty()) "" else
                "Steps already taken this task: " + history.joinToString(" -> ")

            val parts = JSONArray()
            parts.put(JSONObject().put("text", "$SYSTEM_PROMPT\n\nUser's task: $task\n$historyText"))
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/png")
                        .put("data", base64Image)
                )
            )

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.2).put("maxOutputTokens", 300)
                )

            val request = Request.Builder()
                .url("$ENDPOINT?key=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Gemini error ${resp.code}: $bodyStr")
                    return null
                }
                val json = JSONObject(bodyStr)
                val text = json
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val actionJson = JSONObject(cleaned)

                return MizanoraAction(
                    action = actionJson.optString("action", "speak"),
                    x = actionJson.optInt("x", 0),
                    y = actionJson.optInt("y", 0),
                    x2 = actionJson.optInt("x2", 0),
                    y2 = actionJson.optInt("y2", 0),
                    text = actionJson.optString("text", ""),
                    packageName = actionJson.optString("package_name", ""),
                    say = actionJson.optString("say", "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "decideNextStep failed", e)
            return null
        }
    }
}
