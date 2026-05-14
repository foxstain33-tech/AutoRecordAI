package com.autorecordai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        
        // Broadcast actions
        const val ACTION_RECORDING_STARTED = "com.autorecordai.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.autorecordai.RECORDING_STOPPED"
        const val ACTION_REALTIME_TEXT = "com.autorecordai.REALTIME_TEXT"
        const val ACTION_AI_SUMMARY = "com.autorecordai.AI_SUMMARY"
    }

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private var isWorking = false
    private var recordingStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var timer: Timer? = null

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordingTime: TextView
    private lateinit var tvRealtimeText: TextView
    private lateinit var tvAiSummary: TextView
    private lateinit var scrollRealtime: ScrollView
    private lateinit var scrollSummary: ScrollView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.map { it.key }
        if (denied.isEmpty()) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
            checkAccessibilityService()
        } else {
            Toast.makeText(this, "部分权限被拒绝: " + denied.joinToString(), Toast.LENGTH_LONG).show()
        }
        updateUI()
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 检查电池优化白名单状态
        checkBatteryOptimization()
    }

    // 广播接收器 - 接收来自服务的更新
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RECORDING_STARTED -> {
                    runOnUiThread {
                        onRecordingStarted()
                    }
                }
                ACTION_RECORDING_STOPPED -> {
                    val audioPath = intent.getStringExtra("audio_path") ?: ""
                    runOnUiThread {
                        onRecordingStopped(audioPath)
                    }
                }
                ACTION_REALTIME_TEXT -> {
                    val text = intent.getStringExtra("text") ?: ""
                    runOnUiThread {
                        appendRealtimeText(text)
                    }
                }
                ACTION_AI_SUMMARY -> {
                    val summary = intent.getStringExtra("summary") ?: ""
                    runOnUiThread {
                        showAiSummary(summary)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Toast.makeText(this, "App已打开 v7", Toast.LENGTH_SHORT).show()

        initViews()
        setupUI()
        checkPermissions()
        checkBatteryOptimization()
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        tvRecordingTime = findViewById(R.id.tv_recording_time)
        tvRealtimeText = findViewById(R.id.tv_realtime_text)
        tvAiSummary = findViewById(R.id.tv_ai_summary)
        scrollRealtime = findViewById(R.id.scroll_realtime)
        scrollSummary = findViewById(R.id.scroll_summary)
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

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
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
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            showAccessibilityDialog()
            return
        }

        // 启动服务
        startServices()
        
        isWorking = true
        recordingStartTime = System.currentTimeMillis()
        updateUI()
        startTimer()
        
        Toast.makeText(this, "开始工作", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Work started")
    }

    private fun stopWork() {
        // 停止服务
        stopServices()
        
        isWorking = false
        updateUI()
        stopTimer()
        
        Toast.makeText(this, "停止工作，正在生成AI总结...", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Work stopped")
        
        // 触发 AI 总结（这里需要通过服务来调用）
        // 暂时显示占位符
        tvAiSummary.text = "正在处理..."
    }

    private fun startServices() {
        // 启动电话录音服务
        val phoneServiceIntent = Intent(this, PhoneCallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(phoneServiceIntent)
        } else {
            startService(phoneServiceIntent)
        }
        Log.d(TAG, "PhoneCallService started")
        
        // 保存状态
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("service_running", true)
            .apply()
    }

    private fun stopServices() {
        // 停止电话录音服务
        val phoneServiceIntent = Intent(this, PhoneCallService::class.java)
        stopService(phoneServiceIntent)
        Log.d(TAG, "PhoneCallService stopped")
        
        // 保存状态
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("service_running", false)
            .apply()
    }

    private fun startTimer() {
        timer = Timer()
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    updateRecordingTime()
                }
            }
        }, 0, 1000) // 每秒更新一次
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
        val hours = (elapsed / (1000 * 60 * 60))
        
        val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        tvRecordingTime.text = "录音时间: $timeStr"
    }

    private fun onRecordingStarted() {
        // 服务通知我们录音开始了
        Log.d(TAG, "Recording started")
    }

    private fun onRecordingStopped(audioPath: String) {
        // 服务通知我们录音停止了
        Log.d(TAG, "Recording stopped, audio: $audioPath")
        
        if (audioPath.isNotEmpty()) {
            // 触发 AI 处理
            processRecording(audioPath)
        }
    }

    private fun appendRealtimeText(text: String) {
        // 追加实时文字
        val currentText = tvRealtimeText.text.toString()
        if (currentText == "等待通话开始...") {
            tvRealtimeText.text = text
        } else {
            tvRealtimeText.append("\n$text")
        }
        
        // 自动滚动到底部
        scrollRealtime.post {
            scrollRealtime.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun showAiSummary(summary: String) {
        tvAiSummary.text = summary
        
        // 自动滚动到总结区域
        scrollSummary.post {
            scrollSummary.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun processRecording(audioPath: String) {
        // 调用 AIProcessor 处理录音
        Thread {
            val transcription = AIProcessor.transcribeWithXunfei(audioPath)
            val summary = AIProcessor.summarizeWithDoubao(transcription ?: "")
            
            runOnUiThread {
                showAiSummary(summary ?: "总结失败")
            }
        }.start()
    }

    private fun checkPermissions() {
        if (hasAllPermissions()) {
            checkAccessibilityService()
        } else {
            tvStatus.text = "需要授予权限"
            findViewById<Button>(R.id.btn_request_permissions)?.visibility = Button.VISIBLE
        }
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun checkAccessibilityService() {
        if (isAccessibilityServiceEnabled()) {
            tvStatus.text = "就绪 ✅ (点击「开始工作」)"
            updateUI()
        } else {
            showAccessibilityDialog()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val pref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return pref.getBoolean("accessibility_service_enabled", false)
    }

    private fun updateUI() {
        if (isWorking) {
            btnToggle.text = "停止工作"
            tvStatus.text = "正在监听通话..."
            tvRecordingTime.visibility = TextView.VISIBLE
        } else {
            btnToggle.text = "开始工作"
            tvStatus.text = "点击「开始工作」启动"
            tvRecordingTime.visibility = TextView.GONE
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("启用微信电话监听")
            .setMessage("要监听微信电话，需要开启无障碍服务。\n\n请前往：设置 → 无障碍 → 找到「自动录音AI」→ 开启")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("暂不开启", null)
            .show()
        findViewById<Button>(R.id.btn_accessibility)?.visibility = Button.VISIBLE
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // 请求电池优化白名单
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    batteryOptimizationLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面检查权限状态
        checkPermissions()
        // 检查无障碍服务状态
        if (isAccessibilityServiceEnabled()) {
            updateUI()
        }
    }

    override fun onStart() {
        super.onStart()
        // 注册广播接收器
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
        // 取消注册广播接收器
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
