package com.sihoo.aemx.pipeline.app

import android.content.Context

/**
 * 호출어 듣기를 자동으로 켤지 정하는 순수 규칙. 안드로이드 없이 테스트한다.
 *
 * 자동 시작은 마이크 권한이 있고, 사용자가 마이크 버튼으로 끄지 않았고, 이미 돌고 있지 않을 때만 한다.
 */
object VoiceAutoStart {

    fun shouldAutoStart(micGranted: Boolean, userStopped: Boolean, running: Boolean, quit: Boolean = false): Boolean =
        micGranted && !userStopped && !running && !quit

    /**
     * 서비스는 떠 있는데 마이크가 꺼져 있을 때 다시 켤지. 준비가 실패했거나 무음으로 막힌 뒤 화면이 올라오면
     * 다시 시도한다. 사용자가 끈 상태([userStopped])와 여는 중([micIdle] 거짓)에는 하지 않는다.
     */
    /**
     * 마이크 포그라운드 서비스를 올리다 생긴 예외가 백그라운드 시작 제한 때문인지. 안드로이드 14 이후에는 화면에 없는
     * 앱이 마이크 타입 서비스를 올리면 SecurityException(또는 ForegroundServiceStartNotAllowedException)이 난다.
     */
    fun isBackgroundStartBlock(error: Throwable): Boolean =
        error is SecurityException || error.javaClass.simpleName == "ForegroundServiceStartNotAllowedException"

    fun shouldResumeIdle(micGranted: Boolean, userStopped: Boolean, running: Boolean, micIdle: Boolean): Boolean =
        micGranted && !userStopped && running && micIdle

    /**
     * 완전히 종료한 상태([QuitPrefs])를 지울지. 부팅과 업데이트는 새로 시작하는 것이라 지운다.
     * 그 밖에는 사용자가 앱을 열거나 마이크를 켤 때 지운다.
     */
    fun clearsQuit(action: String?): Boolean = action == BOOT_COMPLETED || action == PACKAGE_REPLACED

    /**
     * 앱이 업데이트되어 새로 설치됐을 때 예전의 끈 상태를 지울지. 업데이트는 사용자가 새로 설치한 것이므로
     * 끈 상태가 남아 자동 시작을 영영 막지 않게 지운다. 부팅에서는 지우지 않는다(끈 상태를 존중한다).
     */
    fun clearsUserStop(action: String?): Boolean = action == PACKAGE_REPLACED

    /**
     * 듣기가 돌고 있는데 마이크가 무음으로 막혔으면 앱이 화면에 올라왔을 때 한 번 다시 시작할지. 화면이 떠 있으면
     * 마이크 서비스를 새로 올릴 수 있어서 백그라운드 제한을 피한다.
     */
    fun shouldRetryMic(silenced: Boolean, running: Boolean): Boolean = silenced && running

    private const val PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
}

/**
 * 빠른 메뉴의 "완전히 종료"를 누른 상태. 참인 동안은 앱이 스스로 마이크를 켜지 않는다.
 *
 * 접근성 서비스는 켜 둔 채로 끝내기 때문에 안드로이드가 프로세스를 다시 띄워 서비스를 연결한다. 그때 듣기가
 * 되살아나지 않도록 저장해 둔다. 사용자가 앱을 열거나 마이크를 켜면, 또는 부팅하거나 업데이트하면 지운다.
 * 사용자가 마이크 버튼으로 끈 상태([VoicePrefs])는 건드리지 않아서, 다시 열면 종료 전 설정대로 돌아간다.
 */
object QuitPrefs {

    private const val FILE = "aemx_voice"
    private const val KEY_QUIT = "app_quit"

    fun isQuit(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_QUIT, false)

    /** 바로 뒤에 프로세스를 끝낼 수 있어서 저장이 끝난 뒤에 돌아온다(commit). */
    fun setQuit(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_QUIT, value).commit()
    }
}

/**
 * 사용자가 마이크 버튼으로 호출어 듣기를 끈 상태를 저장한다. 프로세스가 죽거나 기기가 부팅되거나 앱이
 * 업데이트돼도 이 값이 참이면 자동으로 켜지 않는다. 사용자가 다시 켜면 거짓으로 바꾼다.
 */
object VoicePrefs {

    private const val FILE = "aemx_voice"
    private const val KEY_USER_STOPPED = "user_stopped"

    fun userStopped(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_USER_STOPPED, false)

    fun setUserStopped(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_USER_STOPPED, value).apply()
    }
}
