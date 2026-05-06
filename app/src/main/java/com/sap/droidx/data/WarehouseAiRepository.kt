package com.sap.droidx.data

import com.sap.droidx.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WarehouseIntent(
    val action: String,       // "POST_GR" | "UNKNOWN"
    val orderId: String,
    val qty: Int,
    val confidence: String    // "HIGH" | "LOW"
)

class WarehouseAiRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private val systemPrompt = """
You are a warehouse assistant AI that extracts intent from spoken warehouse commands.
Extract the order ID and quantity from the user's speech.

Order ID normalisation rules:
- "PRD dash 001" → "PRD-001"
- "production order 5" → "PRD-005"
- "order P R D 0 0 1" → "PRD-001"
- Numbers spoken as digits: "zero zero one" → "001"
- If no order ID found, use empty string ""

Respond ONLY with a JSON object:
{
  "action": "POST_GR",
  "orderId": "<normalised order id>",
  "qty": <integer quantity, default 1 if not mentioned>,
  "confidence": "HIGH" or "LOW"
}
Set confidence LOW if the order ID is unclear or missing.
""".trimIndent()

    fun extractIntent(spokenText: String): WarehouseIntent {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_ANTHROPIC_API_KEY_HERE") {
            return WarehouseIntent("UNKNOWN", "", 1, "LOW")
        }

        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 256)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", spokenText)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IllegalStateException("Empty response from Claude")
        if (!response.isSuccessful) throw IllegalStateException("Claude API error ${response.code}: $responseBody")

        val content = JSONObject(responseBody)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()

        val json = JSONObject(content)
        return WarehouseIntent(
            action     = json.optString("action", "UNKNOWN"),
            orderId    = json.optString("orderId", "").trim(),
            qty        = json.optInt("qty", 1),
            confidence = json.optString("confidence", "LOW")
        )
    }
}
