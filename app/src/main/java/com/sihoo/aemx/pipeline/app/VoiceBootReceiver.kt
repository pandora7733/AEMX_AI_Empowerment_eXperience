package com.sihoo.aemx.pipeline.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅이 끝나거나 앱이 업데이트되면 호출어 듣기를 스스로 다시 켠다([VoiceService.autoStart]).
 * 마이크 권한이 없거나 사용자가 끈 상태면 아무것도 하지 않는다. 시작을 막히면 조용히 넘어간다.
 */
class VoiceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (VoiceAutoStart.clearsUserStop(action)) VoicePrefs.setUserStopped(context, false)
        if (VoiceAutoStart.clearsQuit(action)) QuitPrefs.setQuit(context, false)
        VoiceService.autoStart(context)
    }
}
