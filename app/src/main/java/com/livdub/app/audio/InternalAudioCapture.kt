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
 * Converts internal audio playback to 16kHz or 24kHz Mono 16-bit PCM suitable for Gemini API.
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
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
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
            Log.d(TAG, "AudioRecord started successfully.")

            // 3. Read loop in coroutine
            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        val chunk = buffer.copyOf(readBytes)
                        onAudioChunkReady(chunk)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing internal audio capture: ${e.message}", e)
        }
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
