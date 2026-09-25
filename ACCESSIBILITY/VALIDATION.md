# 검증 기록 — 2026-09-25

후속 에뮬레이터 검증은 [`EMULATOR_TESTS.md`](EMULATOR_TESTS.md)에 기록했다. 아래의 "연결 기기 없음"은 최초 빌드 당시 상태이며, 이후 API 36 에뮬레이터에서 서비스 연결과 실제 노드 조작을 검증했다.

담당자: 공규태 / 구현 보완: Codex

## 입력과 브랜치

- 기존 원격 `feature/accessibility`: `d1e2261e263167ddb5951af35cf7601a8cb03189`
- 사용자 제공 `AEMX_AI_Empowerment_eXperience-feature-accessibility-completed.zip`의 변경을 이 브랜치 위에 적용했다.
- 새 브랜치를 만들지 않았다. main/다른 feature 브랜치를 수정하지 않았으며 PR/merge도 하지 않았다.
- ZIP에는 Git 이력이 없으므로 원격의 기존 브랜치를 복제해 이력을 유지했다.

## 프로젝트 분석

루트 `settings.gradle.kts`는 `app` 모듈을 빌드한다. `STT/SherpaOnnx`, `LLM/LlamaDemo`, `AEMX_upload`는 별도 프로젝트다. 메인 app에는 기존 TTS 컨트롤러와 테스트 Activity가 있다. 실행 코드는 app 소스에 두고 ACCESSIBILITY 문서로 모듈 경계를 관리했다.

`TASK_공규태.md`와 최신 원격 `LLM/README.md`를 확인했다. LLM의 app/steps/action/target/value 형식을 사용하며 wait/scroll 상세 규칙은 LLM 문서에 없어서 로컬 확장으로 명시했다. 보안 credential 저장소는 없으며 기존 더미 UI 데이터를 로그인 정보로 연결하지 않았다.

## 자동 검증

환경: Windows, 작업 폴더의 Temurin JDK 25, 프로젝트 Gradle Wrapper 9.5.0, 기존 Android SDK 37. 시스템 Java 설정은 변경하지 않았다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

- Debug APK 빌드 성공. 기존 TTS 코드도 동일 app 빌드에 포함된다.
- 자동 테스트 19개 통과: 파서 9, 실행기/노드 9(API 24/28 포함), 기존 기본 테스트 1.
- 실행기 테스트: 순서 유지, tap, ACTION_SET_TEXT 인자, 비동기 wait, 중단, 중복 제출 거부, 다른 패키지 timeout, credential 미설정, 앱 미설치, open_app 실행 후 root 대기.
- 노드 테스트: 정확 일치, 부분/숨김/중복 거부, resource-id, editable 필터, 클릭 가능한 부모.
- Android Manifest와 접근성 XML은 앱 빌드에 포함되어 처리되었다. 실제 OS의 서비스 활성화/바인딩은 기기 검증이 필요하다.
- 독립 STT/LLM/AEMX_upload 프로젝트는 수정하거나 재빌드하지 않았다. 전체 음성 파이프라인 검증은 아니다.
- 최초 빌드에서 ZIP의 `$MAX_STEPS개까지` 문자열이 잘못된 Kotlin 식별자로 해석되는 오류를 발견했고 `${MAX_STEPS}개까지`로 수정했다.

최종 Lint: 오류 0, 경고 7(모두 의존성/빌드 도구 최신 버전 알림). 최종 실행은 `BUILD SUCCESSFUL`, 53개 task, 19개 테스트 실패 0으로 완료됐다. 테스트의 구형 SDK 호환 API deprecation 및 JDK 25 native-access 경고는 비실패 경고다. 의존성 자동 업그레이드는 이번 범위에서 제외했다. Git 커밋 정보는 전달되는 작업 결과 보고서에 기록한다.

## 실기기 검증 필요

`adb devices` 결과 연결 기기 없음. 아래 항목은 성공했다고 판단하지 않았다.

1. APK 설치 → AEMX → 접근성 설정 열기 → 사용자가 서비스 직접 켜기 → 서비스 연결 상태 확인.
2. 교보문고/알라딘 설치 패키지명과 런처 확인. 앱 미설치 오류 확인.
3. 각 앱에서 `uiautomator dump`로 로그인/검색 화면의 text/content-desc/resource-id/editable/clickable 확인. 덤프는 민감 정보 확인 후 로컬에서만 취급.
4. JSON 실행 화면에서 `open_app → tap → input_text → tap` 실행. 실제 검색어가 입력되고 정확한 버튼이 눌리는지 확인.
5. 8초 지연, 비활성/중복/숨김 노드, 팝업, 화면 회전, 다른 앱 전환, 서비스 비활성화, 중단 후 재실행 확인.
6. API 24/25 기기와 최신 기기, WebView/Compose 화면 확인. 단위 테스트는 실제 접근성 노출을 보장하지 않는다.
7. credential 미설정 시 계획 중단 확인. 저장 방식과 필드 별칭을 팀이 확정한 뒤 로그인 전체 경로 검증.
8. TTS 엔진 준비/미준비 상태에서 완료 피드백 확인. 피드백은 UI 명령 전달 완료이며 실제 로그인 성공 판정이 아니다.
9. scroll up/down 방향과 wait 단위 규칙을 LLM 팀과 합의 후 실제 화면에서 확인.

## 남은 작업과 한계

- credential 저장/Autofill 구현, id/password 실제 필드 매핑, 별도 LLM 앱의 메인 app 통합은 TODO.
- `검색결과 첫번째 항목` 같은 의미적 위치 선택, 스크롤 거리/컨테이너 지정, 로그인·구매 결과 판정, 실패 후 LLM 재계획은 TODO.
- 동명 노드는 임의로 누르지 않는다. 고유 resource-id 또는 실기기에서 확인한 별칭이 필요하다.
- 이벤트 안정화와 다음 target 재탐색은 기본 대기일 뿐 모든 앱의 네트워크 완료를 보장하지 않는다.
- 프로세스 종료 시 계획을 저장하거나 재개하지 않는다. 입력 JSON과 credential도 영속 저장하지 않는다.

코드 검토와 기기 검증을 위한 상태이며, 실기기 E2E 통과/배포 완료로 간주하지 않는다.
