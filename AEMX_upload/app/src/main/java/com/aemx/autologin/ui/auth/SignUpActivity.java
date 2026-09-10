package com.aemx.autologin.ui.auth;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.aemx.autologin.R;
import com.aemx.autologin.databinding.ActivitySignUpBinding;

/**
 * 회원가입 화면.
 * AEMX 마스터 계정을 만드는 입력 폼의 껍데기이며, 실제 가입 처리는 하지 않는다.
 */
public class SignUpActivity extends AppCompatActivity {

    /** activity_sign_up.xml 의 ViewBinding */
    private ActivitySignUpBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ViewBinding 으로 레이아웃을 붙인다.
        binding = ActivitySignUpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setUpToolbar();
        setUpActions();
    }

    /** 툴바 뒤로가기 버튼 처리 */
    private void setUpToolbar() {
        binding.toolbarSignUp.setNavigationOnClickListener(v -> finish());
    }

    /** 버튼·링크 클릭 동작 연결 (껍데기이므로 화면 전환과 안내 메시지만 담당) */
    private void setUpActions() {
        // 가입하기 → 준비 중 안내 후 이전 화면(로그인)으로 복귀
        binding.btnSignUp.setOnClickListener(v -> {
            showNotImplemented();
            finish();
        });

        // "로그인" 링크 → 이전 화면인 로그인으로 그대로 되돌아간다.
        binding.tvGoLogin.setOnClickListener(v -> finish());

        // 안내 문구를 눌러도 같은 동작이 되도록 하여 터치 영역을 넓힌다.
        binding.tvGoLoginLabel.setOnClickListener(v -> finish());
    }

    /** 미구현 기능 공통 안내 */
    private void showNotImplemented() {
        Toast.makeText(this, R.string.toast_not_implemented, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 액티비티가 사라질 때 바인딩 참조를 정리한다.
        binding = null;
    }
}
