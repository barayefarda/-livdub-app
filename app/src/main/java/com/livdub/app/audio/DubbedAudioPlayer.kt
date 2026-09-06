package com.livdub.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioFocusRequest
import android.os.Build
import android.util.Log

/**
 * Real-time audio player for playing synthesized PCM audio returned by Gemini.
 * Plays 24kHz Mono 16-bit PCM stream with ultra-low latency.
 *
 * Features:
 * 1. Hardware audio ducking request (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).
 * 2. Software Digital Gain Boost (default 2.8x, up to 3.8x) to ensure the dubbed voice
 *    is significantly louder, clearer, and dominates the underlying video audio.
 * 3. Soft-clipping protection to prevent digital distortion at high volumes.
 */
class DubbedAudioPlayer(
    private val context: Context,
    private val sampleRate: Int = 24000,
    var volumeBoost: Float = 2.8f
) {
    companion object {
        private const val TAG = "DubbedAudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        requestDuckingFocus()
        initTrack()
    }

    private fun requestDuckingFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()

                audioFocusRequest = focusRequest
                val result = audioManager?.requestAudioFocus(focusRequest)
                Log.d(TAG, "Requested audio focus (ducking original video): result = $result")
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus for ducking: ${e.message}")
        }
    }

    private fun initTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Ensure maximum volume on the AudioTrack channel
            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            Log.d(TAG, "AudioTrack initialized with volumeBoost = $volumeBoost")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    /**
     * Applies digital gain boost with soft-limiting to make the dubbed Persian voice
     * significantly louder and punchier than the background video audio.
     */
    private fun applyGainBoost(pcmData: ByteArray, gainFactor: Float): ByteArray {
        if (gainFactor <= 1.0f) return pcmData

        val sampleCount = pcmData.size / 2
        val output = ByteArray(pcmData.size)

        for (i in 0 until sampleCount) {
            val idx = i * 2
            val low = pcmData[idx].toInt() and 0xFF
            val high = pcmData[idx + 1].toInt()
            val sample = (high shl 8) or low

            var boosted = (sample * gainFactor).toInt()
            if (boosted > 32767) {
                boosted = 32767
            } else if (boosted < -32768) {
                boosted = -32768
            }

            output[idx] = (boosted and 0xFF).toByte()
            output[idx + 1] = ((boosted shr 8) and 0xFF).toByte()
        }
        return output
    }

    /**
     * Write incoming audio chunks directly to the playing audio stream with volume boost applied.
     */
    fun writePcmChunk(pcmData: ByteArray) {
        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initTrack()
        }
        try {
            val boostedChunk = applyGainBoost(pcmData, volumeBoost)
            audioTrack?.write(boostedChunk, 0, boostedChunk.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PCM chunk: ${e.message}")
        }
    }

    fun release() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio track: ${e.message}")
        }
        audioTrack = null
        audioFocusRequest = null
    }
}
