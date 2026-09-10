package com.aemx.autologin.ui.accounts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aemx.autologin.R
import com.aemx.autologin.data.DummyData
import com.aemx.autologin.data.model.Account
import com.aemx.autologin.databinding.FragmentAccountsBinding

/**
 * 계정 탭. 저장된 계정 목록을 보여 주고 행을 누르면 상세 화면으로 이동한다.
 * 검색창과 필터 칩은 껍데기 단계라 화면 구성만 하고 실제 필터링은 하지 않는다.
 */
class AccountsFragment : Fragment() {

    private var _binding: FragmentAccountsBinding? = null
    private val binding get() = _binding!!

    // 껍데기 단계이므로 목록은 더미 데이터를 그대로 쓴다.
    private val accounts: List<Account> = DummyData.accounts

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRecyclerView()
        setUpEmptyState()
    }

    override fun onDestroyView() {
        // 어댑터를 먼저 떼어 내 뷰 참조가 남지 않게 한다.
        _binding?.rvAccounts?.adapter = null
        super.onDestroyView()
        _binding = null
    }

    /** 계정 목록 리사이클러뷰를 구성한다. */
    private fun setUpRecyclerView() {
        val adapter = AccountAdapter(
            items = accounts,
            onItemClick = { account -> openDetail(account) },
            onAutoLoginToggle = { _, _ -> showNotImplemented() }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAccounts.setHasFixedSize(true)
        binding.rvAccounts.adapter = adapter
    }

    /** 목록이 비었을 때만 빈 상태 안내를 띄운다(더미 데이터가 있어 실제로는 숨겨진다). */
    private fun setUpEmptyState() {
        val isEmpty = accounts.isEmpty()
        binding.rvAccounts.isVisible = !isEmpty
        binding.layoutEmpty.root.isVisible = isEmpty

        binding.layoutEmpty.btnEmptyAction.setOnClickListener {
            startActivity(Intent(requireContext(), AddAccountActivity::class.java))
        }
    }

    /** 선택한 계정 정보를 인텐트 엑스트라로 넘기며 상세 화면을 연다. */
    private fun openDetail(account: Account) {
        val intent = Intent(requireContext(), AccountDetailActivity::class.java).apply {
            putExtra(AccountDetailActivity.EXTRA_SITE_NAME, account.siteName)
            putExtra(AccountDetailActivity.EXTRA_SITE_URL, account.siteUrl)
            putExtra(AccountDetailActivity.EXTRA_LOGIN_ID, account.loginId)
            putExtra(AccountDetailActivity.EXTRA_CATEGORY, account.category)
            putExtra(AccountDetailActivity.EXTRA_AUTO_LOGIN, account.autoLoginEnabled)
            putExtra(AccountDetailActivity.EXTRA_LAST_USED, account.lastUsed)
        }
        startActivity(intent)
    }

    private fun showNotImplemented() {
        Toast.makeText(requireContext(), R.string.toast_not_implemented, Toast.LENGTH_SHORT).show()
    }
}
