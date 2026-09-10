package com.aemx.autologin.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aemx.autologin.R
import com.aemx.autologin.databinding.ActivityLoginBinding
import com.aemx.autologin.ui.main.MainActivity

/**
 * 마스터 계정 로그인 화면.
 * 껍데기 단계이므로 입력값 검증 없이 화면 전환과 안내 토스트만 처리한다.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpClickListeners()
    }

    /** 화면의 모든 클릭 동작을 한곳에 모아 연결한다. */
    private fun setUpClickListeners() = with(binding) {
        // 로그인 → 메인 화면으로 이동하고 로그인 화면은 스택에서 제거한다.
        btnLogin.setOnClickListener {
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }

        // 회원가입 화면(Java 액티비티)으로 이동. 돌아올 수 있도록 finish 하지 않는다.
        tvGoSignUp.setOnClickListener {
            startActivity(Intent(this@LoginActivity, SignUpActivity::class.java))
        }

        // 아래 항목들은 아직 준비 중인 기능이라 안내 토스트만 띄운다.
        btnBiometricLogin.setOnClickListener { showNotImplemented() }
        tvForgotPassword.setOnClickListener { showNotImplemented() }
    }

    /** 미구현 기능 공통 안내. */
    private fun showNotImplemented() {
        Toast.makeText(this, R.string.toast_not_implemented, Toast.LENGTH_SHORT).show()
    }
}
