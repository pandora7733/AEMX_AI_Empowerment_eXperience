# TTS (Text-to-Speech) 파트

담당: 공규태

## 상태

Android 기본 `TextToSpeech`를 이용한 한국어 음성 출력 최소 기능 구현 완료. 에뮬레이터에서 사용자가 직접 문장을 입력·수정하고 음성이 정상 재생되는 것을 확인했습니다.

현재 `feature/tts` 브랜치의 메인 `app` 모듈(`com.sihoo.aemx`)에 구현되어 있습니다. `MainActivity`는 TTS 검증용 화면이며, STT·LLM 연결과 팀 최종 UI 통합은 팀 논의 후 진행할 예정입니다.

## 사용한 것

| 항목 | 내용 |
| --- | --- |
| 엔진 | 기기에 설정된 기본 Android TTS 엔진 |
| API | Android `TextToSpeech`, `UtteranceProgressListener` |
| 언어 | `Locale.KOREAN` |
| 방식 | 입력 문자열 → `AndroidTtsController` → 기기 TTS 엔진 → 음성 출력 |
| 별도 모델 | 앱에 포함하지 않음. 한국어 음성 데이터는 기기의 TTS 엔진에서 관리 |

## 기능 명세서

### 개요

사용자가 입력한 한국어 문장을 음성으로 읽습니다. 기본 문장은 `안녕하세요`이며, 입력창에서 내용을 수정하거나 지우기 아이콘으로 비운 뒤 새 문장을 입력할 수 있습니다.

TTS 처리 코드는 화면과 분리되어 있어 향후 UI 또는 LLM 파트에서 문자열을 전달해 재사용할 수 있습니다.

### 입력 / 출력

| 구분 | 내용 |
| --- | --- |
| 입력 | `speak(rawText: String)`에 전달하는 문자열. 앞뒤 공백 제거 |
| 출력 | 기기 스피커 등의 오디오 출력과 `State` 상태 콜백 |
| 빈 입력 | 재생하지 않고 `EMPTY_TEXT` 전달 |
| 재생 방식 | `QUEUE_FLUSH`: 새 요청이 기존 발화를 대체 |
| 권한 | 앱에 마이크·인터넷 권한을 추가하지 않음. TTS 서비스 조회용 `<queries>` 선언 사용 |
| 실행 조건 | 기기에 TTS 엔진과 한국어 음성 지원 필요 |

### 핵심 API (`AndroidTtsController.kt`)

실제 사용 예시는 `MainActivity.kt`에 있습니다.

| API | 역할 |
| --- | --- |
| `AndroidTtsController(context, onStateChanged)` | 엔진을 비동기로 초기화하고 한국어 설정 |
| `speak(rawText: String)` | 입력 검사 후 음성 재생 요청. 반환값은 `Unit`이며 결과는 콜백으로 전달 |
| `shutdown()` | 현재 발화를 중지하고 엔진 자원 해제 |

아래는 Activity에서의 연동 예시입니다. `READY`가 전달된 후 재생을 요청합니다. 상태 콜백은 UI 스레드가 아닐 수 있어 화면 갱신 시 `runOnUiThread`를 사용합니다.

```kotlin
private lateinit var ttsController: AndroidTtsController

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    ttsController = AndroidTtsController(applicationContext) { state ->
        runOnUiThread {
            // READY: 재생 가능
            // SPEAKING / DONE: 재생 시작 / 완료
            // 오류 상태는 사용자에게 안내
        }
    }
}

// 엔진 준비 후 버튼 클릭 또는 LLM 응답 완료 시 호출
private fun readText(text: String) {
    ttsController.speak(text) // 예: "안녕하세요"
}

override fun onDestroy() {
    if (::ttsController.isInitialized) {
        ttsController.shutdown()
    }
    super.onDestroy()
}
```

컨트롤러는 현재 `internal` 클래스이므로 같은 `app` 모듈에서 사용할 수 있습니다. 별도 모듈로 분리할 때는 접근 범위와 의존성 구성을 함께 조정해야 합니다.

### 상태값

| 상태 | 의미 |
| --- | --- |
| `INITIALIZING` | 초기화 중 또는 준비 전 재생 요청 |
| `READY` | 엔진 초기화와 한국어 설정 완료 |
| `SPEAKING` | 발화 시작 콜백 수신 |
| `DONE` | 발화 완료 콜백 수신 |
| `EMPTY_TEXT` | 입력이 비어 있거나 공백뿐임 |
| `INIT_FAILED` | 엔진 초기화 실패 |
| `KOREAN_UNAVAILABLE` | 한국어 데이터 부족 또는 한국어 미지원 |
| `SPEAK_FAILED` | 재생 요청 실패 또는 발화 오류 |

