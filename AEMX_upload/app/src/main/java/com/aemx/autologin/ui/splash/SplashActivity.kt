package com.aemx.autologin.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.aemx.autologin.databinding.ActivitySplashBinding
import com.aemx.autologin.ui.auth.LoginActivity

/**
 * 앱의 시작 화면(스플래시).
 * 브랜드 로고를 잠시 보여준 뒤 로그인 화면으로 자동 이동한다.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    // 지연 이동용 핸들러. 액티비티가 사라지면 콜백을 반드시 제거해 메모리 릭을 막는다.
    private val handler = Handler(Looper.getMainLooper())

    // 필드로 보관해야 onDestroy 에서 동일한 인스턴스를 removeCallbacks 할 수 있다.
    private val navigateToLogin = Runnable {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 로딩 인디케이터는 실제 작업 없이 준비 중인 느낌만 주는 연출이다.
        binding.progressSplash.visibility = View.VISIBLE

        handler.postDelayed(navigateToLogin, SPLASH_DELAY_MS)
    }

    override fun onDestroy() {
        // 화면이 먼저 종료되는 경우를 대비해 대기 중인 이동 작업을 취소한다.
        handler.removeCallbacks(navigateToLogin)
        super.onDestroy()
    }

    private companion object {
        /** 스플래시 노출 시간(ms). */
        const val SPLASH_DELAY_MS = 1500L
    }
}
