package com.livdub.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.livdub.app.floating.FloatingBubbleService
import com.livdub.app.service.LiveDubbingService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var projectionManager: MediaProjectionManager

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startDubbingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "مجوز ضبط صدای سیستم رد شد", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingBubble()
        } else {
            Toast.makeText(this, "مجوز نمایش روی سایر برنامه‌ها رد شد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("livdub_settings", Context.MODE_PRIVATE)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        requestRequiredPermissions()

        setContent {
            MaterialTheme {
                MainScreen(
                    isDubbingActive = LiveDubbingService.isRunning,
                    apiKey = prefs.getString("gemini_api_key", "") ?: "",
                    targetLang = prefs.getString("target_lang", "Persian (Farsi)") ?: "Persian (Farsi)",
                    volumeBoost = prefs.getFloat("volume_boost", 2.8f),
                    onSaveApiKey = { key -> prefs.edit().putString("gemini_api_key", key).apply() },
                    onSaveLang = { lang -> prefs.edit().putString("target_lang", lang).apply() },
                    onSaveVolumeBoost = { boost -> prefs.edit().putFloat("volume_boost", boost).apply() },
                    onToggleDubbing = {
                        if (LiveDubbingService.isRunning) {
                            stopDubbingService()
                        } else {
                            initiateCapture()
                        }
                    },
                    onToggleFloatingBubble = {
                        if (Settings.canDrawOverlays(this)) {
                            startFloatingBubble()
                        } else {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            overlayPermissionLauncher.launch(intent)
                        }
                    }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())
        }
    }

    private fun initiateCapture() {
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "لطفاً ابتدا کلید API جمینای خود را وارد کنید", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(captureIntent)
        } else {
            Toast.makeText(this, "این قابلیت نیازمند اندروید ۱۰ به بالا است", Toast.LENGTH_LONG).show()
        }
    }

    private fun startDubbingService(resultCode: Int, data: Intent) {
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val targetLang = prefs.getString("target_lang", "Persian (Farsi)") ?: "Persian (Farsi)"
        val volumeBoost = prefs.getFloat("volume_boost", 2.8f)

        val intent = Intent(this, LiveDubbingService::class.java).apply {
            action = LiveDubbingService.ACTION_START
            putExtra(LiveDubbingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveDubbingService.EXTRA_RESULT_DATA, data)
            putExtra(LiveDubbingService.EXTRA_API_KEY, apiKey)
            putExtra(LiveDubbingService.EXTRA_TARGET_LANG, targetLang)
            putExtra(LiveDubbingService.EXTRA_VOLUME_BOOST, volumeBoost)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "دوبله زنده آغاز شد! ویدیو را پخش کنید.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در شروع سرویس: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopDubbingService() {
        val intent = Intent(this, LiveDubbingService::class.java).apply {
            action = LiveDubbingService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "دوبله همزمان متوقف شد", Toast.LENGTH_SHORT).show()
    }

    private fun startFloatingBubble() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        startService(intent)
        Toast.makeText(this, "دکمه شناور روی صفحه فعال شد", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isDubbingActive: Boolean,
    apiKey: String,
    targetLang: String,
    volumeBoost: Float,
    onSaveApiKey: (String) -> Unit,
    onSaveLang: (String) -> Unit,
    onSaveVolumeBoost: (Float) -> Unit,
    onToggleDubbing: () -> Unit,
    onToggleFloatingBubble: () -> Unit
) {
    var keyText by remember { mutableStateOf(apiKey) }
    var selectedLang by remember { mutableStateOf(targetLang) }
    var selectedBoost by remember { mutableStateOf(volumeBoost) }
    var activeState by remember { mutableStateOf(isDubbingActive) }

    val languages = listOf("Persian (Farsi)", "Arabic", "Turkish", "Spanish", "French", "German", "Russian", "Hindi")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Livdub AI",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF38BDF8))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "LIVE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF0F172A))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC))
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeState) Color(0xFFDCFCE7) else Color(0xFFF1F5F9)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (activeState) Color(0xFF22C55E) else Color(0xFF94A3B8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activeState) Icons.Default.PlayArrow else Icons.Default.Stop,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (activeState) "دوبله همزمان در حال اجراست" else "سیستم آماده به کار است",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = if (activeState) Color(0xFF15803D) else Color(0xFF334155)
                    )
                    Text(
                        text = if (activeState) "صدای فیلم به صورت آنی به $selectedLang دوبله می‌شود" else "دکمه زیر را برای شروع دوبله لمس کنید",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Start/Stop Primary Button
            Button(
                onClick = {
                    activeState = !activeState
                    onToggleDubbing()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeState) Color(0xFFEF4444) else Color(0xFF2563EB)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = if (activeState) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (activeState) "توقف دوبله همزمان" else "شروع دوبله روی فیلم‌ها",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Floating Bubble Button
            OutlinedButton(
                onClick = onToggleFloatingBubble,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "نمایش دکمه شناور روی صفحه (Overlay)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Settings Section
            Text(
                text = "تنظیمات هوش مصنوعی جمینای",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF0F172A),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Gemini API Key Input
            OutlinedTextField(
                value = keyText,
                onValueChange = {
                    keyText = it
                    onSaveApiKey(it)
                },
                label = { Text("Gemini API Key") },
                placeholder = { Text("کلید هوش مصنوعی را اینجا وارد کنید") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Target Language Selection
            Text(
                text = "زبان مقصد دوبله (Target Language)",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Color(0xFF475569),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Persian (Farsi)", "Arabic", "Turkish").forEach { lang ->
                    FilterChip(
                        selected = selectedLang == lang,
                        onClick = {
                            selectedLang = lang
                            onSaveLang(lang)
                        },
                        label = { Text(lang) },
                        leadingIcon = if (selectedLang == lang) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Volume Boost Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dubbing Volume Boost (بلندی صدای دوبله)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = "${(selectedBoost * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF4F46E5)
                )
            }

            Text(
                text = "تقویت دیجیتال صدای دوبله تا کاملاً بلندتر و واضح‌تر از صدای زمینه فیلم شنیده شود.",
                fontSize = 12.sp,
                color = Color(0xFF64748B),
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = 4.dp, bottom = 10.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val boostOptions = listOf(
                    1.8f to "۱۸۰٪ (معمولی)",
                    2.8f to "۲۸۰٪ (پیشنهادی)",
                    3.8f to "۳۸۰٪ (حداکثر)"
                )
                boostOptions.forEach { (boost, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(selectedBoost - boost) < 0.1f,
                        onClick = {
                            selectedBoost = boost
                            onSaveVolumeBoost(boost)
                        },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Useful Tip Card
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "راهکار برتر: هنگام پخش فیلم در یوتیوب یا اینستاگرام، ولوم صدای گوشی را در حدود ۴۰٪ تا ۵۰٪ تنظیم کنید. به دلیل تقویت ۲۸۰٪، صدای دوبله فارسی بسیار رسا و غالب پخش شده و صدای اصلی فیلم در پس‌زمینه کم‌رنگ می‌شود.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF1E40AF)
                    )
                }
            }
        }
    }
}
