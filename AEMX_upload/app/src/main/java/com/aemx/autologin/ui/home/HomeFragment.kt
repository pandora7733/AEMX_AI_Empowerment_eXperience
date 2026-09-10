package com.aemx.autologin.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.aemx.autologin.R
import com.aemx.autologin.databinding.FragmentHomeBinding
import com.aemx.autologin.databinding.ItemRecentActivityBinding

/**
 * 홈 탭. 보호 상태 카드·요약 통계·최근 활동 3건을 보여 주는 대시보드 화면이다.
 */
class HomeFragment : Fragment() {

    // 프래그먼트 뷰 수명에 맞춰 바인딩을 해제하기 위한 표준 패턴.
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindRecentActivities()
        bindActions()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** include 로 붙인 최근 활동 3줄에 각각 다른 샘플 문구와 아이콘을 채운다. */
    private fun bindRecentActivities() {
        bindRecentItem(
            item = binding.recentItem1,
            titleRes = R.string.sample_activity_title_1,
            subRes = R.string.sample_activity_sub_1,
            timeRes = R.string.sample_activity_time_1,
            iconRes = R.drawable.ic_check_circle_24
        )
        bindRecentItem(
            item = binding.recentItem2,
            titleRes = R.string.sample_activity_title_2,
            subRes = R.string.sample_activity_sub_2,
            timeRes = R.string.sample_activity_time_2,
            iconRes = R.drawable.ic_info_24
        )
        bindRecentItem(
            item = binding.recentItem3,
            titleRes = R.string.sample_activity_title_3,
            subRes = R.string.sample_activity_sub_3,
            timeRes = R.string.sample_activity_time_3,
            iconRes = R.drawable.ic_add_24
        )
    }

    /** 최근 활동 한 줄을 채우는 공통 처리. */
    private fun bindRecentItem(
        item: ItemRecentActivityBinding,
        @StringRes titleRes: Int,
        @StringRes subRes: Int,
        @StringRes timeRes: Int,
        @DrawableRes iconRes: Int
    ) {
        item.tvActivityTitle.setText(titleRes)
        item.tvActivitySub.setText(subRes)
        item.tvActivityTime.setText(timeRes)
        item.ivActivityIcon.setImageResource(iconRes)
        item.root.setOnClickListener { showNotImplemented() }
    }

    /** 껍데기 단계라 동기화·전체 보기는 안내 토스트만 띄운다. */
    private fun bindActions() {
        binding.btnHomeSync.setOnClickListener { showNotImplemented() }
        binding.tvHomeSeeAll.setOnClickListener { showNotImplemented() }
    }

    private fun showNotImplemented() {
        Toast.makeText(requireContext(), R.string.toast_not_implemented, Toast.LENGTH_SHORT).show()
    }
}
