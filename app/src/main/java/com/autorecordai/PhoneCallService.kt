package com.autorecordai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private val CHANNEL_ID = "phone_call_recording"
    private val NOTIFICATION_ID = 1001

    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    private fun shouldRecord(): Boolean {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("service_running", false)
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        Log.d(TAG, "通话状态变化: $state, 号码: $phoneNumber")

        if (!shouldRecord()) {
            Log.d(TAG, "服务未启动，跳过")
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStartTime = System.currentTimeMillis()
                startRecording(phoneNumber ?: "未知号码")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isRecording) {
                    stopRecordingAndProcess()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "来电: $phoneNumber")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("通话录音服务运行中", "正在监听通话..."))

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state, phoneNumber)
            }
        }

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        Log.d(TAG, "PhoneCallService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        super.onDestroy()
        phoneStateListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
        if (isRecording) {
            try { stopRecordingAndProcess() } catch (_: Exception) {}
        }
        Log.d(TAG, "PhoneCallService 已停止")
    }

    private fun startRecording(phoneNumber: String) {
        if (isRecording) { Log.d(TAG, "已经在录音中，跳过"); return }
        if (!shouldRecord()) { Log.d(TAG, "服务未启动，不录音"); return }

        try {
            if (checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "没有录音权限"); return
            }

            val audioDir = File(getExternalFilesDir(null), "recordings")
            if (!audioDir.exists()) audioDir.mkdirs()

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val displayNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val fileName = "${timestamp}_${displayNumber}.mp4"
            currentFilePath = File(audioDir, fileName).absolutePath

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
            updateNotification("正在录音", "通话对象: $phoneNumber")
            Toast.makeText(this, "开始录音: $phoneNumber", Toast.LENGTH_SHORT).show()
            sendBroadcast(Intent(MainActivity.ACTION_RECORDING_STARTED))

        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
            e.printStackTrace()
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
        }
    }

    private fun stopRecordingAndProcess() {
        if (!isRecording || mediaRecorder == null) return

        try {
            mediaRecorder?.apply { stop(); release() }
            mediaRecorder = null
            isRecording = false
            val duration = (System.currentTimeMillis() - callStartTime) / 1000
            Log.d(TAG, "录音结束，时长: ${duration}秒")
            updateNotification("录音完成", "正在AI处理...")
            Toast.makeText(this, "录音完成 (${duration}秒)", Toast.LENGTH_SHORT).show()

            sendBroadcast(Intent(MainActivity.ACTION_RECORDING_STOPPED).apply {
                putExtra("audio_path", currentFilePath)
            })

            currentFilePath?.let { path ->
                CoroutineScope(Dispatchers.IO).launch { processRecording(path) }
            }

        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}")
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            isRecording = false
        }

        currentFilePath = null
        updateNotification("通话录音服务运行中", "等待下一个通话...")
    }

    private suspend fun processRecording(filePath: String) {
        try {
            updateNotification("AI处理中", "正在转写...")
            val text = AIProcessor.transcribeWithXunfei(filePath)
            if (!text.isNullOrEmpty()) {
                sendBroadcast(Intent(MainActivity.ACTION_REALTIME_TEXT).apply { putExtra("text", text) })
            }

            updateNotification("AI处理中", "正在生成总结...")
            val summary = AIProcessor.summarizeWithDoubao(text ?: "")
            if (!summary.isNullOrEmpty()) {
                sendBroadcast(Intent(MainActivity.ACTION_AI_SUMMARY).apply { putExtra("summary", summary) })
                showResultNotification("通话总结已生成", summary.take(80) + "...")
            } else {
                showResultNotification("总结失败", "请检查API配置")
            }
            saveRecord(filePath, text ?: "", summary ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "AI处理失败: ${e.message}")
            showResultNotification("处理失败", "错误: ${e.message}")
        }
    }

    private fun saveRecord(filePath: String, text: String, summary: String) {
        try {
            val recordDir = File(getExternalFilesDir(null), "records")
            if (!recordDir.exists()) recordDir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val f = File(recordDir, "record_${dateFormat.format(Date())}.txt")
            f.writeText("""通话录音总结
日期: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}

【录音文件】$filePath

【语音转文字】$text

【AI总结】$summary
""")
            Log.d(TAG, "记录已保存: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存记录失败: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "通话录音服务", NotificationManager.IMPORTANCE_LOW)
        channel.description = "通话录音服务通知"
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun showResultNotification(title: String, content: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
    }
}