검증 화면은 초기화 중·초기화 실패·한국어 미지원 상태에서 재생 버튼을 비활성화합니다. `DONE`은 엔진 콜백이므로 실제 소리 출력은 미디어 음량과 청취로도 확인해야 합니다.

### 설정

| 항목 | 값 |
| --- | --- |
| 최소 Android 버전 | API 24 |
| compileSdk / targetSdk | API 37 |
| 언어 | `Locale.KOREAN` |
| 발화 식별자 | `aemx-korean-tts` |
| 큐 정책 | `TextToSpeech.QUEUE_FLUSH` |
| 음색·속도·피치 | 앱에서 별도 지정하지 않음 |
| 종료 처리 | Activity의 `onDestroy()`에서 `shutdown()` 호출 |

### 성능 / 제약

- 음질·재생 시작 시간·오프라인 가능 여부는 기기 엔진과 설치된 음성 데이터에 따라 달라집니다. 현재 코드는 오프라인 음성을 강제하지 않습니다.
- 별도 ONNX 모델이나 네이티브 라이브러리를 앱에 포함하지 않습니다. 음성 데이터 용량은 엔진이 별도로 관리합니다.
- 초기화는 비동기로 진행되며 완료 후 재생해야 합니다.
- 장문 분할, 엔진 선택 UI, 음성 데이터 자동 설치, 초기화 재시도 버튼은 구현하지 않았습니다.
- 연속 요청은 기존 발화를 대체합니다. 빠른 연속 입력과 장문 처리는 추가 검증이 필요합니다.
- STT와 함께 사용할 때 TTS 소리가 마이크에 다시 입력되지 않도록 녹음·재생 순서를 팀에서 정해야 합니다.

## AI 활용 — 연동 코드와 두 옵션 비교

AI를 활용해 Android TTS 연동 코드, 재사용 가능한 컨트롤러, 입력 수정 기능과 문서를 작성·검토했습니다. 아래는 구현 방식에 따른 정성 비교이며, 동일 기기에서 두 엔진의 용량·음질·속도를 측정한 벤치마크는 아닙니다. sherpa-onnx + Piper는 이번 TTS 코드에 구현하지 않았습니다.

| 항목 | Android TTS (현재 채택) | sherpa-onnx + Piper (비교 대상) |
| --- | --- | --- |
| 용량 | 앱에 별도 음성 모델을 넣지 않음. 엔진·음성 데이터는 기기에서 관리 | 네이티브 라이브러리와 선택 모델 등의 배포 용량 필요 |
| 음질 | 기기의 엔진과 음성 데이터에 따라 차이 | 선택한 모델에 따라 달라짐. 공통 모델 배포 시 음색을 통일하기 용이 |
| 속도 | 엔진 초기화와 합성 방식에 따라 달라짐 | 모델 로딩과 기기 내 추론 비용에 따라 달라짐 |
| 오프라인 | 엔진과 설치 음성에 따라 다름 | 필요한 모델·라이브러리를 갖춘 기기 내 추론 구성 가능 |
| 연동 범위 | Android API 초기화·재생·해제 | 모델·네이티브 라이브러리·ABI 및 자원 관리 필요 |
| 선택 이유 | 현재 목표인 한국어 최소 재생 기능을 구현하고 팀에 연결하기 위해 채택 | 고정 음색·오프라인 요구가 확정되면 후속 검토 |

## 폴더 구조

```text
AEMX_AI_Empowerment_eXperience/
├── README.md
└── app/src/main/
    ├── AndroidManifest.xml       # 실행 Activity, TTS 서비스 조회 선언
    ├── java/com/sihoo/aemx/
    │   ├── MainActivity.kt        # 입력 수정·재생 버튼·상태 표시
    │   └── AndroidTtsController.kt # 엔진 초기화·발화·상태·해제
    └── res/values/
        └── strings.xml           # 기본 문장과 화면 안내 문구
```

## 실행 방법

