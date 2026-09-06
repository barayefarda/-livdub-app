package com.livdub.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livdub.app.service.FloatingBubbleService
import com.livdub.app.service.LiveDubbingService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDubbingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Audio capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            startBubbleService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("livdub_prefs", Context.MODE_PRIVATE)

        setContent {
            LivdubAppScreen(
                isDubbingActive = LiveDubbingService.isRunning,
                apiKey = prefs.getString("gemini_api_key", "") ?: "",
                targetLang = prefs.getString("target_lang", "Persian (Farsi)") ?: "Persian (Farsi)",
                onSaveApiKey = { key -> prefs.edit().putString("gemini_api_key", key).apply() },
                onSaveLang = { lang -> prefs.edit().putString("target_lang", lang).apply() },
                onToggleDubbing = {
                    if (LiveDubbingService.isRunning) {
                        stopDubbingService()
                    } else {
                        checkAndRequestPermissions()
                    }
                },
                onToggleFloatingBubble = {
                    if (hasOverlayPermission()) {
                        startBubbleService()
                    } else {
                        requestOverlayPermission()
                    }
                }
            )
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startBubbleService() {
        startService(Intent(this, FloatingBubbleService::class.java))
        Toast.makeText(this, "Floating bubble enabled! Tap anytime to dub.", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API Key first", Toast.LENGTH_LONG).show()
            return
        }

        // Request system audio capture via MediaProjection
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startDubbingService(resultCode: Int, data: Intent) {
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val targetLang = prefs.getString("target_lang", "Persian (Farsi)") ?: "Persian (Farsi)"

        val intent = Intent(this, LiveDubbingService::class.java).apply {
            action = LiveDubbingService.ACTION_START
            putExtra(LiveDubbingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveDubbingService.EXTRA_RESULT_DATA, data)
            putExtra(LiveDubbingService.EXTRA_API_KEY, apiKey)
            putExtra(LiveDubbingService.EXTRA_TARGET_LANG, targetLang)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopDubbingService() {
        val intent = Intent(this, LiveDubbingService::class.java).apply {
            action = LiveDubbingService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LivdubAppScreen(
    isDubbingActive: Boolean,
    apiKey: String,
    targetLang: String,
    onSaveApiKey: (String) -> Unit,
    onSaveLang: (String) -> Unit,
    onToggleDubbing: () -> Unit,
    onToggleFloatingBubble: () -> Unit
) {
    var keyText by remember { mutableStateOf(apiKey) }
    var selectedLang by remember { mutableStateOf(targetLang) }
    var activeState by remember { mutableStateOf(isDubbingActive) }

    val languages = listOf("Persian (Farsi)", "Arabic", "Turkish", "Spanish", "French", "German", "Russian", "Hindi")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Livdub Android",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (activeState) Color(0xFF10B981) else Color(0xFF94A3B8),
                            modifier = Modifier.size(10.dp)
                        ) {}
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8FAFC))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF8FAFC))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero Status Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeState) Color(0xFF4F46E5) else Color(0xFFFFFFFF)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(if (activeState) Color.White.copy(alpha = 0.2f) else Color(0xFFEEF2FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activeState) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (activeState) Color.White else Color(0xFF4F46E5),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (activeState) "Live Dubbing Active" else "Ready to Dub System Audio",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (activeState) Color.White else Color(0xFF0F172A)
                    )

                    Text(
                        text = if (activeState)
                            "Capturing phone audio and translating to $selectedLang"
                        else
                            "Translate YouTube, Instagram, podcasts or browser audio in real time",
                        fontSize = 14.sp,
                        color = if (activeState) Color.White.copy(alpha = 0.8f) else Color(0xFF64748B),
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            activeState = !activeState
                            onToggleDubbing()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (activeState) Color(0xFFEF4444) else Color(0xFF4F46E5)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (activeState) "Stop Dubbing" else "Start Live Dubbing",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Floating Bubble Quick Action
            OutlinedButton(
                onClick = onToggleFloatingBubble,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable Floating Screen Bubble")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Settings Section
            Text(
                text = "Dubbing Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0F172A),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // API Key Input
            OutlinedTextField(
                value = keyText,
                onValueChange = {
                    keyText = it
                    onSaveApiKey(it)
                },
                label = { Text("Gemini API Key") },
                placeholder = { Text("Enter AI Studio API Key...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Target Language Picker
            Text(
                text = "Target Dubbing Language",
                fontSize = 14.sp,
                color = Color(0xFF475569),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                languages.take(3).forEach { lang ->
                    FilterChip(
                        selected = selectedLang == lang,
                        onClick = {
                            selectedLang = lang
                            onSaveLang(lang)
                        },
                        label = { Text(lang) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}
