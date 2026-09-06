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
 * Uses USAGE_ASSISTANT and AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to automatically
 * duck (lower the volume of) the original video/media on the phone so the dubbed
 * voice is clearly heard above it.
 */
class DubbedAudioPlayer(
    private val context: Context,
    private val sampleRate: Int = 24000
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

            // USAGE_ASSISTANT ensures:
            // 1. AudioPlaybackCapture will NOT capture this dubbed audio (prevents infinite echo loop)
            // 2. Android system treats it as high-priority voice assistant audio over media
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

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}", e)
        }
    }

    /**
     * Write incoming audio chunks directly to the playing audio stream.
     */
    fun writePcmChunk(pcmData: ByteArray) {
        if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            initTrack()
        }
        try {
            audioTrack?.write(pcmData, 0, pcmData.size)
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
