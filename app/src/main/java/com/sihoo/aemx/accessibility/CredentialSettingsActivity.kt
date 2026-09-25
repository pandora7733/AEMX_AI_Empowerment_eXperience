package com.sihoo.aemx.accessibility

import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sihoo.aemx.R

class CredentialSettingsActivity : AppCompatActivity() {
    private lateinit var userId: EditText
    private lateinit var password: EditText
    private lateinit var apps: Spinner
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var delete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        layout.addView(TextView(this).apply {
            setText(R.string.credential_description)
            textSize = 18f
        })
        apps = Spinner(this).apply {
            contentDescription = getString(R.string.credential_app)
            adapter = ArrayAdapter(this@CredentialSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("교보문고", "알라딘"))
        }
        layout.addView(apps)
        userId = input(getString(R.string.credential_id), false)
        password = input(getString(R.string.credential_password), true)
        layout.addView(userId)
        layout.addView(password)
        apps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { clearInputs() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        save = Button(this).apply {
            setText(R.string.credential_save)
            setOnClickListener {
                val id = userId.text.toString()
                val secret = password.text.toString()
                if (id.isBlank() || secret.isEmpty()) {
                    status.setText(R.string.credential_required)
                } else {
                    val app = apps.selectedItem.toString()
                    clearInputs()
                    operate(getString(R.string.credential_saved, app)) { store -> store.save(app, id, secret) }
                }
            }
        }
        delete = Button(this).apply {
            setText(R.string.credential_delete)
            setOnClickListener {
                val app = apps.selectedItem.toString()
                clearInputs()
                AemxAccessibilityService.cancelActionPlan()
                operate(getString(R.string.credential_deleted, app)) { it.delete(app) }
            }
        }
        layout.addView(save)
        layout.addView(delete)
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        layout.addView(status)
        layout.addView(TextView(this).apply {
            setText(R.string.credential_help)
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun input(label: String, secret: Boolean) = EditText(this).apply {
        hint = label
        isSingleLine = true
        isSaveEnabled = false
        filters = arrayOf(InputFilter.LengthFilter(2000))
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun operate(success: String, action: (KeystoreCredentialProvider) -> Unit) {
        setBusy(true)
        status.setText(R.string.credential_busy)
        val context = applicationContext
        CredentialIo.executor.execute {
            val result = runCatching { action(KeystoreCredentialProvider(context)) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    setBusy(false)
                    status.text = if (result.isSuccess) success else getString(R.string.credential_failed)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        listOf<View>(apps, userId, password, save, delete).forEach { it.isEnabled = !busy }
    }

    private fun clearInputs() { userId.text.clear(); password.text.clear() }

    override fun onStop() {
        clearInputs()
        super.onStop()
    }
}
