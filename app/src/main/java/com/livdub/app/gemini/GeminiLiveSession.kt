package com.livdub.app.gemini

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages two-way real-time streaming WebSocket connection with Gemini Live API:
 * 1. Sends PCM audio chunks captured from system audio playback.
 * 2. Receives live synthesized translated voice audio and dispatches to AudioTrack.
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
        private const val MODEL_NAME = "models/gemini-2.0-flash-exp"
        private const val HOST = "generativelanguage.googleapis.com"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isSetupCompleted = false

    fun start() {
        if (apiKey.isBlank()) {
            onStateChanged(SessionState.ERROR, "API key is missing")
            return
        }

        onStateChanged(SessionState.CONNECTING, "Connecting to Gemini Live...")

        val url = "wss://$HOST/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened. Sending initial setup payload.")
                sendInitialSetup()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                onStateChanged(SessionState.DISCONNECTED, "Session closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                onStateChanged(SessionState.ERROR, t.message ?: "Connection failure")
            }
        })
    }

    /**
     * Initial handshake configuration: sets model instructions to act as a simultaneous live dubber.
     */
    private fun sendInitialSetup() {
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
                                    put("voiceName", "Kore") // Natural clear voice
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put(
                                    "text",
                                    "You are a professional simultaneous live audio dubber and interpreter. " +
                                            "You will receive live streaming audio in real-time. Immediately translate the spoken content " +
                                            "into fluent, natural, spoken $targetLanguage. " +
                                            "Do not add conversational filler, do not ask questions, do not explain anything. " +
                                            "Directly output the dubbed voice in $targetLanguage matching the cadence and tone of the speaker."
                                )
                            })
                        })
                    })
                })
            }

            webSocket?.send(setupPayload.toString())
            isSetupCompleted = true
            onStateChanged(SessionState.CONNECTED, "Live translation active ($targetLanguage)")
            Log.d(TAG, "Setup sent successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup message: ${e.message}", e)
        }
    }

    /**
     * Send real-time audio chunk to Gemini (encoded as base64 PCM 16kHz Mono).
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isSetupCompleted || webSocket == null) return

        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val realtimeInput = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    })
                })
            }
            webSocket?.send(realtimeInput.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    /**
     * Parse server-sent audio chunks from Gemini model response.
     */
    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val serverContent = json.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType")
                    val b64Data = inlineData.optString("data")
                    if (b64Data.isNotEmpty()) {
                        val pcmBytes = Base64.decode(b64Data, Base64.DEFAULT)
                        onIncomingAudioChunk(pcmBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}")
        }
    }

    fun close() {
        isSetupCompleted = false
        try {
            webSocket?.close(1000, "Session stopped by user")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket: ${e.message}")
        }
        webSocket = null
        onStateChanged(SessionState.DISCONNECTED, "Disconnected")
    }
}
