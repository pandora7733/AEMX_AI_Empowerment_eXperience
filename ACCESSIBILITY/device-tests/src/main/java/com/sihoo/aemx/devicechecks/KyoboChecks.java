package com.sihoo.aemx.devicechecks;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Real-app inspection helper. Never reads the credential store or prints field values. */
public final class KyoboChecks extends Instrumentation {
    private Bundle arguments;
    private UiAutomation automation;
    private static final String[] WORDS = {"로그인", "로그아웃", "마이룸", "마이페이지", "마이", "닫기",
            "아이디", "비밀번호", "회원정보", "회원가입", "인증", "일치하지", "확인해", "실패", "완료", "차단",
            "close", "Close", "login", "Login", "MY", "my", "오늘", "취소", "확인",
            "보안", "비정상", "에뮬레이터", "루팅", "서비스", "접근성", "해제", "네트워크",
            "인터넷", "오류", "탐지", "기기", "종료", "업데이트", "재시도", "앱을"};

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            String operation = arguments.getString("operation", "inspect");
            if ("launch".equals(operation)) {
                Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage("mok.android");
                if (intent == null) throw new IllegalStateException("Bookstore not installed");
                getTargetContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                SystemClock.sleep(3000);
            } else if ("tap_id".equals(operation) || "tap_label".equals(operation)) {
                String target = arguments.getString("target", "");
                List<AccessibilityNodeInfo> found = new ArrayList<>();
                for (AccessibilityNodeInfo node : nodes()) {
                    if (!node.isVisibleToUser() || !node.isEnabled()) continue;
                    boolean match = "tap_id".equals(operation) ? target.equals(node.getViewIdResourceName())
                            : target.contentEquals(value(node.getText())) || target.contentEquals(value(node.getContentDescription()));
                    if (match) found.add(node);
                }
                if (found.size() != 1) throw new IllegalStateException("Target is missing or ambiguous");
                AccessibilityNodeInfo node = found.get(0);
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException("Click rejected");
                SystemClock.sleep(3000);
            }
            StringBuilder report = new StringBuilder("SANITIZED UI (input values omitted)\n");
            int editable = 0, passwords = 0;
            for (AccessibilityNodeInfo node : nodes()) {
                if (!node.isVisibleToUser()) continue;
                boolean input = node.isEditable() || node.getActionList().stream().anyMatch(a -> a.getId() == AccessibilityNodeInfo.ACTION_SET_TEXT);
                if (input) editable++;
                if (node.isPassword()) passwords++;
                String terms = knownTerms(input ? "" : value(node.getText()) + " " + value(node.getContentDescription()));
                String exact = "";
                if (!input) for (String word : WORDS) if (word.contentEquals(value(node.getText())) || word.contentEquals(value(node.getContentDescription()))) exact += word + ",";
                String id = node.getViewIdResourceName();
                if (input || node.isClickable() || !terms.isEmpty()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    report.append("id=").append(id == null ? "-" : id.replaceAll("[^A-Za-z0-9_./:-]", "?")).append(" class=")
                        .append(node.getClassName()).append(" editable=").append(input).append(" password=").append(node.isPassword())
                        .append(" clickable=").append(node.isClickable()).append(" bounds=").append(bounds.toShortString())
                        .append(" knownTerms=").append(terms).append(" exact=").append(exact).append('\n');
                }
            }
            report.append("editableCount=").append(editable).append(" passwordCount=").append(passwords).append('\n');
            result.putString("stream", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("stream", "CHECK FAILED: " + error.getClass().getSimpleName() + " (details omitted)\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private List<AccessibilityNodeInfo> nodes() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null || !"mok.android".contentEquals(root.getPackageName())) throw new IllegalStateException("Wrong foreground app");
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            if (result.size() >= 2000) throw new IllegalStateException("Tree too large");
            AccessibilityNodeInfo node = queue.removeFirst(); result.add(node);
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child); }
        }
        return result;
    }
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String knownTerms(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : WORDS) if (value.contains(word)) result.append(word).append(',');
        return result.toString();
    }
}
