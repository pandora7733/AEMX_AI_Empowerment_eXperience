package com.aemx.autologin.ui.accounts

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aemx.autologin.R
import com.aemx.autologin.databinding.ActivityAccountDetailBinding

/**
 * 계정 상세 화면. 목록에서 넘어온 인텐트 엑스트라를 표시하고 비밀번호 마스킹만 실제로 토글한다.
 */
class AccountDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountDetailBinding

    // 비밀번호 평문 표시 여부. 화면 회전 시에도 유지한다.
    private var isPasswordVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 툴바를 액션바로 등록하고 뒤로가기(업) 버튼을 켠다.
        setSupportActionBar(binding.toolbarDetail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isPasswordVisible = savedInstanceState?.getBoolean(STATE_PASSWORD_VISIBLE, false) ?: false

        bindAccountInfo()
        bindPasswordSection()
        bindActions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PASSWORD_VISIBLE, isPasswordVisible)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_account_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        R.id.action_edit, R.id.action_delete -> {
            showNotImplemented()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 목록에서 전달받은 계정 정보를 화면에 채운다. 값이 없으면 레이아웃 기본 문구를 그대로 둔다. */
    private fun bindAccountInfo() {
        binding.tvDetailSiteName.setTextIfNotBlank(intent.getStringExtra(EXTRA_SITE_NAME))
        binding.tvDetailSiteUrl.setTextIfNotBlank(intent.getStringExtra(EXTRA_SITE_URL))
        binding.tvDetailId.setTextIfNotBlank(intent.getStringExtra(EXTRA_LOGIN_ID))
        binding.tvDetailCategoryValue.setTextIfNotBlank(intent.getStringExtra(EXTRA_CATEGORY))
        binding.tvDetailLastUsedValue.setTextIfNotBlank(intent.getStringExtra(EXTRA_LAST_USED))
        binding.tvDetailCreatedValue.text = SAMPLE_CREATED_DATE

        // 자동 로그인 여부에 따라 상단 배지와 스위치 상태를 맞춘다.
        val autoLoginEnabled = intent.getBooleanExtra(EXTRA_AUTO_LOGIN, true)
        bindBadge(autoLoginEnabled)
        binding.switchDetailAutoLogin.setOnCheckedChangeListener(null)
        binding.switchDetailAutoLogin.isChecked = autoLoginEnabled
        binding.switchDetailAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            bindBadge(isChecked)
            showNotImplemented()
        }
    }

    /** 비밀번호는 기본적으로 가려 두고 눈 아이콘으로만 평문 샘플과 번갈아 보여 준다. */
    private fun bindPasswordSection() {
        updatePasswordText()
        binding.btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            updatePasswordText()
        }
    }

    private fun updatePasswordText() {
        binding.tvDetailPassword.text =
            if (isPasswordVisible) SAMPLE_PASSWORD else getString(R.string.detail_password_masked)
    }

    /** 복사·사이트 열기·삭제는 껍데기 단계라 안내 토스트만 띄운다. */
    private fun bindActions() {
        binding.btnCopyId.setOnClickListener { showNotImplemented() }
        binding.btnCopyPassword.setOnClickListener { showNotImplemented() }
        binding.btnOpenSite.setOnClickListener { showNotImplemented() }
        binding.btnDetailDelete.setOnClickListener { showNotImplemented() }
    }

    /** 자동/수동 배지의 문구·배경·글자색을 함께 바꾼다. */
    private fun bindBadge(autoLoginEnabled: Boolean) {
        val badge = binding.tvDetailBadge

        // 배경 리소스 교체로 패딩이 초기화되는 것을 막기 위해 저장 후 복원한다.
        val left = badge.paddingLeft
        val top = badge.paddingTop
        val right = badge.paddingRight
        val bottom = badge.paddingBottom

        if (autoLoginEnabled) {
            badge.setText(R.string.accounts_badge_auto)
            badge.setBackgroundResource(R.drawable.bg_pill_success)
            badge.setTextColor(ContextCompat.getColor(this, R.color.color_success))
        } else {
            badge.setText(R.string.accounts_badge_manual)
            badge.setBackgroundResource(R.drawable.bg_pill_neutral)
            badge.setTextColor(ContextCompat.getColor(this, R.color.color_text_secondary))
        }

        badge.setPadding(left, top, right, bottom)
    }

    /** 전달값이 비어 있으면 레이아웃에 적힌 기본 문구를 유지한다. */
    private fun TextView.setTextIfNotBlank(value: String?) {
        if (!value.isNullOrBlank()) text = value
    }

    private fun showNotImplemented() {
        Toast.makeText(this, R.string.toast_not_implemented, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SITE_NAME = "extra_site_name"
        const val EXTRA_SITE_URL = "extra_site_url"
        const val EXTRA_LOGIN_ID = "extra_login_id"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_AUTO_LOGIN = "extra_auto_login"
        const val EXTRA_LAST_USED = "extra_last_used"

        /** 시연용 샘플 비밀번호. 실제 저장·복호화 로직은 구현하지 않는다. */
        private const val SAMPLE_PASSWORD = "aemx!2026"

        /** 등록일은 전달되는 값이 없어 화면 구성용 샘플 날짜를 사용한다. */
        private const val SAMPLE_CREATED_DATE = "2026.01.12"

        private const val STATE_PASSWORD_VISIBLE = "state_password_visible"
    }
}
