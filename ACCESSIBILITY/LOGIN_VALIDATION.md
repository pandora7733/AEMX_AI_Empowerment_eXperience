# 로그인 입력 기능 검증 (2026-09-25)

## 구현

- 메인 화면 → 로그인 정보 관리에서 교보문고/알라딘별 아이디·비밀번호 등록, 교체, 삭제.
- Android Keystore 앱별 AES-256-GCM 키, 매 저장마다 새 IV, 패키지별 AAD, AtomicFile 저장.
- noBackupFilesDir의 암호문만 영속 저장. 삭제 시 Keystore 키도 삭제.
- 등록 화면 FLAG_SECURE, 비밀번호 마스킹, 뷰 상태 저장/Autofill/지원 버전의 Content Capture 제외, 저장·화면 종료 시 입력 제거.
- 기본 접근성 서비스에서 저장소 자동 연결. 작업 스레드에서 복호화, 취소 후 늦은 콜백 무시.
- input_credential id/password는 같은 앱의 고유 아이디 필드와 비밀번호 필드가 각각 하나일 때만 입력. 성공한 입력을 반복하지 않음.
- 로그인 버튼은 기존 tap 단계로 실행. JSON에는 계정 값을 넣지 않음.

## 실행한 검증

- `:app:assembleDebug :app:assembleDebugAndroidTest`: 성공.
- `:app:testDebugUnitTest`: 25개 통과 (기존 19개 + 로그인 검증 API 24/28 각각 3개).
- `:app:lintDebug`: 오류 0개, 기존 의존성 업데이트 경고 7개.
- Android 16/API 36 에뮬레이터 `AndroidJUnitRunner`: 8개 통과.
  - 기존 5개 UI/접근성 노드 검사.
  - 새 3개: 실제 Keystore 저장·재조회·삭제와 새 IV, 변조 및 다른 앱 암호문 복사 거부, 등록 화면 FLAG_SECURE 및 재생성 후 입력 제거/빈 입력 검사.
- 테스트 credential은 가짜 값이며 별도 `credential-test-only` 저장소/키를 사용하고 테스트 후 삭제. 실제 계정 저장소에는 테스트 값을 쓰지 않음.
- 최종 APK 재설치·재부팅 후 서비스가 Bound 상태이며 Crashed 목록에 없음을 확인했다. 별도 `devicechecks` 회귀 도구는 재부팅 직후 System UI 응답 지연과 이후 UiAutomation `DeadObjectException`으로 결과를 내지 못했다. 이번 변경의 외부 5개 회귀 검사는 통과로 집계하지 않는다. 위 8개 앱 instrumentation 테스트는 재부팅 전에 정상 완료했다.

## 남아 있는 검증

- 교보문고 검색은 사용자가 직접 확인한 결과이며 이번 로그인 자동 테스트 결과와 구분한다.
- 실제 교보문고/알라딘의 로그인 폼 resource-id/힌트/비밀번호 속성 호환성과 실제 계정 로그인 성공은 미검증. 노드가 다르거나 여러 개면 입력하지 않고 중단한다.
- CAPTCHA, OTP, 추가 인증은 사용자가 수행해야 한다. 로그인 성공/실패 결과 판정은 구현하지 않았다.
- 전체 STT → LLM → 실행 계층 통합 및 기기별/앱 업데이트 후 회귀 검증은 별도 작업이다.
- 저장소는 기기 로컬 자동입력용이며 키 사용마다 생체 인증을 요구하지 않는다. 단말 잠금 해제 상태에서 앱을 사용할 수 있는 사용자는 저장된 계정을 입력할 수 있다.

## 재현

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.sihoo.aemx.test/androidx.test.runner.AndroidJUnitRunner
```

기존 앱을 업데이트할 때는 기존 설치와 같은 서명을 사용한다. 같은 프로세스의 instrumentation이 접근성 서비스 연결에 영향을 주면 테스트 후 에뮬레이터를 재시작한다. 서비스 활성화는 사용자가 설정에서 직접 수행한다.
