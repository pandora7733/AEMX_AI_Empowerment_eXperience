package com.aemx.autologin.ui.accounts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.aemx.autologin.R
import com.aemx.autologin.data.model.Account
import com.aemx.autologin.databinding.ItemAccountBinding

/**
 * 저장된 계정 목록을 그리는 어댑터. 행 클릭과 자동 로그인 스위치 토글을 바깥으로 전달한다.
 */
class AccountAdapter(
    private val items: List<Account>,
    private val onItemClick: (Account) -> Unit,
    private val onAutoLoginToggle: (Account, Boolean) -> Unit
) : RecyclerView.Adapter<AccountAdapter.AccountViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AccountViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    /** 계정 한 줄을 담당하는 뷰홀더. */
    inner class AccountViewHolder(
        private val binding: ItemAccountBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(account: Account) {
            binding.tvSiteName.text = account.siteName
            binding.tvSiteAccountId.text = account.loginId
            bindBadge(account.autoLoginEnabled)

            // 뷰 재활용 버그 방지: 리스너 해제 -> 상태 세팅 -> 리스너 재등록 순서를 반드시 지킨다.
            binding.switchAccountAutoLogin.setOnCheckedChangeListener(null)
            binding.switchAccountAutoLogin.isChecked = account.autoLoginEnabled
            binding.switchAccountAutoLogin.setOnCheckedChangeListener { _, isChecked ->
                bindBadge(isChecked)
                onAutoLoginToggle(account, isChecked)
            }

            binding.root.setOnClickListener { onItemClick(account) }
        }

        /** 자동/수동 여부에 따라 배지 문구·배경·글자색을 바꾼다. */
        private fun bindBadge(autoLoginEnabled: Boolean) {
            val badge = binding.tvSiteBadge

            // 배경 리소스를 교체하면 패딩이 초기화될 수 있어 미리 저장했다가 되돌려 준다.
            val left = badge.paddingLeft
            val top = badge.paddingTop
            val right = badge.paddingRight
            val bottom = badge.paddingBottom

            if (autoLoginEnabled) {
                badge.setText(R.string.accounts_badge_auto)
                badge.setBackgroundResource(R.drawable.bg_pill_success)
                badge.setTextColor(ContextCompat.getColor(itemView.context, R.color.color_success))
            } else {
                badge.setText(R.string.accounts_badge_manual)
                badge.setBackgroundResource(R.drawable.bg_pill_neutral)
                badge.setTextColor(ContextCompat.getColor(itemView.context, R.color.color_text_secondary))
            }

            badge.setPadding(left, top, right, bottom)
        }
    }
}
