package com.sihoo.aemx.pipeline.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sihoo.aemx.R
import com.sihoo.aemx.pipeline.stt.MicStatus

/**
 * 호출어를 계속 듣는 포그라운드 서비스. 화면을 꺼도, 다른 앱이 앞에 있어도 마이크를 쓴다.
 *
 * 마이크 사용 유형(microphone)으로 올린다. 그래야 안드로이드가 백그라운드에서 녹음을 끊지 않는다.
 * 듣기와 실행은 [PipelineCore] 가 맡고, 이 서비스는 살려 두고 알림을 띄우는 역할만 한다.
 *
 * 켜고 끄는 방법은 세 가지다. [start] 는 사용자가 버튼으로 켠 것이라 끈 상태를 지운다. [stop] 은 사용자가 끈 것이라
 * 끈 상태를 저장한다. [autoStart] 는 앱이 스스로 켜는 것(준비가 끝남, 부팅, 업데이트, 최근 앱에서 지운 뒤)이라
 * 마이크 권한이 있고 사용자가 끄지 않았을 때만 켠다([VoiceAutoStart]).
 */
class VoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        running = true
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
        // 마이크가 무음으로 막혔던 듣기를 화면이 올라온 뒤 다시 시작한다. 아래의 시작 경로로 이어진다.
        if (intent?.action == ACTION_RETRY_MIC) PipelineCore.stopVoice()
        // 시작 명령은 5초 안에 포그라운드로 올려야 한다. 준비보다 먼저 올린다. 이미 듣고 있으면 startVoice 가 아무것도 하지 않는다.
        // 올리지 못하면 서비스를 내리고 되살리지 않는다. 같은 프로세스의 접근성 서비스가 이 예외로 죽지 않게 하려는 것이다.
        if (!startForegroundCompat()) return START_NOT_STICKY
        PipelineCore.startVoice()
        // 죽으면 시스템이 다시 살린다. 다시 살아나면 마지막 설정으로 듣기를 이어 간다.
        return START_STICKY
    }

    /**
     * 앱을 최근 앱 목록에서 지워도 듣기는 이어진다. 사용자가 끄지 않았다면 서비스를 다시 올려 둔다.
     * 이미 돌고 있으면 [autoStart] 가 아무것도 하지 않는다.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!VoicePrefs.userStopped(this)) autoStart(this)
    }

    override fun onDestroy() {
        running = false
        PipelineCore.stopVoice()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 마이크 포그라운드로 올린다. 실패하면 예외를 잡아 로그만 남기고 서비스를 내린다(프로세스는 죽이지 않는다).
     * 사용자가 끈 상태([VoicePrefs])는 바꾸지 않는다. 성공하면 백그라운드 차단 표시를 지운다.
     */
    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            backgroundStartBlocked = false
            true
        } catch (e: RuntimeException) {
            val blocked = VoiceAutoStart.isBackgroundStartBlock(e)
            backgroundStartBlocked = blocked
            Log.w(TAG, "호출어 듣기를 포그라운드로 올리지 못했다: ${if (blocked) "백그라운드 시작 제한" else "기타"} (${e.javaClass.simpleName})")
            stopSelf()
            false
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
        private const val ACTION_RETRY_MIC = "com.sihoo.aemx.action.RETRY_MIC"
        private const val ACTION_RESUME = "com.sihoo.aemx.action.RESUME"
        private const val CHANNEL_ID = "aemx_voice"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AemxVoiceService"

        /** 서비스가 지금 떠 있는지. onCreate 에서 참, onDestroy 에서 거짓. */
        @Volatile
        var running = false
            private set

        /**
         * 마지막으로 마이크 포그라운드 시작이 백그라운드 제한으로 막혔는지. 앱이 화면에 올라오면 [resumeIfIdle] 이 다시 시도한다.
         * 시작에 성공하면 거짓으로 돌아간다. 사용자가 끈 상태와는 별개다.
         */
        @Volatile
        var backgroundStartBlocked = false
            private set

        /** 사용자가 버튼으로 듣기를 켰다. 끈 상태를 지운다. 서비스가 이미 떠 있으면 그대로 둔다. */
        fun start(context: Context) {
            VoicePrefs.setUserStopped(context, false)
            QuitPrefs.setQuit(context, false)
            startService(context)
        }

        /**
         * 앱이 스스로 켠다. 마이크 권한이 있고, 사용자가 끄지 않았고, 돌고 있지 않을 때만 켠다. 안드로이드가
         * 백그라운드 마이크 서비스 시작을 막으면 예외를 잡고 false 를 돌려준다(사용자가 앱을 열면 다시 시도한다).
         */
        fun autoStart(context: Context): Boolean {
            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val should = VoiceAutoStart.shouldAutoStart(
                micGranted = micGranted,
                userStopped = VoicePrefs.userStopped(context),
                running = running,
                quit = QuitPrefs.isQuit(context),
            )
            if (!should) return false
            // 진단: 배터리 최적화 제외 여부를 남긴다. 백그라운드 시작이 막히면 이 값과 함께 원인을 본다.
            Log.i(TAG, "자동 시작 시도: 배터리 최적화 제외=${isIgnoringBatteryOptimizations(context)}")
            return try {
                startService(context)
                true
            } catch (e: RuntimeException) {
                // 부팅 직후처럼 백그라운드에서 마이크 서비스를 올릴 수 없는 경우. 예외 종류만 남긴다.
                Log.w(TAG, "호출어 자동 시작을 하지 못했다: ${e.javaClass.simpleName}")
                false
            }
        }

        /**
         * 듣기가 돌고 있는데 마이크가 무음으로 막혔었다면 다시 시작한다. 앱이 화면에 올라왔을 때 부른다(화면이 떠 있으면
         * 마이크 서비스를 올릴 수 있다). 다시 시작했으면 참이다.
         */
        fun retryIfSilenced(context: Context): Boolean {
            if (!VoiceAutoStart.shouldRetryMic(silenced = MicStatus.silenced, running = running)) return false
            Log.i(TAG, "무음이던 마이크를 앱이 화면에 올라온 뒤 다시 시작한다")
            return try {
                val intent = Intent(context, VoiceService::class.java).setAction(ACTION_RETRY_MIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "무음 마이크를 다시 시작하지 못했다: ${e.javaClass.simpleName}")
                false
            }
        }

        /**
         * 서비스가 떠 있는데 마이크가 꺼져 있으면 다시 켠다(준비 실패, 무음으로 막힌 뒤 등). 서비스가 없으면 [autoStart] 와
         * 같다. 사용자가 끈 상태면 하지 않는다. 화면이 올라왔을 때와 접근성이 연결될 때 부른다.
         */
        fun resumeIfIdle(context: Context): Boolean {
            // 빠른 메뉴에서 잠시 끈 마이크는 사용자가 다시 켤 때까지 그대로 둔다.
            if (PipelineCore.micPaused) return false
            // 완전히 종료한 뒤에는 사용자가 앱을 열거나 마이크를 켤 때까지 아무것도 다시 켜지 않는다.
            if (QuitPrefs.isQuit(context)) return false
            if (!running) {
                if (backgroundStartBlocked) Log.i(TAG, "백그라운드에서 막혔던 자동 시작을 앱이 화면에 올라와 다시 시도한다")
                return autoStart(context)
            }
            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val should = VoiceAutoStart.shouldResumeIdle(
                micGranted = micGranted,
                userStopped = VoicePrefs.userStopped(context),
                running = running,
                micIdle = PipelineCore.isMicIdle(),
            )
            if (!should) return false
            Log.i(TAG, "꺼져 있던 호출어 듣기를 앱이 다시 켠다")
            return try {
                val intent = Intent(context, VoiceService::class.java).setAction(ACTION_RESUME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "꺼진 호출어 듣기를 다시 켜지 못했다: ${e.javaClass.simpleName}")
                false
            }
        }

        /** 배터리 최적화 제외 여부(진단 로그용). 제외돼 있어도 백그라운드 시작 제한이 풀린다고 확인되지는 않았다. */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java) ?: return false
            return power.isIgnoringBatteryOptimizations(context.packageName)
        }

        /** 듣기를 끄고 서비스를 내린다. 끈 상태는 저장해서 부팅 뒤에도 자동으로 켜지 않는다. */
        fun stop(context: Context) {
            VoicePrefs.setUserStopped(context, true)
            val intent = Intent(context, VoiceService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        private fun startService(context: Context) {
            val intent = Intent(context, VoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
