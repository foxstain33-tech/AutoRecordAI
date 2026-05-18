package com.autorecordai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class PhoneCallService : Service() {

    private val TAG = "PhoneCallService"
    private val CHANNEL_ID = "phone_call_recording"
    private val NOTIFICATION_ID = 1001

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        try {
            createNotificationChannel()

            // Android 14+ 必须传入 foregroundServiceType
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
                        Log.d(TAG, "通话中")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "通话结束")
                    }
                }
            }
        }
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
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
}
