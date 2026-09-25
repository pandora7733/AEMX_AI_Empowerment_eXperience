# AEMX

AI Empowerment eXperience(AEMX) Android 프로젝트입니다. 이 문서는 저장소를 처음 내려받은 뒤 Android 개발환경을 구성하고, 프로젝트를 빌드하여 개발을 시작하기까지의 절차를 설명합니다.

LLM Action Plan을 실제 Android UI 조작으로 연결하는 실행 계층은
[`ACCESSIBILITY/README.md`](ACCESSIBILITY/README.md)를 참고하세요.

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
| 모듈 | `app`, 개발용 `accessibility-device-tests` |

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
git pull --ff-only
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

현재 `MainActivity`에 `MAIN`/`LAUNCHER`가 등록되어 있습니다. TTS 테스트, 접근성 설정 이동, Action Plan 실행 테스트를 앱에서 열 수 있습니다. 이번 실행 모듈 작업은 기존 `feature/accessibility` 브랜치에서만 진행합니다.

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
