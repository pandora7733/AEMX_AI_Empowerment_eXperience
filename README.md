# AEMX

AI Empowerment eXperience(AEMX) Android 프로젝트입니다. 이 문서는 저장소를 처음 내려받은 뒤 Android 개발환경을 구성하고, 프로젝트를 빌드하여 개발을 시작하기까지의 절차를 설명합니다.

## 1. 프로젝트 구성

현재 프로젝트는 다음 환경을 기준으로 구성되어 있습니다.

| 항목 | 설정 |
| --- | --- |
| 프로젝트 유형 | Android 애플리케이션 |
| 빌드 시스템 | Gradle Wrapper + Kotlin DSL |
| Android Gradle Plugin | 9.3.2 |
| Gradle Wrapper | 9.5.0 |
| compileSdk / targetSdk | Android API 37 |
| minSdk | Android API 24 |
| Java 소스/타깃 호환성 | Java 11 |
| 애플리케이션 ID | `com.sihoo.aemx` |
| 모듈 | `app` |

`Java 소스/타깃 호환성`이 Java 11이라는 뜻과 Gradle을 실행하는 JVM의 버전은 서로 다릅니다. Gradle 9.5 및 최신 Android Gradle Plugin을 실행하려면 JDK 17 이상이 필요하며, 저장소의 `gradle/gradle-daemon-jvm.properties`에 지정된 JDK 25를 권장합니다.

## 2. 사전 준비

다음 프로그램을 설치합니다.

1. Git
2. Android Studio 최신 안정 버전
3. JDK 25 권장(JDK 17 이상)
4. Android SDK Platform 37
5. Android SDK Platform-Tools
6. Android SDK Build-Tools 최신 버전

