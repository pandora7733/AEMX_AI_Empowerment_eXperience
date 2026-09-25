# 에뮬레이터 검증 — 2026-09-25

## 환경과 결과

- AVD: AEMX_TTS_API_36, Android 16 / API 36, x86_64, Pixel 7 설정.
- 기존 AEMX를 삭제하지 않고 이전 개발용 debug 키로 다시 서명해 업데이트했다. 키/비밀번호/로컬 경로는 Git에 추가하지 않았다.
- 사용자가 USB 디버깅 승인과 접근성 서비스 활성화를 직접 수행했다. 코드로 접근성 설정을 변경하지 않았다.
- 실제 OS의 서비스 등록, 설정 화면 진입, 서비스 바인딩 확인.
- 앱 내부 계측 검사 5개 통과: 기존 패키지 검사 1, 서비스 등록/JSON 화면/실제 노드 입력·클릭/오류 표시 4.
- 외부 프로세스 검사 5개 통과, skip 0. 마지막에도 Bound services에 AEMX가 있고 Crashed services는 비어 있었다.
- `:app:assembleDebugAndroidTest`, `:accessibility-device-tests:assembleDebug`, `:accessibility-device-tests:lintDebug` 성공. 외부 테스트 모듈 Lint는 오류 0, 경고 2(개발용 앱 아이콘 생략, 백업 규칙 권고).

## 왜 외부 프로세스 테스트가 필요한가

최초 앱 내부 계측 실행 후 서비스가 Crashed services에 들어갔고, 서비스 실행 테스트 두 개가 skip되었다. 앱 프로세스에서 직접 계측하면 해당 프로세스에 함께 있는 서비스 연결에 영향을 줄 수 있다. 이를 실제 서비스 기능 실패나 테스트 성공으로 계산하지 않았다.

외부 테스트 앱은 자기 자신을 instrumentation target으로 삼는다. AEMX를 force-stop하지 않으며 `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`를 사용한다. 에뮬레이터 재부팅으로 이미 사용자가 켠 서비스의 연결을 복구한 뒤 외부 검사 다섯 개가 모두 통과했다. 기존의 서비스 직접 참조 테스트 두 개는 제거하고, 외부 UI를 통한 검증으로 대체했다.

참고: [Android UiAutomation API](https://developer.android.com/reference/android/app/UiAutomation), [AOSP self-instrumenting tests](https://source.android.com/docs/core/tests/development/instr-self-e2e).

## 외부 검사 5개

1. AEMX 런처 → JSON 화면 → 실제 서비스의 준비 상태 확인.
2. ACTION_SET_TEXT로 잘못된 JSON 입력 → ACTION_CLICK으로 실행 → 안전한 오류 표시 확인.
3. JSON wait 두 단계의 1단계 → 2단계 → 완료 상태를 화면에서 확인.
4. 대기 중 새 실행 거부 → 중단 버튼 → 중단 상태 유지 확인.
5. plan.app은 교보문고인데 실제 화면은 AEMX인 경우 버튼을 누르지 않고 8초 timeout으로 종료하는지 확인.

외부 검사도 입력/클릭에 실제 Android AccessibilityNodeInfo를 사용한다. 타사 앱을 위장한 fixture를 설치하지 않는다. 현재 앱 데이터와 서비스 설정을 삭제하지 않는다.

## 재현 방법

먼저 앱과 앱 내부 테스트를 빌드한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :accessibility-device-tests:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.sihoo.aemx.test/androidx.test.runner.AndroidJUnitRunner
```

설치된 APK와 빌드 APK의 서명이 다르면 기존 개발용 키로 서명을 맞춘다. 오류를 피하려고 기존 앱을 무조건 삭제하지 않는다.

앱 내부 계측 뒤에는 에뮬레이터를 재부팅하고 부팅 완료를 확인한다. AEMX의 접근성 서비스를 사용자가 설정에서 활성화했는지 확인한다. 서비스가 이미 켜져 있다면 다시 권한을 강제 설정하지 않는다.

```powershell
adb reboot
adb wait-for-device
adb shell getprop sys.boot_completed
adb shell dumpsys accessibility
adb install -r ACCESSIBILITY/device-tests/build/outputs/apk/debug/accessibility-device-tests-debug.apk
adb shell am instrument -w -r com.sihoo.aemx.devicechecks/.DeviceChecks
```

외부 검사 성공 출력은 `PASS: 5 external device checks; no skips`다. 실패한 검사 이름/화면 조건이 출력되면 성공으로 간주하지 않는다. 검사 종료 시 실패 보호 검사의 timeout 상태가 화면에 남는 것은 예상 결과다.

## 미검증 범위

교보문고/알라딘은 가상 기기에 설치되어 있지 않았으며 Google Play는 로그인 화면이었다. 따라서 실제 서점의 앱 실행·검색·로그인 E2E는 아직 검증하지 못했다. 두 앱을 설치한 뒤 실제 노드 라벨/ID를 확인하고 동일한 JSON 경로를 검증해야 한다. credential 저장/로그인 정보 제공은 여전히 팀 결정 사항이다.
