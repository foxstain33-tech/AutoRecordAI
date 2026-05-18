package com.autorecordai

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_RECORDING_STARTED = "com.autorecordai.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.autorecordai.RECORDING_STOPPED"
        const val ACTION_REALTIME_TEXT = "com.autorecordai.REALTIME_TEXT"
        const val ACTION_AI_SUMMARY = "com.autorecordai.AI_SUMMARY"
    }

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private var isWorking = false
    private var recordingStartTime: Long = 0
    private var timer: Timer? = null

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordingTime: TextView
    private lateinit var tvRealtimeText: TextView
    private lateinit var tvAiSummary: TextView
    private lateinit var scrollRealtime: ScrollView
    private lateinit var scrollSummary: ScrollView
    private lateinit var btnAccessibility: Button

    private var accessibilityDialogShown = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.map { it.key }
        if (denied.isEmpty()) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "部分权限被拒绝: " + denied.joinToString(), Toast.LENGTH_LONG).show()
        }
        updateUI()
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RECORDING_STARTED -> runOnUiThread { onRecordingStarted() }
                ACTION_RECORDING_STOPPED -> {
                    val audioPath = intent.getStringExtra("audio_path") ?: ""
                    runOnUiThread { onRecordingStopped(audioPath) }
                }
                ACTION_REALTIME_TEXT -> {
                    val text = intent.getStringExtra("text") ?: ""
                    runOnUiThread { appendRealtimeText(text) }
                }
                ACTION_AI_SUMMARY -> {
                    val summary = intent.getStringExtra("summary") ?: ""
                    runOnUiThread { showAiSummary(summary) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupUI()
        updateUI()

        if (!hasAllPermissions()) {
            tvStatus.text = "请先授予权限"
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        tvRecordingTime = findViewById(R.id.tv_recording_time)
        tvRealtimeText = findViewById(R.id.tv_realtime_text)
        tvAiSummary = findViewById(R.id.tv_ai_summary)
        scrollRealtime = findViewById(R.id.scroll_realtime)
        scrollSummary = findViewById(R.id.scroll_summary)
        btnAccessibility = findViewById(R.id.btn_accessibility)
    }

    private fun setupUI() {
        btnToggle.setOnClickListener {
            if (isWorking) {
                stopWork()
            } else {
                startWork()
            }
        }

        findViewById<Button>(R.id.btn_request_permissions).setOnClickListener {
            requestPermissions()
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun startWork() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "请先授予权限", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            if (!accessibilityDialogShown) {
                accessibilityDialogShown = true
                AlertDialog.Builder(this)
                    .setTitle("微信电话监听")
                    .setMessage("要监听微信电话，需开启无障碍服务。\n\n前往：设置 → 无障碍 → 找到「自动录音AI」→ 开启\n\n不开启也可以监听普通电话。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("跳过") { _, _ -> }
                    .show()
            }
        }

        // 启动前台服务（只做保活，不做电话监听）
        try {
            val intent = Intent(this, PhoneCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("service_running", true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败: ${e.message}")
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        isWorking = true
        recordingStartTime = System.currentTimeMillis()
        updateUI()
        startTimer()

        tvRealtimeText.text = "监听中，等待通话..."
        tvAiSummary.text = "通话结束后自动生成总结"

        Toast.makeText(this, "已开始监听通话", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Work started")
    }

    private fun stopWork() {
        try {
            stopService(Intent(this, PhoneCallService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败: ${e.message}")
        }
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()

        isWorking = false
        updateUI()
        stopTimer()

        Toast.makeText(this, "已停止监听", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Work stopped")
    }

    private fun startTimer() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateRecordingTime() }
            }
        }, 0, 1000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun updateRecordingTime() {
        if (!isWorking) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val seconds = (elapsed / 1000) % 60
        val minutes = (elapsed / (1000 * 60)) % 60
        val hours = elapsed / (1000 * 60 * 60)
        tvRecordingTime.text = String.format("监听时间: %02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun onRecordingStarted() {
        Log.d(TAG, "Recording started broadcast received")
        tvRealtimeText.text = "通话中，正在录音..."
    }

    private fun onRecordingStopped(audioPath: String) {
        Log.d(TAG, "Recording stopped, audio: $audioPath")
        tvAiSummary.text = "通话结束，正在AI处理..."
        if (audioPath.isNotEmpty()) {
            processRecording(audioPath)
        }
    }

    private fun appendRealtimeText(text: String) {
        val currentText = tvRealtimeText.text.toString()
        if (currentText.startsWith("等待") || currentText.startsWith("监听中") || currentText.startsWith("通话中")) {
            tvRealtimeText.text = text
        } else {
            tvRealtimeText.append("\n$text")
        }
        scrollRealtime.post { scrollRealtime.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showAiSummary(summary: String) {
        tvAiSummary.text = summary
        scrollSummary.post { scrollSummary.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun processRecording(audioPath: String) {
        Thread {
            val transcription = AIProcessor.transcribeWithXunfei(audioPath)
            if (!transcription.isNullOrEmpty()) {
                runOnUiThread { appendRealtimeText("\n---转写结果---\n$transcription") }
            }
            val summary = AIProcessor.summarizeWithDoubao(transcription ?: "")
            if (!summary.isNullOrEmpty()) {
                runOnUiThread { showAiSummary(summary) }
            } else {
                runOnUiThread { showAiSummary("AI总结失败，请检查API配置") }
            }
        }.start()
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/.WeChatAccessibilityService"
        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName) || enabledServices.contains(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "检查无障碍服务失败: ${e.message}")
            return false
        }
    }

    private fun updateUI() {
        val hasPerms = hasAllPermissions()
        val hasAccessibility = isAccessibilityServiceEnabled()

        val btnPerms = findViewById<Button>(R.id.btn_request_permissions)
        btnPerms.visibility = if (hasPerms) View.GONE else View.VISIBLE

        btnAccessibility.visibility = if (hasAccessibility) View.GONE else View.VISIBLE

        if (isWorking) {
            btnToggle.text = "停止监听"
            tvStatus.text = "正在监听通话... 🎙️"
            tvRecordingTime.visibility = View.VISIBLE
        } else {
            btnToggle.text = "开始监听"
            if (!hasPerms) {
                tvStatus.text = "请先授予权限"
            } else if (!hasAccessibility) {
                tvStatus.text = "就绪 ✅ (可监听电话，微信需开无障碍)"
            } else {
                tvStatus.text = "就绪 ✅ (点击「开始监听」)"
            }
            tvRecordingTime.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_RECORDING_STARTED)
            addAction(ACTION_RECORDING_STOPPED)
            addAction(ACTION_REALTIME_TEXT)
            addAction(ACTION_AI_SUMMARY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
