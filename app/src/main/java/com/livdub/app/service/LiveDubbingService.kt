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
import com.livdub.app.MainActivity
import com.livdub.app.R
import com.livdub.app.audio.DubbedAudioPlayer
import com.livdub.app.audio.InternalAudioCapture
import com.livdub.app.gemini.GeminiLiveSession

/**
 * Foreground Service that handles the lifetime of background audio capture
 * and the real-time AI live dubbing session.
 */
class LiveDubbingService : Service() {

    companion object {
        private const val TAG = "LiveDubbingService"
        const val CHANNEL_ID = "livdub_foreground_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.livdub.app.action.START"
        const val ACTION_STOP = "com.livdub.app.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_TARGET_LANG = "extra_target_lang"
        const val EXTRA_VOLUME_BOOST = "extra_volume_boost"

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
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "Persian (Farsi)"
                val volumeBoost = intent.getFloatExtra(EXTRA_VOLUME_BOOST, 2.8f)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    try {
                        // CRITICAL FOR ANDROID 14+ (API 34+):
                        // startForeground() with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        // MUST be invoked BEFORE calling getMediaProjection.
                        startForegroundWithProjectionType("Starting live audio dubbing...")
                        startDubbing(resultCode, resultData, apiKey, targetLang, volumeBoost)
                    } catch (e: Exception) {
                        Log.e(TAG, "Crash prevented in onStartCommand: ${e.message}", e)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this, "خطا در شروع سرویس: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        stopSelf()
                    }
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startDubbing(resultCode: Int, resultData: Intent, apiKey: String, targetLang: String, volumeBoost: Float = 2.8f) {
        isRunning = true

        try {
            // 1. Initialize output player with ducking capability and digital gain boost
            dubbedPlayer = DubbedAudioPlayer(context = this, sampleRate = 24000, volumeBoost = volumeBoost)

            // 2. Initialize Gemini Live connection
            geminiSession = GeminiLiveSession(
                apiKey = apiKey,
                targetLanguage = targetLang,
                onIncomingAudioChunk = { pcmAudio ->
                    dubbedPlayer?.writePcmChunk(pcmAudio)
                },
                onStateChanged = { state, message ->
                    updateNotification("Gemini: ${message ?: state.name}")
                }
            )
            geminiSession?.start()

            // 3. Initialize Audio Capture from MediaProjection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioCapture = InternalAudioCapture(mediaProjection!!) { audioChunk ->
                    geminiSession?.sendAudioChunk(audioChunk)
                }
                audioCapture?.startCapture()
            }

            updateNotification("دوبله زنده هوش مصنوعی فعال است")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting dubbing: ${e.message}", e)
            stopDubbing()
        }
    }

    private fun stopDubbing() {
        isRunning = false
        try {
            audioCapture?.stopCapture()
            audioCapture = null
            geminiSession?.close()
            geminiSession = null
            dubbedPlayer?.release()
            dubbedPlayer = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping dubbing: ${e.message}")
        }
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, LiveDubbingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Livdub AI - دوبله زنده فعال")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenIntent)
            .addAction(R.mipmap.ic_launcher, "توقف", pendingStopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Livdub Live Dubbing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت دوبله همزمان در پس‌زمینه"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopDubbing()
        super.onDestroy()
    }
}
