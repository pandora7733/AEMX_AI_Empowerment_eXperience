package com.sihoo.aemx.devicechecks;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;

/** External UI checks against the real AEMX service. Never enables accessibility in code. */
public final class DeviceChecks extends Instrumentation {
    private UiAutomation automation;
    private int passed;
    private String scenario;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        scenario = args == null ? "regression" : args.getString("scenario", "regression");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            android.util.Log.i("AemxDeviceChecks", "Starting UiAutomation connection");
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.util.Log.i("AemxDeviceChecks", "UiAutomation connected");
            Intent launch = new Intent().setClassName("com.sihoo.aemx", "com.sihoo.aemx.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            getTargetContext().startActivity(launch);
            click("Action Plan 실행 테스트");
            click("실행 중단");
            awaitText("실행 중단됨");
            pass("service_connected");

            if ("aladin_open".equals(scenario)) {
                // No account entry: the installed bookstore closes during startup on this emulator.
                submit("{\"app\":\"알라딘\",\"steps\":[{\"action\":\"open_app\",\"target\":\"알라딘\"}]}");
                awaitNode("1단계 실패: 앱 화면이 열리지 않았습니다. 앱이 종료되거나 접근이 차단되었는지 확인해 주세요.", 22_000);
                pass("aladin_startup_failure_reported");
                result.putString("stream", "PASS: Aladin startup failure reported\n");
                finish(Activity.RESULT_OK, result);
                return;
            }

            if ("kyobo_search".equals(scenario)) {
                // Selectors observed on Kyobo 3.2.23. No account, purchase or payment operations.
                submit("{\"app\":\"교보문고\",\"steps\":[{\"action\":\"open_app\",\"target\":\"교보문고\"},"
                        + "{\"action\":\"input_text\",\"target\":\"searchMainKeyword\",\"value\":\"데미안\"},"
                        + "{\"action\":\"tap\",\"target\":\"search button\"}]}");
                SystemClock.sleep(15_000);
                // This is a manual inspection helper, not an assertion of search success.
                result.putString("stream", "DISPATCHED kyobo_search; inspect the bookstore result and AEMX status\n");
                finish(Activity.RESULT_OK, result);
                return;
            }

            if ("naver_weather".equals(scenario)) {
                dispatchManual(result, "{\"app\":\"네이버\",\"steps\":[{\"action\":\"open_app\",\"target\":\"네이버\"},"
                        + "{\"action\":\"search\",\"target\":\"서울 날씨\"}]}", "naver_weather");
                return;
            }

            if ("google_news".equals(scenario)) {
                dispatchManual(result, "{\"app\":\"구글\",\"steps\":[{\"action\":\"open_app\",\"target\":\"구글\"},"
                        + "{\"action\":\"search\",\"target\":\"오늘 주요 뉴스\"}]}", "google_news");
                return;
            }

            if ("youtube_search".equals(scenario)) {
                dispatchManual(result, "{\"app\":\"유튜브\",\"steps\":[{\"action\":\"open_app\",\"target\":\"유튜브\"},"
                        + "{\"action\":\"search\",\"target\":\"오늘 날씨\"},"
                        + "{\"action\":\"play_first\",\"target\":\"첫 번째 동영상\"}]}", "youtube_search");
                return;
            }

            if ("youtube_next".equals(scenario)) {
                dispatchManual(result, "{\"app\":\"유튜브\",\"steps\":[{\"action\":\"open_app\",\"target\":\"유튜브\"},"
                        + "{\"action\":\"tap\",\"target\":\"Shorts\"},"
                        + "{\"action\":\"next_video\",\"target\":\"다음 동영상\",\"value\":\"10s\"}]}", "youtube_next");
                return;
            }

            if ("instagram_next".equals(scenario)) {
                dispatchManual(result, "{\"app\":\"인스타그램\",\"steps\":[{\"action\":\"open_app\",\"target\":\"인스타그램\"},"
                        + "{\"action\":\"tap\",\"target\":\"릴스\"},"
                        + "{\"action\":\"next_video\",\"target\":\"다음 릴스\",\"value\":\"10s\"}]}", "instagram_next");
                return;
            }

            submit("not-json");
            awaitText("Action Plan JSON 형식이 올바르지 않습니다.");
            pass("malformed_json_visible_error");

            submit("{\"app\":\"교보문고\",\"steps\":[{\"action\":\"wait\",\"value\":\"1s\"},{\"action\":\"wait\",\"value\":\"1s\"}]}");
            awaitText("1단계: wait");
            awaitText("2단계: wait");
            awaitText("모든 UI 명령 전달 완료 (실제 결과 확인 필요)");
            pass("service_wait_order_and_completion");

            submit("{\"app\":\"교보문고\",\"steps\":[{\"action\":\"wait\",\"value\":\"30s\"}]}");
            awaitText("1단계: wait");
            click("JSON 실행");
            awaitText("이미 실행 중입니다. 먼저 중단해 주세요.");
            click("실행 중단");
            awaitText("실행 중단됨");
            SystemClock.sleep(1_200);
            awaitText("실행 중단됨");
            pass("duplicate_rejected_and_cancelled");

            // The current window is AEMX, not Kyobo: the service must never click this button.
            submit("{\"app\":\"교보문고\",\"steps\":[{\"action\":\"tap\",\"target\":\"실행 중단\"}]}");
            awaitText("1단계 실패: 대상 화면 또는 조작 가능한 요소를 8초 안에 찾지 못했습니다.");
            pass("wrong_app_window_times_out_without_clicking");

            result.putString("stream", "PASS: " + passed + " external device checks; no skips\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL after " + passed + " checks: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage() + "\n");
            android.util.Log.e("AemxDeviceChecks", result.getString("stream"));
            sendStatus(-2, result);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void submit(String json) {
        AccessibilityNodeInfo field = awaitNode("실행할 JSON");
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, json);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw new AssertionError("ACTION_SET_TEXT was rejected");
        }
        click("JSON 실행");
    }

    private void dispatchManual(Bundle result, String json, String name) {
        submit(json);
        SystemClock.sleep(20_000);
        result.putString("stream", "DISPATCHED " + name + "; verify the target app UI and AEMX status manually\n");
        finish(Activity.RESULT_OK, result);
    }

    private void click(String text) {
        AccessibilityNodeInfo node = awaitNode(text);
        while (node != null && !node.isClickable()) node = node.getParent();
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            throw new AssertionError("Click failed: " + text);
        }
    }

    private void awaitText(String text) { awaitNode(text); }

    private AccessibilityNodeInfo awaitNode(String expected) { return awaitNode(expected, 15_000); }

    private AccessibilityNodeInfo awaitNode(String expected, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null && "com.sihoo.aemx".contentEquals(root.getPackageName())) {
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                while (!queue.isEmpty()) {
                    AccessibilityNodeInfo node = queue.removeFirst();
                    CharSequence hint = android.os.Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
                    if (node.isVisibleToUser() && (same(expected, node.getText())
                            || same(expected, node.getContentDescription()) || same(expected, hint))) return node;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child);
                    }
                }
            }
            SystemClock.sleep(100); // Instrumentation worker, not the app main thread.
        }
        throw new AssertionError("Visible UI not found: " + expected);
    }

    private boolean same(String expected, CharSequence actual) {
        return actual != null && expected.equalsIgnoreCase(actual.toString());
    }

    private void pass(String name) {
        passed++;
        Bundle progress = new Bundle();
        progress.putString("stream", "PASS " + name + "\n");
        sendStatus(0, progress);
    }
}
