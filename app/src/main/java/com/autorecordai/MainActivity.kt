package com.autorecordai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val REQUEST_CODE = 1001
    private val ACTION_RECORDING_STARTED = "com.autorecordai.RECORDING_STARTED"
    private val ACTION_RECORDING_STOPPED = "com.autorecordai.RECORDING_STOPPED"
    private val ACTION_REALTIME_TEXT = "com.autorecordai.REALTIME_TEXT"
    private val ACTION_AI_RESULT = "com.autorecordai.AI_RESULT"

    private lateinit var prefs: SharedPreferences
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnTestRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRealtimeText: TextView
    private lateinit var tvSummary: TextView

    private var testRecorder: MediaRecorder? = null
    private var testFilePath: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RECORDING_STARTED -> {
                    runOnUiThread {
                        tvStatus.text = "🔴 正在录音..."
                        Toast.makeText(this@MainActivity, "开始录音", Toast.LENGTH_SHORT).show()
                    }
                }
                ACTION_RECORDING_STOPPED -> {
                    runOnUiThread {
                        tvStatus.text = "🟢 监听中，等待通话..."
                    }
                }
                ACTION_REALTIME_TEXT -> {
                    val text = intent.getStringExtra("text") ?: ""
                    runOnUiThread {
                        tvRealtimeText.text = "📝 实时通话内容\n$text"
                    }
                }
                ACTION_AI_RESULT -> {
                    val transcribedText = intent.getStringExtra("transcribed_text") ?: ""
                    val summary = intent.getStringExtra("summary") ?: ""
                    runOnUiThread {
                        tvRealtimeText.text = "📝 转写内容\n${transcribedText.take(500)}"
                        tvSummary.text = "🤖 AI 总结\n${summary.take(500)}"
                        Toast.makeText(this@MainActivity, "AI处理完成！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnTestRecord = findViewById(R.id.btn_test_record)
        tvStatus = findViewById(R.id.tv_status)
        tvRealtimeText = findViewById(R.id.tv_realtime_text)
        tvSummary = findViewById(R.id.tv_summary)

        updateUI()

        btnStart.setOnClickListener {
            Log.d(TAG, "开始监听 clicked")
            if (hasBasicPermissions()) {
                prefs.edit().putBoolean("service_running", true).apply()
                startPhoneCallService()
                updateUI()
                Toast.makeText(this, "开始监听", Toast.LENGTH_SHORT).show()
            } else {
                requestBasicPermissions()
            }
        }

        btnStop.setOnClickListener {
            Log.d(TAG, "停止监听 clicked")
            prefs.edit().putBoolean("service_running", false).apply()
            stopPhoneCallService()
            updateUI()
            Toast.makeText(this, "已停止监听", Toast.LENGTH_SHORT).show()
        }

        btnTestRecord.setOnClickListener {
            Log.d(TAG, "测试录音 clicked")
            startTestRecording()
        }

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_RECORDING_STARTED)
            addAction(ACTION_RECORDING_STOPPED)
            addAction(ACTION_REALTIME_TEXT)
            addAction(ACTION_AI_RESULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        Log.d(TAG, "广播接收器已注册")
    }

    private fun hasBasicPermissions(): Boolean {
        val hasRecord = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "权限检查: RECORD_AUDIO=$hasRecord, READ_PHONE_STATE=$hasPhone")
        return hasRecord && hasPhone
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CODE)
        }
    }

    private fun startPhoneCallService() {
        try {
            val intent = Intent(this, PhoneCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "PhoneCallService 已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败: ${e.message}")
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPhoneCallService() {
        try {
            val intent = Intent(this, PhoneCallService::class.java)
            stopService(intent)
            Log.d(TAG, "PhoneCallService 已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败: ${e.message}")
        }
    }

    private fun startTestRecording() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先授予录音权限", Toast.LENGTH_SHORT).show()
                requestBasicPermissions()
                return
            }

            val audioDir = File(getExternalFilesDir(null), "recordings")
            if (!audioDir.exists()) audioDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val testFile = File(audioDir, "test_${timestamp}.mp4")
            testFilePath = testFile.absolutePath

            testRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            testRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(testFilePath)
                prepare()
                start()
            }

            Toast.makeText(this, "测试录音中 (5秒)...", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "测试录音开始: $testFilePath")

            // 5秒后停止
            btnTestRecord.postDelayed({
                stopTestRecording()
                Toast.makeText(this, "测试录音完成: ${testFile.name}", Toast.LENGTH_LONG).show()
                Log.d(TAG, "测试录音完成: $testFilePath")

                // 触发AI处理（转写+豆包总结）
                val recordedFile = testFilePath
                if (!recordedFile.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "正在调用豆包AI总结...", Toast.LENGTH_SHORT).show()
                    val (transcribedText, summary) = AIProcessor.processAudioFile(recordedFile)
                    runOnUiThread {
                        tvRealtimeText.text = "📝 转写内容\n" + (transcribedText?.take(500) ?: "")
                        tvSummary.text = "🤖 AI 总结\n" + (summary?.take(500) ?: "")
                        Toast.makeText(this@MainActivity, "AI处理完成！", Toast.LENGTH_SHORT).show()
                    }
                }
            }, 5000)

        } catch (e: Exception) {
            Log.e(TAG, "测试录音失败: ${e.message}")
            Toast.makeText(this, "测试录音失败: ${e.message}", Toast.LENGTH_LONG).show()
            try { testRecorder?.release() } catch (_: Exception) {}
            testRecorder = null
        }
    }

    private fun stopTestRecording() {
        try {
            testRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            Log.e(TAG, "停止测试录音失败: ${e.message}")
        }
        testRecorder = null
    }

    private fun updateUI() {
        val isRunning = prefs.getBoolean("service_running", false)
        if (isRunning) {
            tvStatus.text = "🟢 监听中，等待通话..."
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.text = "🔴 未启动"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) {
            val hasAudio = grantResults.getOrNull(permissions.indexOf(Manifest.permission.RECORD_AUDIO)) == PackageManager.PERMISSION_GRANTED
            val hasPhone = grantResults.getOrNull(permissions.indexOf(Manifest.permission.READ_PHONE_STATE)) == PackageManager.PERMISSION_GRANTED
            
            if (hasAudio && hasPhone) {
                prefs.edit().putBoolean("service_running", true).apply()
                startPhoneCallService()
                updateUI()
                Toast.makeText(this, "权限已授予，开始监听", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要录音和电话权限才能工作", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败: ${e.message}")
        }
        stopTestRecording()
    }
}
