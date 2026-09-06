package com.livdub.app.gemini

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages two-way real-time streaming WebSocket connection with Gemini Live API.
 * 
 * Features built-in resilience for unstable VPN connections and temporary network drops:
 * 1. Keep-Alive: Ping interval of 10s prevents VPN NAT tunnels from timing out idle sockets.
 * 2. Session Resumption: Captures sessionResumptionUpdate tokens from Google to resume sessions seamlessly.
 * 3. Auto-Reconnection: When a socket drops due to VPN jitter/ping spikes, it automatically
 *    reconnects with exponential backoff rather than terminating the session.
 * 4. Audio Buffering: Buffers recent audio during reconnections so spoken phrases are not lost.
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
        private const val MODEL_NAME = "models/gemini-3.5-live-translate-preview"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val MAX_BUFFERED_CHUNKS = 25 // ~5 seconds of audio
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

    // Ping interval of 10 seconds keeps the socket alive across VPN tunnels and NAT routers
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isSetupCompleted = false
    private val isClosedByUser = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionResumptionHandle: String? = null

    private val pendingAudioQueue = ConcurrentLinkedQueue<ByteArray>()

    fun start() {
        val cleanKey = apiKey.trim().replace("\n", "").replace("\r", "")
        if (cleanKey.isBlank()) {
            onStateChanged(SessionState.ERROR, "کلید API وارد نشده است. لطفاً در برنامه کلید را وارد کنید.")
            return
        }

        isClosedByUser.set(false)
        connectSocket()
    }

    private fun connectSocket() {
        if (isClosedByUser.get()) return

        val cleanKey = apiKey.trim().replace("\n", "").replace("\r", "")
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

        isSetupCompleted = false
        val statusMsg = if (reconnectAttempt > 0) {
            "در حال اتصال مجدد خودکار به جمینای (تلاش $reconnectAttempt)..."
        } else {
            "در حال اتصال به هوش مصنوعی زنده..."
        }
        onStateChanged(SessionState.CONNECTING, statusMsg)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened. Sending setup payload (hasResumption=${sessionResumptionHandle != null}).")
                reconnectAttempt = 0
                isReconnecting.set(false)
                sendLivdubSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket onClosing: code=$code, reason=$reason")
                if (code == 1000 || isClosedByUser.get()) {
                    onStateChanged(SessionState.DISCONNECTED, "ارتباط بسته شد")
                } else {
                    handleTransientDisconnection("ارتباط بسته شد ($code): $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket onClosed: code=$code, reason=$reason")
                if (code != 1000 && !isClosedByUser.get()) {
                    handleTransientDisconnection("قطع ارتباط موقت ($code)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket onFailure: ${t.message}", t)
                val responseCode = response?.code
                val rawMsg = t.message ?: ""

                // Fatal auth errors: notify user immediately
                if (responseCode == 400 || rawMsg.contains("API key not valid", ignoreCase = true)) {
                    isClosedByUser.set(true)
                    onStateChanged(SessionState.ERROR, "کلید API نامعتبر است. لطفاً کلید صحیح را از Google AI Studio کپی کنید.")
                    return
                }

                // Transient network / VPN errors: perform auto-reconnect without terminating service
                handleTransientDisconnection(t.localizedMessage ?: "قطع لحظه‌ای شبکه/فیلترشکن")
            }
        })
    }

    /**
     * Handles transient network/VPN disconnections by scheduling an automatic reconnection.
     */
    private fun handleTransientDisconnection(reason: String) {
        if (isClosedByUser.get()) return
        if (isReconnecting.getAndSet(true)) return

        isSetupCompleted = false
        reconnectAttempt++

        // Progressive backoff: 1s, 2s, 3s, max 4s
        val delayMillis = (minOf(reconnectAttempt, 4) * 1000L)
        Log.w(TAG, "Transient disconnect ($reason). Reconnecting in ${delayMillis}ms (attempt $reconnectAttempt)...")

        onStateChanged(
            SessionState.CONNECTING,
            "ارتباط با فیلترشکن موقتاً قطع شد؛ در حال اتصال مجدد خودکار..."
        )

        mainHandler.postDelayed({
            isReconnecting.set(false)
            if (!isClosedByUser.get()) {
                connectSocket()
            }
        }, delayMillis)
    }

    /**
     * Exact setup configuration identical to the Livdub Chrome extension:
     * Uses Gemini 3.5 Live Translate with native translationConfig,
     * and passes sessionResumption handle when reconnecting so context is preserved.
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

                    // If we have a saved resumption handle from earlier in this session, provide it
                    sessionResumptionHandle?.let { handle ->
                        put("sessionResumption", JSONObject().apply {
                            put("handle", handle)
                        })
                    }
                })
            }

            Log.d(TAG, "Sending setup: $setupPayload")
            webSocket?.send(setupPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
            handleTransientDisconnection("خطا در ارسال تنظیمات: ${e.message}")
        }
    }

    /**
     * Send real-time audio chunk to Gemini.
     * Buffers up to MAX_BUFFERED_CHUNKS during temporary VPN reconnects.
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isSetupCompleted || webSocket == null) {
            // Buffer recent audio while reconnecting so no spoken words are lost
            while (pendingAudioQueue.size >= MAX_BUFFERED_CHUNKS) {
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
        Log.d(TAG, "Flushing ${pendingAudioQueue.size} buffered audio chunks after connection.")
        while (pendingAudioQueue.isNotEmpty()) {
            val chunk = pendingAudioQueue.poll() ?: break
            sendAudioNow(chunk)
        }
    }

    /**
     * Parse server response messages matching the Livdub extension handler:
     * - Captures sessionResumptionUpdate tokens for smooth reconnection
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
                if (errorMsg.contains("API key not valid", ignoreCase = true)) {
                    isClosedByUser.set(true)
                    onStateChanged(SessionState.ERROR, "کلید API نامعتبر است.")
                } else {
                    handleTransientDisconnection(errorMsg)
                }
                return
            }

            // 2. Session Resumption handle capture (exact same pattern as Livdub Chrome extension)
            val resumption = json.optJSONObject("sessionResumptionUpdate") ?: json.optJSONObject("session_resumption_update")
            if (resumption != null && resumption.optBoolean("resumable", true)) {
                val newHandle = resumption.optString("newHandle").ifEmpty { resumption.optString("new_handle") }
                if (newHandle.isNotEmpty()) {
                    sessionResumptionHandle = newHandle
                    Log.d(TAG, "Updated sessionResumptionHandle for automatic reconnection.")
                }
            }

            // 3. Graceful Google server-side GoAway
            val goAway = json.optJSONObject("goAway") ?: json.optJSONObject("go_away")
            if (goAway != null) {
                Log.i(TAG, "Received server goAway notice. Scheduling seamless reconnection.")
                handleTransientDisconnection("Server requested reconnect")
                return
            }

            // 4. Setup completion confirmation
            if (json.has("setupComplete") || json.has("setup_complete")) {
                Log.d(TAG, "Gemini Live setup completed successfully.")
                isSetupCompleted = true
                onStateChanged(SessionState.CONNECTED, "دوبله زنده فعال شد (زبان: فارسی)")
                flushPendingAudio()
                return
            }

            // 5. Audio parts received
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
        isClosedByUser.set(true)
        isSetupCompleted = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingAudioQueue.clear()
        sessionResumptionHandle = null
        try {
            webSocket?.close(1000, "Session stopped by user")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
        onStateChanged(SessionState.DISCONNECTED, "ارتباط قطع شد")
    }
}