1. Android Studio에서 저장소 루트(`settings.gradle.kts`가 있는 폴더)를 엽니다.
2. Android SDK Platform 37, Platform-Tools와 Build-Tools를 설치하고, 저장소의 Gradle JVM 설정에 맞춰 JDK 25를 준비한 뒤 Sync합니다. SDK 경로는 Android Studio가 생성하는 `local.properties`에서 확인하며 이 파일은 커밋하지 않습니다.
3. API 24 이상 기기 또는 에뮬레이터에 한국어 지원 TTS 엔진·음성 데이터를 준비합니다.
4. `app` 실행 구성으로 Run합니다.
5. 엔진 준비 후 기본 문장 `안녕하세요`를 재생합니다.
6. 입력창의 문장을 수정하고 다시 재생하여 변경된 내용이 들리는지 확인합니다.

Windows PowerShell에서 직접 빌드·설치할 수도 있습니다.

```powershell
.\gradlew.bat assembleDebug test lint
adb devices
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.sihoo.aemx/.MainActivity
```

`emulator-5554`는 `adb devices`에 표시된 대상 식별자로 바꿉니다. Linux/macOS에서는 `.\gradlew.bat` 대신 `./gradlew`를 사용합니다.

## 검증 기록

기존 2026-09-05~06 로컬 검증 기록과 이후 사용자 재생·입력 확인을 기준으로 작성했습니다. 이번 문서 수정에서 빌드나 음성 테스트를 새로 실행한 것은 아닙니다.

| 항목 | 확인 내용 |
| --- | --- |
| 빌드·검사 | 기존 기록에서 `assembleDebug`, `test`, `lint` 성공 |
| 단위 테스트 | 기존 `ExampleUnitTest` 1개 통과. TTS 전용 자동 테스트는 아님 |
| Lint | 기존 기록상 오류 0개, 의존성 새 버전 알림 경고 2개 |
| 에뮬레이터 | API 36 Google Play x86_64에서 APK 설치·Activity 실행 |
| 한국어 출력 | 재생 완료 콜백 확인 및 사용자의 실제 청취 확인 |
| 입력 수정 | 사용자가 문장을 수정해 재생하는 동작 확인 |
| 예외 경로 | 기존 기록에서 빈 입력·공백·엔진 비활성화·앱 재실행 확인 |

기존 로컬 빌드 검증에는 격리 환경의 파일 접근 오류를 우회하기 위한 JDK 17, 동일 버전 직접 의존성 선언, 로컬 디버그 서명이 사용됐습니다. 저장소 설정은 JDK 25와 version catalog로 복원했으므로 팀 환경에서 원본 설정 빌드를 재확인해야 합니다.

로컬 AVD는 `AEMX_TTS_API_36`이며 Pixel 7 프로필, API 36 Google Play x86_64를 사용했습니다. 화면은 로컬 그래픽 안정화를 위해 720×1280 / 280 dpi로 조정했습니다. 팀 공통 디바이스로 확정된 설정은 아닙니다.

### 문제 해결 기록 (다음 사람을 위해)

- **글자 수정이 어려움**: 입력창을 `TextInputEditText`와 `TextInputLayout`으로 구성하고 지우기 아이콘·키보드 표시 동작을 추가했습니다.
- **입력창 레이아웃 호환 문제**: 입력창을 추가할 때 호환되는 `LinearLayout.LayoutParams`를 사용하도록 수정했습니다.
- **한국어 키보드가 보이지 않음**: 키보드 언어와 한국어 TTS 음성 데이터는 별개입니다. 기기 키보드 설정에서 한국어 입력을 준비해야 하며, 사용자가 이후 동작을 확인했습니다.
- **한국어 재생 불가**: 기기의 TTS 설정에서 한국어 지원 엔진과 음성 데이터를 확인한 뒤 앱을 다시 실행합니다. 앱은 데이터 설치를 자동으로 수행하지 않습니다.
- **완료 문구는 나오는데 소리가 안 들림**: 기기 미디어 음량과 PC 출력 장치를 확인합니다. 완료 콜백만으로 실제 청취 성공을 판정하지 않습니다.

## 다음 할 일

- [ ] 팀 공통 에뮬레이터 모델·화면 설정 합의 후 재검증
- [ ] 팀 환경에서 원본 Gradle 설정으로 빌드 재확인
- [ ] 실기기와 다양한 문장·장문·빠른 연속 요청 추가 테스트
- [ ] STT·LLM·최종 UI와 연결하고 녹음·재생 순서 협의
- [ ] `feature/tts` → `main` PR 제출 및 팀 리뷰 후 병합

기능 코드의 push 성공 기록은 로컬 Git에서 확인했습니다. PR 생성과 main 병합 완료 여부는 확인되지 않았습니다.
