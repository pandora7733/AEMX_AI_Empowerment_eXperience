package com.aemx.autologin.ui.accounts;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.aemx.autologin.R;
import com.aemx.autologin.databinding.ActivityAddAccountBinding;

/**
 * 계정 추가 화면.
 * 사이트 정보를 입력받는 폼의 껍데기이며, 실제 저장·암호화는 하지 않는다.
 */
public class AddAccountActivity extends AppCompatActivity {

    /** activity_add_account.xml 의 ViewBinding */
    private ActivityAddAccountBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ViewBinding 으로 레이아웃을 붙인다.
        binding = ActivityAddAccountBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setUpToolbar();
        setUpActions();
    }

    /** 툴바 뒤로가기 버튼 처리 */
    private void setUpToolbar() {
        binding.toolbarAddAccount.setNavigationOnClickListener(v -> finish());
    }

    /** 저장·취소 버튼 동작 연결 */
    private void setUpActions() {
        // 저장 → 준비 중 안내 후 목록 화면으로 복귀
        binding.btnSaveAccount.setOnClickListener(v -> {
            showNotImplemented();
            finish();
        });

        // 취소 → 입력을 버리고 그대로 닫는다.
        binding.btnCancelAdd.setOnClickListener(v -> finish());
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
