package com.autorecordai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var serviceIntent: Intent

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        val phoneGranted = results[Manifest.permission.READ_PHONE_STATE] ?: false

        if (audioGranted) {
            updateStatusView()
            Toast.makeText(applicationContext, "✅ 权限已授予", Toast.LENGTH_SHORT).show()
            startPhoneCallService()
        } else {
            Toast.makeText(applicationContext, "❌ 需要录音权限才能录音", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(applicationContext, "通知权限已授予", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceIntent = Intent(this, PhoneCallService::class.java)

        findViewById<android.widget.Button>(R.id.btn_start_service).setOnClickListener {
            checkPermissionsAndStartService()
        }

        findViewById<android.widget.Button>(R.id.btn_open_settings).setOnClickListener {
            openPermissionSettings()
        }

        findViewById<android.widget.Button>(R.id.btn_enable_accessibility).setOnClickListener {
            openAccessibilitySettings()
        }

        updateStatusView()
    }

    private fun checkPermissionsAndStartService() {
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val phonePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)

        if (audioPermission == PackageManager.PERMISSION_GRANTED) {
            startPhoneCallService()
        } else {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (phonePermission != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_PHONE_STATE)
            }
            phonePermissionLauncher.launch(permissions.toTypedArray())
        }

        // 请求通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startPhoneCallService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(applicationContext, "📞 通话录音服务已启动", Toast.LENGTH_SHORT).show()
        updateStatusView()
    }

    private fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Toast.makeText(
            applicationContext,
            "请在列表中找到「自动录音AI」并开启",
            Toast.LENGTH_LONG
        ).show()
        startActivity(intent)
    }

    private fun updateStatusView() {
        val tvStatus = findViewById<android.widget.TextView>(R.id.tv_status)
        val tvPhoneStatus = findViewById<android.widget.TextView>(R.id.tv_phone_status)
        val tvWechatStatus = findViewById<android.widget.TextView>(R.id.tv_wechat_status)

        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (audioGranted) {
            tvStatus.text = "✅ 服务已就绪"
            tvPhoneStatus.text = "✅ 已开启"
        } else {
            tvStatus.text = "⚠️ 需要授权"
            tvPhoneStatus.text = "❌ 未授权"
        }

        // 检测无障碍服务是否开启
        val accessibilityEnabled = try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabledServices?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }

        tvWechatStatus.text = if (accessibilityEnabled) "✅ 已开启" else "⚠️ 未开启"
        tvWechatStatus.setTextColor(
            if (accessibilityEnabled) resources.getColor(R.color.success, theme)
            else resources.getColor(R.color.warning, theme)
        )
    }

    override fun onResume() {
        super.onResume()
        updateStatusView()
    }
}
