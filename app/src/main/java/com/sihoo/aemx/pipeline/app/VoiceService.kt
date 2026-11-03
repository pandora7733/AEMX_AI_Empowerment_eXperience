package com.sihoo.aemx.pipeline.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.sihoo.aemx.R

/**
 * 호출어를 계속 듣는 포그라운드 서비스. 화면을 꺼도, 다른 앱이 앞에 있어도 마이크를 쓴다.
 *
 * 마이크 사용 유형(microphone)으로 올린다. 그래야 안드로이드가 백그라운드에서 녹음을 끊지 않는다.
 * 듣기와 실행은 [PipelineCore] 가 맡고, 이 서비스는 살려 두고 알림을 띄우는 역할만 한다.
 * 시작은 [start], 끝은 [stop] 으로 한다. 마이크 권한이 없으면 호출하는 쪽이 먼저 확인해야 한다.
 */
class VoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        PipelineCore.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PipelineCore.stopVoice()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // 시작 명령은 5초 안에 포그라운드로 올려야 한다. 준비보다 먼저 올린다.
        startForegroundCompat()
        PipelineCore.startVoice()
        // 죽으면 시스템이 다시 살린다. 다시 살아나면 마지막 설정으로 듣기를 이어 간다.
        return START_STICKY
    }

    override fun onDestroy() {
        PipelineCore.stopVoice()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_service_title))
            .setContentText(getString(R.string.voice_service_text))
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.voice_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val ACTION_STOP = "com.sihoo.aemx.action.STOP_VOICE"
        private const val CHANNEL_ID = "aemx_voice"
        private const val NOTIFICATION_ID = 1001

        /** 듣기를 켠다. 서비스가 이미 떠 있으면 그대로 둔다. */
        fun start(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 듣기를 끄고 서비스를 내린다. */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
