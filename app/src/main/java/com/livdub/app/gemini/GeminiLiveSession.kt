package com.livdub.app.gemini

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages two-way real-time streaming WebSocket connection with Gemini Live API,
 * using the EXACT same endpoint, model, and payload format as the official Livdub extension:
 *
 * 1. Endpoint: /ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
 * 2. Model: models/gemini-3.5-live-translate-preview
 * 3. Config: translationConfig with targetLanguageCode ("fa") and echoTargetLanguage
 * 4. Audio Input: realtimeInput.audio { data, mimeType: "audio/pcm;rate=16000" }
 * 5. Audio Output: serverContent.modelTurn.parts[].inlineData.data (24kHz PCM)
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val targetLanguage: String = "Persian (Farsi)",
    private val onIncomingAudioChunk: (ByteArray) -> Unit,
    private val onTranscriptReady: ((original: String, translated: String) -> Unit)? = null,
    private val onStateChanged: (state: SessionState, message: String?) -> Unit
) {
    enum class SessionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    companion object {
        private const val TAG = "GeminiLiveSession"
        // The exact live speech-to-speech translation model used by Livdub
        private const val MODEL_NAME = "models/gemini-3.5-live-translate-preview"
        private const val HOST = "generativelanguage.googleapis.com"
    }

    private val targetLanguageCode: String = when {
        targetLanguage.contains("fa", ignoreCase = true) ||
                targetLanguage.contains("farsi", ignoreCase = true) ||
                targetLanguage.contains("persian", ignoreCase = true) -> "fa"
        targetLanguage.contains("ar", ignoreCase = true) -> "ar"
        targetLanguage.contains("tr", ignoreCase = true) -> "tr"
        targetLanguage.contains("en", ignoreCase = true) -> "en"
        targetLanguage.contains("de", ignoreCase = true) -> "de"
        targetLanguage.contains("fr", ignoreCase = true) -> "fr"
        targetLanguage.contains("es", ignoreCase = true) -> "es"
        else -> "fa"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isSetupCompleted = false
    private val pendingAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        if (apiKey.isBlank()) {
            onStateChanged(SessionState.ERROR, "کلید API وارد نشده است (API key missing)")
            return
        }

        onStateChanged(SessionState.CONNECTING, "در حال اتصال به جمینای لایو (Connecting to Gemini Live)...")

        // Exact endpoint matching the Livdub Chrome Extension (v1beta)
        val url = "wss://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened to v1beta. Sending Livdub setup payload.")
                sendLivdubSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                onStateChanged(SessionState.DISCONNECTED, "ارتباط بسته شد: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                val rawMsg = t.message ?: ""
                val friendlyMsg = when {
                    rawMsg.contains("ping", ignoreCase = true) || rawMsg.contains("timed out", ignoreCase = true) ->
                        "تایم‌اوت ارتباط: لطفاً وضعیت فیلترشکن را بررسی کنید"
                    rawMsg.contains("Failed to connect", ignoreCase = true) ->
                        "عدم دسترسی به گوگل: اتصال اینترنت و فیلترشکن را بررسی کنید"
                    rawMsg.contains("403", ignoreCase = true) ->
                        "خطای دسترسی ۴۰۳: کلید API نامعتبر است یا کشور تحریم است"
                    else ->
                        "خطای ارتباط: ${t.localizedMessage ?: "Unknown error"}"
                }
                onStateChanged(SessionState.ERROR, friendlyMsg)
            }
        })
    }

    /**
     * Exact setup configuration identical to the Livdub Chrome extension:
     * Uses Gemini 3.5 Live Translate with native translationConfig.
     */
    private fun sendLivdubSetup() {
        try {
            val setupPayload = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", MODEL_NAME)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Kore")
                                })
                            })
                        })
                        put("translationConfig", JSONObject().apply {
                            put("targetLanguageCode", targetLanguageCode)
                            put("echoTargetLanguage", true)
                        })
                    })
                })
            }

            Log.d(TAG, "Sending setup: $setupPayload")
            webSocket?.send(setupPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
            onStateChanged(SessionState.ERROR, "خطا در برقراری ارتباط با مدل: ${e.message}")
        }
    }

    /**
     * Send real-time audio chunk to Gemini.
     * Uses the exact JSON structure of the Livdub extension:
     * { "realtimeInput": { "audio": { "data": base64, "mimeType": "audio/pcm;rate=16000" } } }
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isSetupCompleted || webSocket == null) {
            // Buffer up to 8 recent chunks while waiting for setupComplete
            if (pendingAudioQueue.size > 8) {
                pendingAudioQueue.poll()
            }
            pendingAudioQueue.offer(pcmChunk)
            return
        }

        sendAudioNow(pcmChunk)
    }

    private fun sendAudioNow(pcmChunk: ByteArray) {
        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val realtimeInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("data", base64Data)
                        put("mimeType", "audio/pcm;rate=16000")
                    })
                })
            }
            webSocket?.send(realtimeInput.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    private fun flushPendingAudio() {
        while (pendingAudioQueue.isNotEmpty()) {
            val chunk = pendingAudioQueue.poll() ?: break
            sendAudioNow(chunk)
        }
    }

    /**
     * Parse server response messages matching the Livdub extension handler:
     * - Checks for error
     * - Checks for setupComplete / setup_complete
     * - Parses serverContent.modelTurn.parts[].inlineData.data
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)

            // 1. Error handling from server
            val errorObj = json.optJSONObject("error")
            if (errorObj != null) {
                val errorMsg = errorObj.optString("message", "Unknown Gemini error")
                Log.e(TAG, "Gemini server error: $errorMsg")
                onStateChanged(SessionState.ERROR, errorMsg)
                return
            }

            // 2. Setup completion confirmation
            if (json.has("setupComplete") || json.has("setup_complete")) {
                Log.d(TAG, "Gemini Live setup completed successfully.")
                isSetupCompleted = true
                onStateChanged(SessionState.CONNECTED, "دوبله زنده فعال شد (زبان مقصد: فارسی)")
                flushPendingAudio()
                return
            }

            // 3. Audio parts received
            val serverContent = json.optJSONObject("serverContent") ?: json.optJSONObject("server_content")
            if (serverContent != null) {
                val modelTurn = serverContent.optJSONObject("modelTurn") ?: serverContent.optJSONObject("model_turn")
                val parts = modelTurn?.optJSONArray("parts")

                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        if (inlineData != null) {
                            val b64Data = inlineData.optString("data")
                            if (b64Data.isNotEmpty()) {
                                val pcmBytes = Base64.decode(b64Data, Base64.DEFAULT)
                                onIncomingAudioChunk(pcmBytes)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}")
        }
    }

    fun close() {
        isSetupCompleted = false
        pendingAudioQueue.clear()
        try {
            webSocket?.close(1000, "Session stopped by user")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
        onStateChanged(SessionState.DISCONNECTED, "ارتباط قطع شد")
    }
}
