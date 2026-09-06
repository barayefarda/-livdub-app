package com.livdub.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livdub.app.MainActivity
import com.livdub.app.R
import com.livdub.app.audio.DubbedAudioPlayer
import com.livdub.app.audio.InternalAudioCapture
import com.livdub.app.gemini.GeminiLiveSession

/**
 * Foreground Service running in background to capture internal phone audio
 * and stream it to Gemini for instantaneous voice dubbing.
 */
class LiveDubbingService : Service() {

    companion object {
        const val TAG = "LiveDubbingService"
        const val CHANNEL_ID = "livdub_foreground_channel"
        const val NOTIFICATION_ID = 901

        const val ACTION_START = "com.livdub.app.ACTION_START"
        const val ACTION_STOP = "com.livdub.app.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_TARGET_LANG = "extra_target_lang"

        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var audioCapture: InternalAudioCapture? = null
    private var dubbedPlayer: DubbedAudioPlayer? = null
    private var geminiSession: GeminiLiveSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopDubbing()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
            val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Persian (Farsi)"

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                startForeground(NOTIFICATION_ID, buildNotification("Connecting to Gemini Live..."))
                startDubbing(resultCode, resultData, apiKey, targetLang)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, resultData: Intent, apiKey: String, targetLang: String) {
        isRunning = true

        // 1. Initialize output player
        dubbedPlayer = DubbedAudioPlayer(sampleRate = 24000)

        // 2. Initialize Gemini Live connection
        geminiSession = GeminiLiveSession(
            apiKey = apiKey,
            targetLanguage = targetLang,
            onIncomingAudioChunk = { pcmAudio ->
                dubbedPlayer?.writePcmChunk(pcmAudio)
            },
            onStateChanged = { state, msg ->
                Log.d(TAG, "Gemini State: $state - $msg")
                updateNotification(msg ?: "Dubbing active")
            }
        )
        geminiSession?.start()

        // 3. Initialize MediaProjection & Audio Capture
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            audioCapture = InternalAudioCapture(mediaProjection!!) { audioChunk ->
                // Feed captured system audio into Gemini live stream
                geminiSession?.sendAudioChunk(audioChunk)
            }
            audioCapture?.startCapture()
        }
    }

    private fun stopDubbing() {
        isRunning = false
        audioCapture?.stopCapture()
        audioCapture = null

        mediaProjection?.stop()
        mediaProjection = null

        geminiSession?.close()
        geminiSession = null

        dubbedPlayer?.release()
        dubbedPlayer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveDubbingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Livdub Live Dubbing")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        stopDubbing()
        super.onDestroy()
    }
}
