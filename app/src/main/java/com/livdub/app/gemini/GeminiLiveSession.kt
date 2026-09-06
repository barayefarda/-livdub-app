package com.livdub.app.gemini

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
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
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isSetupCompleted = false
    private val pendingAudioQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        val cleanKey = apiKey.trim().replace("\n", "").replace("\r", "")
        if (cleanKey.isBlank()) {
            onStateChanged(SessionState.ERROR, "کلید API وارد نشده است. لطفاً در برنامه کلید را وارد کنید.")
            return
        }

        onStateChanged(SessionState.CONNECTING, "در حال اتصال به هوش مصنوعی زنده...")

        // Build URL safely with properly encoded query parameters matching Livdub
        val wsUrl = "https://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", cleanKey)
            .build()
            .toString()
            .replaceFirst("https://", "wss://")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened to v1beta. Sending Livdub setup payload.")
                sendLivdubSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            // Google sends frames as binary (Opcode 2) UTF-8 JSON!
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                if (code != 1000) {
                    val friendly = parseCloseReason(code, reason)
                    onStateChanged(SessionState.ERROR, friendly)
                } else {
                    onStateChanged(SessionState.DISCONNECTED, "ارتباط بسته شد")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                if (code != 1000) {
                    val friendly = parseCloseReason(code, reason)
                    onStateChanged(SessionState.ERROR, friendly)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                val responseCode = response?.code
                val rawMsg = t.message ?: ""

                val friendlyMsg = when {
                    responseCode == 403 || rawMsg.contains("403") ->
                        "خطای دسترسی ۴۰۳: آی‌پی ایران مسدود است یا کلید نامعتبر است. لطفاً فیلترشکن را بررسی کنید."
                    responseCode == 400 || rawMsg.contains("400") ->
                        "خطای درخواست نامعتبر (۴۰۰): کلید API وارد شده نامعتبر است."
                    responseCode == 404 || rawMsg.contains("404") ->
                        "خطای ۴۰۴: مدل یا سرویس در دسترس نیست."
                    t is java.net.UnknownHostException || rawMsg.contains("Unable to resolve host", ignoreCase = true) ->
                        "خطای اینترنت/DNS: دسترسی به گوگل مسدود است. لطفاً فیلترشکن را روشن کنید."
                    t is java.net.SocketTimeoutException || rawMsg.contains("timed out", ignoreCase = true) ->
                        "تایم‌اوت ارتباط: سرعت اینترنت یا فیلترشکن برای اتصال به گوگل کافی نیست."
                    t is java.net.ConnectException || rawMsg.contains("Failed to connect", ignoreCase = true) ->
                        "خطای اتصال به سرور گوگل: لطفاً اتصال اینترنت و فیلترشکن را بررسی کنید."
                    t is javax.net.ssl.SSLHandshakeException ->
                        "خطای SSL شبکه: ارتباط توسط فیلترینگ یا اینترنت مختل شده است."
                    else ->
                        "خطای ارتباط (${responseCode ?: "اینترنت"}): ${t.localizedMessage ?: rawMsg}"
                }
                onStateChanged(SessionState.ERROR, friendlyMsg)
            }
        })
    }

    private fun parseCloseReason(code: Int, reason: String): String {
        return when {
            reason.contains("API key not valid", ignoreCase = true) ->
                "کلید API نامعتبر است. لطفاً کلید صحیح را از Google AI Studio کپی کنید."
            reason.contains("quota", ignoreCase = true) || reason.contains("exhausted", ignoreCase = true) ->
                "سهمیه رایگان کلید شما به پایان رسیده است."
            reason.contains("permission", ignoreCase = true) || reason.contains("access", ignoreCase = true) ->
                "عدم دسترسی به مدل ترجمه. نیاز به تغییر آی‌پی یا بررسی دسترسی در AI Studio."
            reason.isNotBlank() ->
                "سرور اتصال را بست: $reason"
            else ->
                "اتصال توسط گوگل قطع شد (کد $code)"
        }
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
            onStateChanged(SessionState.ERROR, "خطا در تنظیمات مدل: ${e.message}")
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
                val friendly = parseCloseReason(0, errorMsg)
                onStateChanged(SessionState.ERROR, friendly)
                return
            }

            // 2. Setup completion confirmation
            if (json.has("setupComplete") || json.has("setup_complete")) {
                Log.d(TAG, "Gemini Live setup completed successfully.")
                isSetupCompleted = true
                onStateChanged(SessionState.CONNECTED, "دوبله زنده فعال شد (زبان: فارسی)")
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
