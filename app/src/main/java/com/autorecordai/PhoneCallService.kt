package com.autorecordai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneCallService : Service() {

    private val TAG = "PhoneCallService"
    private lateinit var telephonyManager: TelephonyManager
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null
    private var callStartTime: Long = 0
    private var callerNumber: String? = null

    private val CHANNEL_ID = "phone_call_recording"
    private val NOTIFICATION_ID = 1001

    // 检查是否应该工作
    private fun shouldRecord(): Boolean {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("service_running", false)
    }

    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            Log.d(TAG, "通话状态变化: $state, 号码: $phoneNumber")

            // 检查是否应该工作
            if (!shouldRecord()) {
                Log.d(TAG, "服务未启动，跳过")
                return
            }

            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // 通话接通，开始录音
                    callerNumber = phoneNumber
                    callStartTime = System.currentTimeMillis()
                    startRecording(phoneNumber ?: "未知号码")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    // 通话结束，停止录音并处理
                    if (isRecording) {
                        stopRecordingAndProcess()
                    }
                }
                TelephonyManager.CALL_STATE_RINGING -> {
                    // 来电响铃（可选：录来电内容）
                    Log.d(TAG, "来电: $phoneNumber")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("通话录音服务运行中", "正在监听通话..."))

        // 获取电话管理器并监听通话状态
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        Log.d(TAG, "PhoneCallService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        if (isRecording) {
            stopRecordingAndProcess()
        }
        Log.d(TAG, "PhoneCallService 已停止")
    }

    private fun startRecording(phoneNumber: String) {
        if (isRecording) {
            Log.d(TAG, "已经在录音中，跳过")
            return
        }

        // 检查是否应该工作
        if (!shouldRecord()) {
            Log.d(TAG, "服务未启动，不录音")
            return
        }

        try {
            // 确保录音权限
            if (checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "没有录音权限")
                return
            }

            // 创建录音文件保存目录
            val audioDir = File(getExternalFilesDir(null), "recordings")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }

            // 生成文件名：日期_时间_号码.mp4
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val displayNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val fileName = "${timestamp}_${displayNumber}.mp4"
            currentFilePath = File(audioDir, fileName).absolutePath

            // 初始化 MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d(TAG, "开始录音: $currentFilePath")

            // 更新通知
            updateNotification("正在录音", "通话对象: $phoneNumber")

            // 发送Toast提示
            Toast.makeText(this, "开始录音: $phoneNumber", Toast.LENGTH_SHORT).show()

            // 发送广播：录音开始
            sendBroadcast(Intent(MainActivity.ACTION_RECORDING_STARTED))

        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
            e.printStackTrace()
            isRecording = false
            mediaRecorder = null
        }
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording || mediaRecorder == null) {
            return
        }

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            val duration = (System.currentTimeMillis() - callStartTime) / 1000
            Log.d(TAG, "录音结束，时长: ${duration}秒")

            // 更新通知
            updateNotification("录音完成", "通话录音已保存，正在处理...")

            // 显示完成提示
            Toast.makeText(this, "录音完成 (${duration}秒)，正在AI处理...", Toast.LENGTH_SHORT).show()

            // 发送广播：录音停止
            val stopIntent = Intent(MainActivity.ACTION_RECORDING_STOPPED).apply {
                putExtra("audio_path", currentFilePath)
            }
            sendBroadcast(stopIntent)

            // 异步处理录音文件（转文字+AI总结）
            currentFilePath?.let { path ->
                CoroutineScope(Dispatchers.IO).launch {
                    processRecording(path)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}")
            e.printStackTrace()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        }

        // 重置状态
        currentFilePath = null
        callerNumber = null

        // 更新通知为待命状态
        updateNotification("通话录音服务运行中", "等待下一个通话...")
    }

    private suspend fun processRecording(filePath: String) {
        try {
            // 步骤1：调用讯飞API转文字
            updateNotification("AI处理中", "正在转写文字...")
            val text = AIProcessor.transcribeWithXunfei(filePath)

            if (text.isNullOrEmpty()) {
                Log.e(TAG, "转写结果为空")
                showResultNotification("转写失败", "请检查讯飞API配置")
                return
            }

            Log.d(TAG, "转写完成，文字长度: ${text.length}")

            // 发送广播：转写文字（目前是一次性发送，实时显示需要流式识别）
            val textIntent = Intent(MainActivity.ACTION_REALTIME_TEXT).apply {
                putExtra("text", text)
            }
            sendBroadcast(textIntent)

            // 步骤2：调用豆包API总结
            updateNotification("AI处理中", "正在生成总结...")
            val summary = AIProcessor.summarizeWithDoubao(text)

            if (summary.isNullOrEmpty()) {
                Log.e(TAG, "总结结果为空")
                showResultNotification("总结失败", "请检查豆包API配置")
                return
            }

            Log.d(TAG, "总结完成，长度: ${summary.length}")

            // 发送广播：AI总结
            val summaryIntent = Intent(MainActivity.ACTION_AI_SUMMARY).apply {
                putExtra("summary", summary)
            }
            sendBroadcast(summaryIntent)

            // 步骤3：显示最终结果通知
            showResultNotification("通话总结已生成", summary.take(100) + "...")

            // 可选：保存记录到本地
            saveRecord(filePath, text, summary)

        } catch (e: Exception) {
            Log.e(TAG, "AI处理失败: ${e.message}")
            e.printStackTrace()
            showResultNotification("处理失败", "发生错误: ${e.message}")
        }
    }

    private fun saveRecord(filePath: String, text: String, summary: String) {
        try {
            val recordDir = File(getExternalFilesDir(null), "records")
            if (!recordDir.exists()) recordDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val recordFile = File(recordDir, "record_${dateFormat.format(Date())}.txt")
            recordFile.writeText("""
                |通话录音总结
                |==============
                |日期: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
                |时长: ${(System.currentTimeMillis() - callStartTime) / 1000}秒
                |
                |【原始录音】
                |$filePath
                |
                |【语音转文字】
                |$text
                |
                |【AI总结】
                |$summary
            """.trimMargin())
            Log.d(TAG, "记录已保存: ${recordFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存记录失败: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通话录音服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "通话录音服务的通知渠道"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun showResultNotification(title: String, content: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}
