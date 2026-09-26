package com.sihoo.aemx.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/** Require a unique password field and a uniquely labelled ID field on the same app screen. */
internal object CredentialNodeFinder {
    fun find(root: AccessibilityNodeInfo, app: String, field: CredentialField): AccessibilityNodeInfo? {
        val pkg = AppLauncher.packageFor(app) ?: return null
        if (root.packageName?.toString() != pkg) return null
        val passwords = mutableListOf<AccessibilityNodeInfo>()
        val ids = mutableListOf<AccessibilityNodeInfo>()
        val otherInputs = mutableListOf<AccessibilityNodeInfo>()
        var kyoboLoginButtons = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 2000) return null
            val node = queue.removeFirst()
            if (pkg == "mok.android" && node.packageName?.toString() == pkg &&
                node.isVisibleToUser && node.isEnabled &&
                node.viewIdResourceName?.substringAfterLast('/') == "loginBtn" && node.isClickable) {
                kyoboLoginButtons++
            }
            if (node.packageName?.toString() == pkg && node.isVisibleToUser && node.isEnabled &&
                (node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })) {
                if (node.isPassword) passwords += node
                else {
                    otherInputs += node
                    val labels = listOfNotNull(node.viewIdResourceName?.substringAfterLast('/'),
                        node.contentDescription?.toString(),
                        if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null,
                        node.text?.toString()).map { it.lowercase(Locale.ROOT).replace(Regex("\\s+"), "") }
                    if (labels.any { it in idLabels }) ids += node
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        // Registration/change-password screens (multiple password fields) are not login forms.
        if (passwords.size != 1) return null
        val id = if (ids.size == 1) ids.single()
            else if (ids.isEmpty() && pkg == "mok.android" && otherInputs.size == 1 && kyoboLoginButtons == 1)
                otherInputs.single()
            else return null
        return if (field == CredentialField.PASSWORD) passwords.single() else id
    }

    private val idLabels = setOf("id", "userid", "user_id", "loginid", "login_id", "memid", "memberid",
        "아이디", "아이디입력", "아이디를입력해주세요", "아이디를입력해주세요.", "이메일", "email", "username")
}
