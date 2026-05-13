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
            startPhoneService()
        } else {
            Toast.makeText(this, "部分权限被拒绝，功能可能不完整", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 延迟启动服务，等待Activity完全加载
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (hasAllPermissions()) {
                    startPhoneService()
                } else {
                    // 如果没有权限，显示提示
                    Toast.makeText(this@MainActivity, "请授予必要权限", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "启动服务失败: " + e.message)
                Toast.makeText(this@MainActivity, "服务启动失败: " + e.message, Toast.LENGTH_LONG).show()
            }
        }, 1000)  // 延迟1秒启动

        // 检查无障碍服务
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
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
        val serviceIntent = Intent(this, PhoneCallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "通话录音服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val pref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return pref.getBoolean("accessibility_service_enabled", false)
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
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面检查权限状态
        if (hasAllPermissions()) {
            findViewById<android.widget.TextView>(R.id.tv_status)?.text = "服务运行中 ✅"
        }
    }
}