package com.livdub.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.livdub.app.MainActivity
import com.livdub.app.R
import com.livdub.app.audio.DubbedAudioPlayer
import com.livdub.app.audio.InternalAudioCapture
import com.livdub.app.gemini.GeminiLiveSession

/**
 * Foreground Service running in background to capture internal phone audio
 * and stream it to Gemini for instantaneous voice dubbing.
 * Compatible with Android 10 up to Android 14+ (API 34).
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
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
            val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Persian (Farsi)"

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                try {
                    // In Android 14 (API 34), startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    // MUST be invoked BEFORE calling getMediaProjection.
                    startForegroundWithProjectionType("Starting live audio dubbing...")
                    startDubbing(resultCode, resultData, apiKey, targetLang)
                } catch (e: Exception) {
                    Log.e(TAG, "Crash prevented in onStartCommand: ${e.message}", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Error starting dubbing: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                }
            } else {
                Log.w(TAG, "Invalid result data for media projection")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType(statusText: String) {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startDubbing(resultCode: Int, resultData: Intent, apiKey: String, targetLang: String) {
        isRunning = true

        try {
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
            val projection = mpManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "MediaProjection returned null")
                stopDubbing()
                stopSelf()
                return
            }
            mediaProjection = projection

            // In Android 14, registering a callback is strictly required before using MediaProjection
            val mainHandler = Handler(Looper.getMainLooper())
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection session stopped")
                    stopDubbing()
                    stopSelf()
                }
            }, mainHandler)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioCapture = InternalAudioCapture(projection) { audioChunk ->
                    // Feed captured system audio into Gemini live stream
                    geminiSession?.sendAudioChunk(audioChunk)
                }
                audioCapture?.startCapture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed during startDubbing: ${e.message}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Capture error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            stopDubbing()
            stopSelf()
        }
    }

    private fun stopDubbing() {
        isRunning = false
        try {
            audioCapture?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture: ${e.message}")
        }
        audioCapture = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection: ${e.message}")
        }
        mediaProjection = null

        try {
            geminiSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gemini session: ${e.message}")
        }
        geminiSession = null

        try {
            dubbedPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio player: ${e.message}")
        }
        dubbedPlayer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Livdub Dubbing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of real-time audio dubbing"
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
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (e: Exception) {
            Log.e(TAG, "Could not update notification: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopDubbing()
        super.onDestroy()
    }
}
