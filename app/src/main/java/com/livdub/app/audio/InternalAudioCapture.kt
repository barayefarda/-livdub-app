package com.livdub.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures internal system audio playing on the Android device (Android 10+ / API 29+).
 * Converts internal audio playback to 16kHz Mono 16-bit PCM suitable for Gemini Live API.
 *
 * Excludes this application's own UID to prevent feedback loops where the dubbed
 * Persian audio would be recaptured and sent back into Gemini.
 * Also applies voice-energy gating to prevent sending endless digital silence when media is paused.
 */
class InternalAudioCapture(
    private val mediaProjection: MediaProjection,
    private val onAudioChunkReady: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "InternalAudioCapture"
        const val SAMPLE_RATE = 16000 // Standard rate for speech models
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 200 // Max amplitude threshold for silence gating
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (recordingJob?.isActive == true) return

        try {
            // 1. Configure audio playback capture: allow capturing media, games, and unknown audio streams
            // Exclude our own app's UID so dubbed Persian audio is NEVER re-captured into Gemini!
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(android.os.Process.myUid())
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = maxOf(minBufferSize, SAMPLE_RATE / 5 * 2) // ~200ms chunks

            // 2. Initialize AudioRecord with captureConfig
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord started successfully with excludeUid.")

            // 3. Read loop in coroutine with silence suppression
            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                var consecutiveSilenceChunks = 0

                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        val chunk = buffer.copyOf(readBytes)
                        val maxAmp = calculateMaxAmplitude(chunk)

                        if (maxAmp < SILENCE_THRESHOLD) {
                            consecutiveSilenceChunks++
                            // Send at most 2 silence frames (~400ms) so Gemini detects natural speech pause,
                            // then pause sending to prevent repetition/hallucination loops when video is paused!
                            if (consecutiveSilenceChunks <= 2) {
                                onAudioChunkReady(chunk)
                            }
                        } else {
                            consecutiveSilenceChunks = 0
                            onAudioChunkReady(chunk)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing internal audio capture: ${e.message}", e)
        }
    }

    private fun calculateMaxAmplitude(pcmChunk: ByteArray): Int {
        var max = 0
        val sampleCount = pcmChunk.size / 2
        for (i in 0 until sampleCount) {
            val b1 = pcmChunk[i * 2].toInt() and 0xFF
            val b2 = pcmChunk[i * 2 + 1].toInt()
            val sample = (b2 shl 8) or b1
            val abs = Math.abs(sample)
            if (abs > max) {
                max = abs
            }
        }
        return max
    }

    fun stopCapture() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        Log.d(TAG, "Audio capture stopped.")
    }
}
