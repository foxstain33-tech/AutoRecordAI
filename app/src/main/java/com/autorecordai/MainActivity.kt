package com.autorecordai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val TAG = "AutoRecordAI"

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.map { it.key }
        if (denied.isEmpty()) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
            // 延迟2秒后再启动服务，方便看到Toast
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startPhoneService()
                } catch (e: Exception) {
                    Log.e(TAG, "启动服务失败: " + e.message)
                    Toast.makeText(this, "启动服务失败: " + e.message, Toast.LENGTH_LONG).show()
                }
            }, 2000)
        } else {
            Toast.makeText(this, "部分权限被拒绝，功能可能不完整", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "onCreate 开始")
        Toast.makeText(this, "App已打开 v5", Toast.LENGTH_LONG).show()
        Log.d(TAG, "Toast已显示")

        if (hasAllPermissions()) {
            Log.d(TAG, "已有权限，启动服务")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startPhoneService()
                } catch (e: Exception) {
                    Log.e(TAG, "启动服务失败: " + e.message)
                    Toast.makeText(this, "启动服务失败: " + e.message, Toast.LENGTH_LONG).show()
                }
            }, 2000)
        } else {
            Log.d(TAG, "请求权限")
            requestPermissions()
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

    private fun startPhoneService() {
        Log.d(TAG, "startPhoneService 被调用")
        val serviceIntent = Intent(this, PhoneCallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "通话录音服务已启动", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "startForegroundService 已调用")
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val pref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return pref.getBoolean("accessibility_service_enabled", false)
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("启用微信电话监听")
            .setMessage("要监听微信电话，需要启用无障碍服务。\n\n请前往：设置 → 辅助功能 → 找到「自动录音AI」并开启")
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            try {
                findViewById<android.widget.TextView>(R.id.tv_status)?.text = "服务运行中 ✅"
            } catch (e: Exception) {
                Log.e(TAG, "更新状态失败: " + e.message)
            }
        }
    }
}
