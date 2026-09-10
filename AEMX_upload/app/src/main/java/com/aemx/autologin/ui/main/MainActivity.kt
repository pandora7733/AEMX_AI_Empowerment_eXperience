package com.aemx.autologin.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.aemx.autologin.R
import com.aemx.autologin.databinding.ActivityMainBinding
import com.aemx.autologin.ui.accounts.AccountsFragment
import com.aemx.autologin.ui.accounts.AddAccountActivity
import com.aemx.autologin.ui.home.HomeFragment
import com.aemx.autologin.ui.settings.SettingsFragment

/**
 * 앱의 메인 화면. 하단 네비게이션으로 홈/계정/설정 세 프래그먼트를 show-hide 방식으로 전환한다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 세 화면을 매번 새로 만들지 않고 필드로 들고 있으면서 show/hide 로만 교체한다.
    private lateinit var homeFragment: HomeFragment
    private lateinit var accountsFragment: AccountsFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var currentFragment: Fragment

    // 현재 선택된 탭 메뉴 id. 화면 회전 시에도 그대로 복원한다.
    @IdRes
    private var currentTabId: Int = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 레이아웃의 MaterialToolbar 를 액션바로 등록한다(테마가 NoActionBar 이므로 충돌 없음).
        setSupportActionBar(binding.toolbarMain)

        currentTabId = savedInstanceState?.getInt(STATE_CURRENT_TAB, R.id.nav_home) ?: R.id.nav_home

        setUpFragments(savedInstanceState)
        setUpBottomNav()
        setUpFab()
        setUpBackBehavior()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_TAB, currentTabId)
    }

    /** 최초 진입에서만 프래그먼트를 add 하고, 재생성 시에는 태그로 기존 인스턴스를 되찾아 중복 add 를 막는다. */
    private fun setUpFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager

        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            accountsFragment = AccountsFragment()
            settingsFragment = SettingsFragment()

            // 세 화면을 한 번에 붙이고 홈을 제외한 나머지는 숨겨 둔다.
            fm.beginTransaction()
                .add(R.id.fragmentContainer, homeFragment, TAG_HOME)
                .add(R.id.fragmentContainer, accountsFragment, TAG_ACCOUNTS)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
                .hide(accountsFragment)
                .hide(settingsFragment)
                .commitNow()

            currentFragment = homeFragment
        } else {
            homeFragment = fm.findFragmentByTag(TAG_HOME) as? HomeFragment ?: HomeFragment()
            accountsFragment = fm.findFragmentByTag(TAG_ACCOUNTS) as? AccountsFragment ?: AccountsFragment()
            settingsFragment = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: SettingsFragment()

            // 혹시 복원되지 않은 화면이 있으면 그때만 add 한다.
            val restoreTx = fm.beginTransaction()
            if (!homeFragment.isAdded) restoreTx.add(R.id.fragmentContainer, homeFragment, TAG_HOME)
            if (!accountsFragment.isAdded) restoreTx.add(R.id.fragmentContainer, accountsFragment, TAG_ACCOUNTS)
            if (!settingsFragment.isAdded) restoreTx.add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS)
            restoreTx.commitNow()

            // 복원된 탭 하나만 보이도록 표시 상태를 다시 맞춘다.
            currentFragment = fragmentOf(currentTabId)
            val visibilityTx = fm.beginTransaction()
            listOf(homeFragment, accountsFragment, settingsFragment).forEach { fragment ->
                if (fragment === currentFragment) visibilityTx.show(fragment) else visibilityTx.hide(fragment)
            }
            visibilityTx.commitNow()
        }
    }

    /** 하단 네비게이션 탭 전환을 연결한다. */
    private fun setUpBottomNav() {
        // 리스너 등록 전에 선택 상태를 맞춰 두면 불필요한 콜백이 발생하지 않는다.
        binding.bottomNav.selectedItemId = currentTabId
        binding.bottomNav.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        selectTab(currentTabId)
    }

    /** 계정 추가 버튼(FAB) — 계정 탭에서만 보이며 누르면 계정 추가 화면으로 간다. */
    private fun setUpFab() {
        binding.fabAddAccount.setOnClickListener {
            startActivity(Intent(this, AddAccountActivity::class.java))
        }
    }

    /** 홈이 아닌 탭에서 뒤로가기를 누르면 앱을 닫지 않고 홈 탭으로 돌아온다. */
    private fun setUpBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            if (currentTabId != R.id.nav_home) {
                binding.bottomNav.selectedItemId = R.id.nav_home
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /** 탭 하나를 선택했을 때의 화면 교체·타이틀·FAB 처리를 한곳에서 담당한다. */
    private fun selectTab(@IdRes itemId: Int) {
        currentTabId = itemId

        val target = fragmentOf(itemId)
        if (target !== currentFragment) {
            supportFragmentManager.beginTransaction()
                .hide(currentFragment)
                .show(target)
                .commit()
            currentFragment = target
        }

        supportActionBar?.title = getString(titleOf(itemId))

        // FAB 은 계정 목록에서만 의미가 있으므로 그 외 탭에서는 감춘다.
        if (itemId == R.id.nav_accounts) binding.fabAddAccount.show() else binding.fabAddAccount.hide()
    }

    /** 탭 메뉴 id 를 실제 프래그먼트로 변환한다. */
    private fun fragmentOf(@IdRes itemId: Int): Fragment = when (itemId) {
        R.id.nav_accounts -> accountsFragment
        R.id.nav_settings -> settingsFragment
        else -> homeFragment
    }

    /** 탭 메뉴 id 에 대응하는 툴바 타이틀 문자열 리소스를 돌려준다. */
    @StringRes
    private fun titleOf(@IdRes itemId: Int): Int = when (itemId) {
        R.id.nav_accounts -> R.string.title_accounts
        R.id.nav_settings -> R.string.title_settings
        else -> R.string.title_home
    }

    private companion object {
        const val TAG_HOME = "tag_home"
        const val TAG_ACCOUNTS = "tag_accounts"
        const val TAG_SETTINGS = "tag_settings"
        const val STATE_CURRENT_TAB = "state_current_tab"
    }
}
