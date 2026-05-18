package com.autorecordai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val REQUEST_CODE = 1001
    private val ACTION_RECORDING_STARTED = "com.autorecordai.RECORDING_STARTED"
    private val ACTION_RECORDING_STOPPED = "com.autorecordai.RECORDING_STOPPED"
    private val ACTION_REALTIME_TEXT = "com.autorecordai.REALTIME_TEXT"
    private val ACTION_AI_SUMMARY = "com.autorecordai.AI_SUMMARY"

    private lateinit var prefs: SharedPreferences
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRealtimeText: TextView
    private lateinit var tvSummary: TextView

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
                ACTION_AI_SUMMARY -> {
                    val summary = intent.getStringExtra("summary") ?: ""
                    runOnUiThread {
                        tvSummary.text = "🤖 AI 总结\n$summary"
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
        tvStatus = findViewById(R.id.tv_status)
        tvRealtimeText = findViewById(R.id.tv_realtime_text)
        tvSummary = findViewById(R.id.tv_summary)

        updateUI()

        btnStart.setOnClickListener {
            Log.d(TAG, "开始监听 clicked")
            if (checkAndRequestPermissions()) {
                prefs.edit().putBoolean("service_running", true).apply()
                startPhoneCallService()
                updateUI()
                Toast.makeText(this, "开始监听", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            Log.d(TAG, "停止监听 clicked")
            prefs.edit().putBoolean("service_running", false).apply()
            stopPhoneCallService()
            updateUI()
            Toast.makeText(this, "已停止监听", Toast.LENGTH_SHORT).show()
        }

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_RECORDING_STARTED)
            addAction(ACTION_RECORDING_STOPPED)
            addAction(ACTION_REALTIME_TEXT)
            addAction(ACTION_AI_SUMMARY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        Log.d(TAG, "广播接收器已注册")
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

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CODE)
            return false
        }

        return true
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
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                prefs.edit().putBoolean("service_running", true).apply()
                startPhoneCallService()
                updateUI()
            } else {
                Toast.makeText(this, "需要权限才能正常工作", Toast.LENGTH_LONG).show()
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
    }
}
