package com.sihoo.aemx

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.pipeline.app.PipelineCore
import com.sihoo.aemx.pipeline.app.VoiceService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 앱의 첫 화면. 처음 켜면 필요한 권한과 설정을 차례로 안내하고, 다 되면 호출어 듣기를 시작한다.
 *
 * 듣기와 실행은 [PipelineCore] 와 [VoiceService] 가 맡는다. 이 화면은 항목이 끝났는지 보여 주고 버튼으로
 * 해당 설정을 연다. 준비가 다 끝나면 듣기를 자동으로 한 번 켜므로, 사람이 누를 일은 권한 허용뿐이다.
 */
class MainActivity : AppCompatActivity() {

    private enum class Item { MIC, NOTIFY, ACCESS, OVERLAY, CONTACTS, MODEL }

    private val rows = LinkedHashMap<Item, Row>()
    private lateinit var listenButton: Button
    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView

    /** 한 항목의 화면 요소. 상태 글자와 버튼을 묶는다. */
    private class Row(val state: TextView, val action: Button)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 시작 화면은 준비 화면 위에서만 짧게 보인다. 모델이 먼저 준비되면 바로 내린다.
        // 첫 실행의 1.2GB 다운로드 동안 붙잡아 두면 체크리스트가 가려지므로 최대 시간을 둔다.
        val splash = installSplashScreen()
        val startedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - startedAt < SPLASH_MAX_MS && !PipelineCore.modelReady.value
        }
        PipelineCore.init(applicationContext)
        setContentView(ScrollView(this).apply { addView(createContentView()) })
        observeCore()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun observeCore() {
        PipelineCore.modelReady.onEach { refresh() }.launchIn(lifecycleScope)
        PipelineCore.modelMessage.onEach { refresh() }.launchIn(lifecycleScope)
        PipelineCore.status.onEach { statusView.text = it }.launchIn(lifecycleScope)
        PipelineCore.listening.onEach { on ->
            listenButton.setText(if (on) R.string.main_listen_stop else R.string.main_listen_start)
        }.launchIn(lifecycleScope)
    }

    private fun createContentView(): LinearLayout {
        val padding = dpToPx(24)
        val spacing = dpToPx(12)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@MainActivity).apply {
                setText(R.string.main_title)
                setTextAppearance(MaterialR.style.TextAppearance_MaterialComponents_Headline5)
            }, matchWidthWrapHeight())
            addView(TextView(this@MainActivity).apply {
                setText(R.string.main_intro)
            }, matchWidthWrapHeight().apply { topMargin = spacing })

            Item.entries.forEach { item ->
                val box = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val state = TextView(this@MainActivity)
                val title = TextView(this@MainActivity).apply {
                    setTextAppearance(MaterialR.style.TextAppearance_MaterialComponents_Subtitle1)
                    text = getString(titleFor(item))
                }
                val desc = TextView(this@MainActivity).apply { text = getString(descFor(item)) }
                val action = Button(this@MainActivity).apply {
                    setOnClickListener { onAction(item) }
                }
                box.addView(state, matchWidthWrapHeight())
                box.addView(title, matchWidthWrapHeight())
                box.addView(desc, matchWidthWrapHeight())
                box.addView(action, matchWidthWrapHeight().apply { topMargin = dpToPx(4) })
                addView(box, matchWidthWrapHeight().apply { topMargin = spacing * 2 })
                rows[item] = Row(state, action)
            }

            summaryView = TextView(this@MainActivity).apply {
                accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(summaryView, matchWidthWrapHeight().apply { topMargin = spacing * 2 })

            listenButton = Button(this@MainActivity).apply {
                setText(R.string.main_listen_start)
                setOnClickListener { onListenClicked() }
            }
            addView(listenButton, matchWidthWrapHeight().apply { topMargin = spacing })

            statusView = TextView(this@MainActivity).apply {
                gravity = Gravity.START
                accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(statusView, matchWidthWrapHeight().apply { topMargin = spacing })
        }
    }

    /** 항목마다 끝났는지 보고 상태 글자와 버튼을 다시 맞춘다. 준비가 다 끝나면 듣기를 한 번 자동으로 켠다. */
    private fun refresh() {
        if (!::listenButton.isInitialized) return
        val done = doneItems()
        var nextMarked = false
        Item.entries.forEach { item ->
            val row = rows.getValue(item)
            val isDone = item in done
            row.state.setText(
                when {
                    isDone -> R.string.guide_done
                    !nextMarked -> R.string.guide_next
                    else -> R.string.guide_todo
                },
            )
            if (!isDone && !nextMarked) nextMarked = true
            row.action.isEnabled = !isDone && item != Item.MODEL
            row.action.setText(if (isDone) R.string.guide_done else actionFor(item))
        }
        val ready = done.size == Item.entries.size
        summaryView.setText(if (ready) R.string.main_ready else R.string.main_not_ready)
        if (ready && !autoListenStarted && !PipelineCore.listening.value) {
            // 준비가 끝난 뒤 처음 한 번만 듣기를 켠다. 사용자가 끄면 프로세스가 살아 있는 동안 다시 켜지 않는다.
            autoListenStarted = true
            VoiceService.start(this)
        }
    }

    private fun doneItems(): Set<Item> {
        val done = mutableSetOf<Item>()
        if (granted(Manifest.permission.RECORD_AUDIO)) done += Item.MIC
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)) {
            done += Item.NOTIFY
        }
        val accessibility = isAccessibilityServiceEnabled()
        if (accessibility) done += Item.ACCESS
        if (accessibility && AemxAccessibilityService.isOverlayEnabled()) done += Item.OVERLAY
        if (granted(Manifest.permission.READ_CONTACTS)) done += Item.CONTACTS
        if (PipelineCore.modelReady.value) done += Item.MODEL
        return done
    }

    private fun onAction(item: Item) {
        when (item) {
            Item.MIC -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            Item.NOTIFY -> notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            Item.ACCESS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Item.OVERLAY -> {
                AemxAccessibilityService.setOverlayEnabled(true)
                refresh()
            }
            Item.CONTACTS -> contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            Item.MODEL -> Unit
        }
    }

    private fun onListenClicked() {
        if (PipelineCore.listening.value) {
            VoiceService.stop(this)
        } else if (granted(Manifest.permission.RECORD_AUDIO)) {
            VoiceService.start(this)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (AemxAccessibilityService.isConnected()) return true
        val expected = ComponentName(this, AemxAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun titleFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_title
        Item.NOTIFY -> R.string.guide_notify_title
        Item.ACCESS -> R.string.guide_access_title
        Item.OVERLAY -> R.string.guide_overlay_title
        Item.CONTACTS -> R.string.guide_contacts_title
        Item.MODEL -> R.string.guide_model_title
    }

    private fun descFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_desc
        Item.NOTIFY -> R.string.guide_notify_desc
        Item.ACCESS -> R.string.guide_access_desc
        Item.OVERLAY -> R.string.guide_overlay_desc
        Item.CONTACTS -> R.string.guide_contacts_desc
        Item.MODEL -> R.string.guide_model_desc
    }

    private fun actionFor(item: Item) = when (item) {
        Item.MIC -> R.string.guide_mic_action
        Item.NOTIFY -> R.string.guide_notify_action
        Item.ACCESS -> R.string.guide_access_action
        Item.OVERLAY -> R.string.guide_overlay_action
        Item.CONTACTS -> R.string.guide_contacts_action
        Item.MODEL -> R.string.guide_model_action
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        /** 프로세스 안에서 자동 듣기를 한 번만 켜기 위한 표시. */
        var autoListenStarted = false

        /** 시작 화면의 최대 시간(ms). 모델이 이보다 빨리 준비되면 그때 내린다. */
        const val SPLASH_MAX_MS = 2_000L
    }
}