Android Studio를 설치하면 Android SDK와 JDK가 함께 제공될 수 있습니다. Android Studio의 `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 Gradle JDK가 JDK 17 이상인지 확인하고, 가능하면 JDK 25를 선택합니다.

### Android SDK 설치

Android Studio에서 `Tools > SDK Manager`를 열고 다음 항목을 설치합니다.

- `SDK Platforms` 탭: Android API 37
- `SDK Tools` 탭: Android SDK Build-Tools, Android SDK Platform-Tools

명령줄에서 SDK를 관리하는 경우에는 다음과 같이 실행할 수 있습니다. `sdkmanager`의 실제 위치는 운영체제와 Android Studio 설치 방식에 따라 다를 수 있습니다.

```bash
sdkmanager "platform-tools" "platforms;android-37"
sdkmanager --licenses
```

Linux/macOS에서 SDK 환경변수를 사용하는 예시는 다음과 같습니다.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

환경변수를 영구 적용하려면 사용하는 셸의 설정 파일(`~/.zshrc`, `~/.bashrc` 등)에 추가한 뒤 새 터미널을 열거나 해당 파일을 다시 읽습니다.

```bash
source ~/.zshrc
```

## 3. 저장소 최초 Clone

저장소를 저장할 디렉터리에서 다음 명령을 실행합니다.

```bash
git clone https://github.com/pandora7733/AEMX_AI_Empowerment_eXperience.git
cd AEMX_AI_Empowerment_eXperience
```

현재 체크아웃된 브랜치와 원격 저장소를 확인합니다.

```bash
git status
git branch --show-current
git remote -v
```

원격 저장소의 최신 변경사항을 반영해야 하는 경우에는 다음을 실행합니다.

```bash
git pull --ff-only origin main
```

## 4. Java 및 Android SDK 확인

Gradle 명령을 실행하기 전에 Java와 Android SDK가 정상적으로 설정되었는지 확인합니다.

```bash
java -version
echo "$JAVA_HOME"
echo "$ANDROID_HOME"
adb version
```

`java` 명령을 찾을 수 없다는 메시지가 나오면 JDK를 설치한 뒤 `JAVA_HOME`을 설정합니다. 예시는 다음과 같습니다.

```bash
export JAVA_HOME="/path/to/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
```

`/path/to/jdk-25`는 실제 JDK 설치 경로로 바꿔야 합니다. Android Studio에 포함된 JDK를 사용하는 경우에는 Android Studio의 Gradle JDK 설정에 표시된 경로를 사용합니다.

Gradle Wrapper가 사용하는 버전과 JVM을 확인합니다.

```bash
./gradlew --version
```

Windows에서는 동일한 명령을 다음처럼 실행합니다.

```bat
gradlew.bat --version
```

## 5. Android Studio에서 프로젝트 열기

1. Android Studio를 실행합니다.
2. `Open`을 선택합니다.
3. Clone한 프로젝트의 루트 디렉터리(`settings.gradle.kts`가 있는 디렉터리)를 선택합니다.
4. Gradle Sync가 끝날 때까지 기다립니다.
5. `File > Project Structure` 또는 Gradle 설정에서 Gradle JDK가 JDK 17 이상인지 확인합니다.

프로젝트가 `local.properties` 파일을 요구하는 경우 Android Studio가 SDK 위치를 자동으로 기록합니다. 이 파일은 개발자별 SDK 경로가 들어가는 로컬 설정 파일이므로 커밋하지 않습니다. 새로 생성할 때는 다음처럼 SDK 경로만 지정합니다.

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Windows 예시:

```properties
sdk.dir=C:\\Users\\사용자이름\\AppData\\Local\\Android\\Sdk
```

## 6. 최초 빌드

### Linux/macOS

프로젝트 루트에서 Gradle Wrapper에 실행 권한이 있는지 확인하고 빌드합니다.

```bash
chmod +x ./gradlew
./gradlew clean assembleDebug
```

### Windows

```bat
gradlew.bat clean assembleDebug
```

빌드가 성공하면 Debug APK가 다음 위치에 생성됩니다.

```text
app/build/outputs/apk/debug/app-debug.apk
```

`clean`은 이전 빌드 결과물을 삭제하는 작업이므로 매번 실행할 필요는 없습니다. 일반적인 개발 중에는 다음처럼 빠르게 빌드할 수 있습니다.

```bash
./gradlew assembleDebug
```

## 7. 테스트 및 정적 검사

로컬 JVM 단위 테스트를 실행합니다.

```bash
./gradlew test
```

Android Lint를 실행합니다.

```bash
./gradlew lint
```

연결된 Android 기기 또는 실행 중인 에뮬레이터에서 계측 테스트를 실행합니다.

```bash
./gradlew connectedDebugAndroidTest
```

모든 주요 검사를 한 번에 실행하려면 다음 명령을 사용할 수 있습니다.

```bash
./gradlew build
```

## 8. 에뮬레이터 또는 실제 기기에서 설치

Android Studio의 `Device Manager`에서 API 24 이상인 가상 기기를 만들고 실행합니다. 실제 기기를 사용하는 경우 개발자 옵션과 USB 디버깅을 활성화합니다.

연결 상태를 확인합니다.

```bash
adb devices
```

Debug APK를 설치합니다.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서 `app` 실행 구성을 선택한 뒤 `Run`을 눌러도 됩니다.

> TTS 기능 브랜치에는 `MAIN`/`LAUNCHER`가 등록된 `MainActivity`가 있습니다. 실행하면 한국어 TTS 검증 화면이 열립니다. 이 화면은 TTS 동작 확인용이며 팀의 최종 UI는 아닙니다.

## 9. 자주 발생하는 문제

### `JAVA_HOME is not set` 또는 Java를 찾을 수 없는 경우

JDK 17 이상을 설치하고 `JAVA_HOME`이 JDK 루트를 가리키도록 설정합니다. `JAVA_HOME`은 `bin` 디렉터리가 아니라 JDK 설치 디렉터리여야 합니다.

```bash
export JAVA_HOME="/path/to/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --version
```

### `SDK location not found`가 발생하는 경우

Android Studio에서 SDK가 설치된 위치를 확인한 후 `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_HOME`을 설정합니다. `local.properties`의 경로는 각 개발자 컴퓨터에 맞게 달라집니다.

### `failed to find target with hash android-37`가 발생하는 경우

SDK Manager에서 Android SDK Platform 37을 설치한 후 Android Studio의 Gradle Sync 또는 빌드를 다시 실행합니다.

### 의존성 다운로드가 실패하는 경우

이 프로젝트는 Google Maven Repository와 Maven Central을 사용합니다. 네트워크, 프록시, 사내 인증서 설정을 확인한 뒤 다음 명령을 다시 실행합니다.

```bash
./gradlew --refresh-dependencies assembleDebug
```

### `Permission denied: ./gradlew`가 발생하는 경우

Linux/macOS에서 Wrapper에 실행 권한을 부여합니다.

```bash
chmod +x ./gradlew
```

### Gradle 캐시 문제로 빌드가 실패하는 경우

먼저 Android Studio에서 Gradle Sync를 다시 실행하고, 필요한 경우 프로젝트의 빌드 산출물만 삭제한 뒤 재시도합니다.

```bash
./gradlew clean assembleDebug
```

## 10. 개발 시작 전 확인 목록

- [ ] Git Clone 및 `origin` 원격 저장소 확인
- [ ] JDK 17 이상 설치 및 `JAVA_HOME` 확인
- [ ] Android SDK Platform 37 설치
- [ ] Android SDK Platform-Tools와 Build-Tools 설치
- [ ] `./gradlew --version` 정상 실행
- [ ] `./gradlew clean assembleDebug` 성공
- [ ] `./gradlew test` 성공
- [ ] 필요한 경우 에뮬레이터 또는 실제 기기 연결 확인
- [ ] `local.properties` 및 서명 키 등 로컬 파일을 커밋하지 않았는지 확인

이 항목까지 완료하면 소스 수정, 테스트 작성, Android Studio 실행 구성 추가 등 다음 개발 작업을 시작할 수 있습니다.

## 11. 기본 개발 명령 요약

```bash
# 프로젝트 빌드
./gradlew assembleDebug

