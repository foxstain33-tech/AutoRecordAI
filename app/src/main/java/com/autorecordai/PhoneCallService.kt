package com.autorecordai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneCallService : Service() {

    private val TAG = "PhoneCallService"
    private val CHANNEL_ID = "phone_call_recording"
    private val NOTIFICATION_ID = 1001

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var recorder: MediaRecorder? = null
    private var currentRecordingFile: String? = null
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        try {
            createNotificationChannel()
            val notification = createNotification("通话录音服务", "正在监听通话状态...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "前台服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败: ${e.message}", e)
            stopSelf()
            return
        }

        try {
            telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            registerPhoneStateListener()
        } catch (e: Exception) {
            Log.e(TAG, "PhoneStateListener 注册失败: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        val tm = telephonyManager ?: return
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in API 31")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.d(TAG, "onCallStateChanged: state=$state")
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "来电响铃: $phoneNumber")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "通话中，开始录音")
                        startRecording(phoneNumber)
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "通话结束，停止录音")
                        stopRecording()
                    }
                }
            }
        }
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener registered")
    }

    private fun startRecording(phoneNumber: String?) {
        if (isRecording) {
            Log.w(TAG, "已在录音中，跳过")
            return
        }
        try {
            val audioDir = File(getExternalFilesDir(null), "recordings")
            if (!audioDir.exists()) audioDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeNumber = phoneNumber?.replace(Regex("[^0-9+]"), "unknown") ?: "unknown"
            val recordFile = File(audioDir, "call_${safeNumber}_${timestamp}.mp4")
            currentRecordingFile = recordFile.absolutePath

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentRecordingFile)
                prepare()
                start()
            }

            isRecording = true
            updateNotification("正在录音", "通话中...")

            // 通知 MainActivity
            val intent = Intent("com.autorecordai.RECORDING_STARTED")
            intent.setPackage(packageName)
            sendBroadcast(intent)

            Log.d(TAG, "录音开始: $currentRecordingFile")
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}", e)
            recorder = null
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recorder?.apply {
                stop()
                release()
            }
            Log.d(TAG, "录音已保存: $currentRecordingFile")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}", e)
        }
        recorder = null
        isRecording = false

        updateNotification("通话录音服务", "正在处理AI转写与总结...")

        // 通知 MainActivity 录音结束
        val intent = Intent("com.autorecordai.RECORDING_STOPPED")
        intent.setPackage(packageName)
        intent.putExtra("file_path", currentRecordingFile)
        sendBroadcast(intent)

        // 自动触发 AI 转写 + 总结
        val recordedFile = currentRecordingFile
        currentRecordingFile = null
        if (!recordedFile.isNullOrEmpty()) {
            AIProcessor.processAudioFile(recordedFile) { transcribedText, summary ->
                Log.d(TAG, "AI处理完成 - 转写:${transcribedText?.take(50)} 总结:${summary?.take(50)}")

                // 通知 MainActivity 显示 AI 结果
                val resultIntent = Intent("com.autorecordai.AI_RESULT")
                resultIntent.setPackage(packageName)
                resultIntent.putExtra("transcribed_text", transcribedText ?: "")
                resultIntent.putExtra("summary", summary ?: "")
                sendBroadcast(resultIntent)

                updateNotification("通话录音服务", "AI总结完成！")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopRecording()
        try {
            val tm = telephonyManager
            val listener = phoneStateListener
            if (tm != null && listener != null) {
                tm.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "unlisten failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "通话录音", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val notification = createNotification(title, content)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}")
        }
    }
}
