package com.autorecordai

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeChatAccessibilityService : AccessibilityService() {

    private val TAG = "WeChatAccessibility"
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null
    private var lastActivityTime = 0L

    // 微信通话相关关键词（检测UI元素）
    private val wechatCallKeywords = listOf(
        "通话中", "语音通话", "视频通话", "对方已加入", "连接中",
        "call in progress", "calling", "wechat call"
    )

    // 微信包名
    private val wechatPackage = "com.tencent.mm"

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != wechatPackage) return

        // 检查是否是微信电话相关界面
        if (isWeChatCallEvent(event)) {
            val currentTime = System.currentTimeMillis()
            
            // 防抖：500ms内的重复事件忽略
            if (currentTime - lastActivityTime < 500) return
            lastActivityTime = currentTime

            if (!isRecording) {
                startRecording()
            }
        } else {
            // 检测通话是否已结束
            if (isRecording && isWeChatCallEnded(event)) {
                stopRecording()
            }
        }
    }

    private fun isWeChatCallEvent(event: AccessibilityEvent): Boolean {
        val text = event.text?.joinToString(" ") ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // 检查事件类型（窗口变化、通知变化）
        if (event.eventType !in listOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            )
        ) return false

        // 检查是否包含通话关键词
        val combinedText = "$text $contentDesc".lowercase()
        return wechatCallKeywords.any { keyword ->
            combinedText.contains(keyword.lowercase())
        }
    }

    private fun isWeChatCallEnded(event: AccessibilityEvent): Boolean {
        // 如果检测到回到微信主界面或聊天列表，说明通话结束
        val text = event.text?.joinToString(" ") ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val combinedText = "$text $contentDesc"

        // 某些结束状态关键词
        val endKeywords = listOf("通话结束", "已结束", "已取消", "呼叫失败")
        return endKeywords.any { combinedText.contains(it) }
    }

    private fun startRecording() {
        if (isRecording) {
            Log.d(TAG, "已经在录音，跳过")
            return
        }

        try {
            // 开启扬声器，提高录音质量
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = true

            // 创建录音目录
            val audioDir = File(getExternalFilesDir(null), "wechat_recordings")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }

            // 生成文件名
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "wechat_${dateFormat.format(Date())}.mp4"
            currentFilePath = File(audioDir, fileName).absolutePath

            // 初始化 MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d(TAG, "微信电话录音开始: $currentFilePath")
            showNotification("微信电话录音中", "正在录制微信语音/视频通话")

        } catch (e: Exception) {
            Log.e(TAG, "微信电话录音启动失败: ${e.message}")
            e.printStackTrace()
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording || mediaRecorder == null) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            Log.d(TAG, "微信电话录音结束")

            // 关闭扬声器
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.isSpeakerphoneOn = false

            // 处理录音文件
            currentFilePath?.let { path ->
                showNotification("微信通话录音完成", "正在AI处理...")
                processWeChatRecording(path)
            }

        } catch (e: Exception) {
            Log.e(TAG, "停止微信录音失败: ${e.message}")
            e.printStackTrace()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        }

        currentFilePath = null
    }

    private fun processWeChatRecording(filePath: String) {
        // 使用 runBlocking 在 IO 线程处理
        runBlocking(Dispatchers.IO) {
            try {
                val text = AIProcessor.transcribeAudio(filePath)
                val summary = AIProcessor.summarizeWithDoubao(text ?: "")
                showResultNotification("微信通话总结", summary?.take(100) ?: "处理完成")
            } catch (e: Exception) {
                Log.e(TAG, "处理微信录音失败: ${e.message}")
            }
        }
    }

    private fun showNotification(title: String, content: String) {
        // 使用通知管理器发送通知
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            "wechat_recording",
            "微信电话录音",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification = androidx.core.app.NotificationCompat.Builder(this, "wechat_recording")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }

    private fun showResultNotification(title: String, content: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager

        val notification = androidx.core.app.NotificationCompat.Builder(this, "wechat_recording")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
            .build()

        notificationManager.notify(2002, notification)
    }

    override fun onInterrupt() {
        Log.d(TAG, "WeChatAccessibilityService 中断")
        if (isRecording) {
            stopRecording()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "WeChatAccessibilityService 已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
        Log.d(TAG, "WeChatAccessibilityService 已销毁")
    }
}