# 단위 테스트
./gradlew test

# Lint
./gradlew lint

# 연결된 기기에서 계측 테스트
./gradlew connectedDebugAndroidTest

# 빌드 산출물 삭제 후 재빌드
./gradlew clean assembleDebug
```

## 12. TTS 기능 명세 — Android TextToSpeech

### 목적과 범위

사용자가 입력한 한국어 문장을 기기의 기본 TTS 엔진으로 읽는 최소 기능입니다. 기본 문장은 `안녕하세요`이며 입력 내용을 바꿔 재생할 수 있습니다. 담당 브랜치는 `feature/tts`입니다.

STT, LLM 및 최종 UI 구현은 포함하지 않습니다. 현재 `MainActivity`의 입력창·버튼·상태 문구는 TTS를 독립적으로 검증하기 위한 화면입니다.

### 선택한 방식과 실행 조건

- Android 기본 `TextToSpeech` API를 사용합니다. Piper 모델이나 추가 네이티브 라이브러리는 포함하지 않습니다.
- 앱은 API 24 이상에서 실행되며 compileSdk/targetSdk는 기존 API 37을 유지합니다.
- 기기에 활성화된 TTS 엔진과 한국어 음성 지원이 필요합니다. 앱이 음성 데이터 설치를 자동으로 수행하지는 않습니다.
- 음질·속도·오프라인 가능 여부는 기기의 엔진과 설치된 음성 데이터에 따라 달라집니다. 앱 자체는 오프라인 음성을 강제하지 않습니다.
- 마이크 권한과 앱 자체의 인터넷 권한은 추가하지 않았습니다. 엔진의 네트워크 이용 여부는 엔진 설정에 따릅니다.

### 코드 위치와 동작

| 파일 | 역할 |
| --- | --- |
| `app/src/main/java/com/sihoo/aemx/MainActivity.kt` | 검증 화면, 엔진 초기화, 한국어 설정, 발화 요청, 상태 표시와 자원 해제 |
| `app/src/main/AndroidManifest.xml` | 실행 Activity와 Android 11 이상 TTS 서비스 조회용 `<queries>` 등록 |
| `app/src/main/res/values/strings.xml` | 기본 문장과 TTS 화면·상태 문구 |

1. Activity 생성 시 `TextToSpeech(this, this)`로 엔진을 비동기 초기화합니다. 완료 전에는 재생 버튼을 비활성화합니다.
2. `onInit()`에서 초기화 성공과 `setLanguage(Locale.KOREAN)` 결과를 확인합니다. 초기화 실패 또는 한국어 데이터 부족·미지원 시 안내를 표시하고 재생을 차단합니다.
3. 재생 버튼을 누르면 입력 앞뒤 공백을 제거합니다. 빈 문장은 안내만 표시합니다.
4. `speak(text, TextToSpeech.QUEUE_FLUSH, null, "aemx-korean-tts")`를 호출합니다. 재생 중 다시 누르면 이전 발화를 대체합니다.
5. `UtteranceProgressListener`의 시작·완료·오류 콜백을 UI 스레드의 상태 문구에 반영합니다. `speak()`의 즉시 오류 반환도 처리합니다.
6. Activity가 파괴되면 `stop()`과 `shutdown()`으로 엔진을 정리합니다.

### 다른 파트와 통합할 때

현재 TTS 로직은 `MainActivity`에 포함되어 있으며 외부에서 호출하는 별도의 공용 TTS 클래스는 없습니다. UI 담당자가 최종 화면을 통합할 때 엔진 초기화·언어 확인·발화·종료 흐름을 함께 옮기거나 별도 클래스로 분리해야 합니다. 준비 완료 전에 `speak()`를 호출하지 않도록 유지합니다.

STT 또는 LLM이 제공하는 문자열을 발화 입력으로 연결할 수 있습니다. `MainActivity`와 Manifest의 LAUNCHER 등록은 UI 담당자 변경과 겹칠 수 있으므로 PR 리뷰에서 최종 실행 화면을 조율합니다.

### 재현 방법과 완료 기준

프로젝트 루트에서 실행합니다. Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

```bash
./gradlew assembleDebug test lint
adb devices
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.sihoo.aemx/.MainActivity
```

`emulator-5554`는 `adb devices`에 나온 대상 식별자로 바꿉니다. Android Studio의 Run으로 실행해도 됩니다.

| 확인 항목 | 기대 결과 |
| --- | --- |
| 앱 시작 | `안녕하세요`가 표시되고 엔진 초기화 후 재생 버튼 활성화 |
| 기본 문장 재생 | 한국어 음성이 들리고 `음성 재생을 완료했습니다.` 표시 |
| 다른 짧은 한국어 문장 | 입력한 문장으로 재생 |
| 빈 입력 또는 공백 | `재생할 문장을 입력해 주세요.` 표시 |
| 초기화 실패 또는 한국어 미지원 | 안내 표시 및 재생 버튼 비활성화 |
| 화면 종료 후 재진입 | 엔진 정리 후 재초기화 |

한국어가 준비되지 않았으면 기기의 TTS 설정에서 한국어 음성 데이터를 설치하거나 지원 엔진을 선택한 뒤 앱을 다시 실행합니다. 재생 완료 문구만으로 PC 스피커에서 소리가 났다고 판정하지 않으며 미디어 음량과 실제 청취도 확인합니다.

### 검증 기록 및 남은 확인

2026-09-05 로컬 검증 기록입니다.

- `assembleDebug`, `test`, `lint` 작업 성공. 기존 `ExampleUnitTest` 1개 통과. 이 테스트는 TTS 자동 테스트가 아닙니다.
- Lint XML 기준 오류 0개, 경고 2개. 경고는 기존 AppCompat/Material의 새 버전 알림입니다.
- 격리된 Windows 빌드 환경의 파일 접근 오류 때문에 검증 시에만 JDK 17, 동일 버전의 직접 의존성 선언, 로컬 디버그 서명을 사용했습니다. 저장소 설정은 JDK 25와 version catalog로 복원했습니다. 팀 환경의 원본 설정 빌드는 PR 전 재확인해야 합니다.
- API 36 Google Play x86_64 에뮬레이터에 APK 설치와 Activity 실행 성공.
- `안녕하세요` 버튼을 눌러 한국어 `kor-KOR` 합성 요청, `ko-kr-x-ism-lstm-embedded` 엔진 선택 및 앱의 재생 완료 콜백을 확인했습니다.
- 당시 호스트 오디오 드라이버 초기화 경고가 있었으므로 실제 스피커 청취는 미확인입니다. 빈 입력·미지원 엔진·화면 재생성 등 예외 경로는 위 표에 따라 추가 확인합니다.
- 장문 분할, 엔진 선택 UI, 음성 데이터 자동 설치, 초기화 재시도 버튼은 현재 구현 범위에 없습니다. 장문과 빠른 연속 입력은 추가 검증이 필요합니다.

### 에뮬레이터와 팀 합의

현재 로컬 AVD는 `AEMX_TTS_API_36`, Pixel 7 하드웨어 프로필, API 36 Google Play x86_64입니다. 이 PC에서는 그래픽 안정화를 위해 화면을 720×1280 / 280 dpi로 조정하고 호스트 GPU와 Vulkan 비활성화 옵션을 사용했습니다. **팀 공통 디바이스로 확정된 설정이 아닙니다.**

팀원들과 모델, 화면 크기·해상도·밀도, Android API 및 시스템 이미지 종류를 합의한 후 공통 환경에서도 TTS를 재검증합니다. PC별 SDK 경로, AVD 데이터, APK와 서명 키는 커밋하지 않습니다.

### PR 준비

`feature/tts`에서 개발하고 `main`을 대상으로 PR을 제출합니다. 2026-09-05에 전달받은 일정 기준 제출 예정일은 다음 주 토요일 또는 일요일(9월 12~13일)이며 최종 날짜는 팀에서 확정합니다.

- [x] 한국어 TTS 최소 기능 및 검증 화면 구현
- [x] README 기능 명세 작성
- [x] 에뮬레이터 APK 설치·실행 및 재생 완료 콜백 확인
- [ ] 팀 공통 에뮬레이터 모델·크기 합의 및 해당 환경 재검증
- [ ] 정상 사용자 환경에서 원본 Gradle 설정 빌드 재확인
- [ ] 실제 스피커 청취 및 예외 경로 확인
- [ ] 기능 브랜치 push 및 `feature/tts` → `main` PR 제출
- [ ] 팀 리뷰·충돌 조정 후 main merge
