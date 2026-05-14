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
import android.widget.Button
import android.widget.TextView
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
            // 权限授予后启动服务
            startServices()
            updateUI()
        } else {
            Toast.makeText(this, "部分权限被拒绝: " + denied.joinToString(), Toast.LENGTH_LONG).show()
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Toast.makeText(this, "App已打开 v6", Toast.LENGTH_SHORT).show()

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        val btnPermissions = findViewById<Button>(R.id.btn_request_permissions)
        val btnAccessibility = findViewById<Button>(R.id.btn_accessibility)
        val btnStartService = findViewById<Button>(R.id.btn_start_service)

        btnPermissions.setOnClickListener {
            requestPermissions()
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStartService.setOnClickListener {
            // 启动服务
            startServices()
        }
    }

    private fun checkPermissions() {
        if (hasAllPermissions()) {
            findViewById<TextView>(R.id.tv_status)?.text = "权限已授予 ✅"
            // 检查无障碍服务
            if (isAccessibilityServiceEnabled()) {
                findViewById<TextView>(R.id.tv_status)?.text = "就绪 ✅"
                findViewById<Button>(R.id.btn_start_service)?.visibility = Button.VISIBLE
            } else {
                showAccessibilityDialog()
            }
        } else {
            findViewById<TextView>(R.id.tv_status)?.text = "需要授予权限"
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

    private fun updateUI() {
        if (hasAllPermissions()) {
            if (isAccessibilityServiceEnabled()) {
                findViewById<TextView>(R.id.tv_status)?.text = "运行中 ✅"
            } else {
                findViewById<TextView>(R.id.tv_status)?.text = "权限已授予，请开启无障碍服务"
            }
        }
    }

    private fun startServices() {
        // 启动电话录音服务
        val phoneServiceIntent = Intent(this, PhoneCallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(phoneServiceIntent)
        } else {
            startService(phoneServiceIntent)
        }
        Toast.makeText(this, "电话录音服务已启动", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "PhoneCallService started")
        
        // 保存状态
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accessibility_service_enabled", true)
            .apply()
        
        updateUI()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val pref = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return pref.getBoolean("accessibility_service_enabled", false)
    }

    companion object {
        private const val TAG = "MainActivity"
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

    override fun onResume() {
        super.onResume()
        // 每次回到界面检查权限状态
        checkPermissions()
    }
}
