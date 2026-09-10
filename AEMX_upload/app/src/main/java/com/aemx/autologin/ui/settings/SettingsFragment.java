package com.aemx.autologin.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.aemx.autologin.R;
import com.aemx.autologin.databinding.FragmentSettingsBinding;
import com.aemx.autologin.ui.auth.LoginActivity;

/**
 * 설정 탭 화면.
 * 프로필·보안·일반·정보 섹션을 보여주는 껍데기이며, 실제 설정 저장은 하지 않는다.
 */
public class SettingsFragment extends Fragment {

    /** fragment_settings.xml 의 ViewBinding (뷰 생명주기 동안만 유효) */
    private FragmentSettingsBinding binding;

    /** Fragment 재생성을 위해 반드시 필요한 public 무인자 생성자 */
    public SettingsFragment() {
        super();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 프래그먼트는 inflate 결과의 루트 뷰를 돌려준다.
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setUpProfile();
        setUpSecuritySection();
        setUpGeneralSection();
        setUpInfoSection();
        setUpBottomActions();
    }

    /** 프로필 카드 영역 */
    private void setUpProfile() {
        // 프로필 수정 버튼과 카드 전체 모두 같은 안내를 띄운다.
        binding.btnEditProfile.setOnClickListener(v -> showNotImplemented());
        binding.cardProfile.setOnClickListener(v -> showNotImplemented());
    }

    /** 보안 섹션: 자동 로그인 / 생체인증 / 자동 잠금 / 비밀번호 변경 */
    private void setUpSecuritySection() {
        binding.switchSettingAutoLogin.setOnCheckedChangeListener(
                (buttonView, isChecked) -> showNotImplemented());
        binding.switchSettingBiometric.setOnCheckedChangeListener(
                (buttonView, isChecked) -> showNotImplemented());

        binding.rowLockTimeout.setOnClickListener(v -> showNotImplemented());
        binding.rowChangePassword.setOnClickListener(v -> showNotImplemented());
    }

    /** 일반 섹션: 다크 모드 / 알림 / 언어 */
    private void setUpGeneralSection() {
        binding.switchSettingDarkMode.setOnCheckedChangeListener(
                (buttonView, isChecked) -> showNotImplemented());
        binding.switchSettingNotification.setOnCheckedChangeListener(
                (buttonView, isChecked) -> showNotImplemented());

        binding.rowLanguage.setOnClickListener(v -> showNotImplemented());
    }

    /** 정보 섹션: 앱 버전 / 오픈소스 / 개인정보 처리방침 */
    private void setUpInfoSection() {
        binding.rowVersion.setOnClickListener(v -> showNotImplemented());
        binding.rowOpenSource.setOnClickListener(v -> showNotImplemented());
        binding.rowPrivacy.setOnClickListener(v -> showNotImplemented());
    }

    /** 하단 로그아웃 / 회원 탈퇴 */
    private void setUpBottomActions() {
        binding.btnLogout.setOnClickListener(v -> logout());
        binding.tvDeleteAccount.setOnClickListener(v -> showNotImplemented());
    }

    /** 로그아웃: 이전 화면 스택을 모두 지우고 로그인 화면으로 되돌아간다. */
    private void logout() {
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    /** 미구현 기능 공통 안내 */
    private void showNotImplemented() {
        Toast.makeText(requireContext(), R.string.toast_not_implemented, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 뷰가 사라지면 바인딩 참조를 반드시 해제한다(메모리 누수 방지).
        binding = null;
    }
}
