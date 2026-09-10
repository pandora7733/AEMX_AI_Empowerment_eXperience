# AEMX — 자동 로그인 관리 앱 (UI 껍데기)

안드로이드 앱의 **화면 껍데기(UI Shell)** 입니다.
로그인 로직 · 암호화 · 서버 통신 · DB 는 없고, 화면 구성과 화면 전환만 되어 있습니다.
미구현 버튼은 "아직 준비 중인 기능입니다" 토스트로 처리되어 있습니다.

- 패키지: `com.aemx.autologin`
- 언어: Kotlin + Java 혼용, UI 는 XML
- minSdk 24 / targetSdk 35 · ViewBinding · Material 3 · 라이트/다크 모드 대응

---

## 실행하려면

필요한 것은 **Android Studio** 하나입니다. (JDK 17 과 Android SDK 가 함께 설치됩니다.)

1. Android Studio 설치 — <https://developer.android.com/studio>
   Ladybug(2024.2.1) 이상 권장.
2. `Tools > SDK Manager > SDK Platforms` 에서 **Android 15 (API 35)** 체크 후 Apply.
3. `File > Open` 으로 이 폴더를 엽니다.
4. Gradle Sync 가 끝날 때까지 기다립니다. (첫 실행은 몇 분 걸립니다.)
5. 에뮬레이터나 실기기를 연결하고 ▶ Run.

에뮬레이터가 없다면 `Tools > Device Manager > Create Device` 에서
Pixel 7 · **API 35** 이미지로 하나 만들면 됩니다.

---

## 터미널에서 빌드하려면

Gradle 을 따로 설치할 필요 없습니다. 래퍼가 포함되어 있습니다.

```bash
./gradlew assembleDebug          # 맥 / 리눅스
gradlew.bat assembleDebug        # 윈도우
```

결과물: `app/build/outputs/apk/debug/app-debug.apk`

---

## 안 될 때

| 증상 | 해결 |
|---|---|
| `SDK location not found` | Android Studio 로 프로젝트를 한 번 열면 `local.properties` 가 자동 생성됩니다. 터미널만 쓴다면 아래 참고 |
| `Unsupported class file major version` | JDK 17 이 아닙니다. `Settings > Build Tools > Gradle > Gradle JDK` 를 17 로 |
| `licences have not been accepted` | SDK Manager 에서 라이선스 동의, 또는 `sdkmanager --licenses` 후 전부 `y` |
| `Permission denied: ./gradlew` (맥/리눅스) | `chmod +x gradlew` |
| 빌드는 되는데 앱이 바로 꺼짐 | `adb logcat` 에서 `FATAL EXCEPTION` 검색 |

> `local.properties` 는 SDK 경로가 사람마다 달라서 저장소에 넣지 않습니다.
> 저장소에 없는 게 정상이고, Android Studio 가 알아서 만듭니다.
> 터미널만 쓴다면 프로젝트 루트에 직접 만들고 한 줄만 쓰면 됩니다.
>
> ```properties
> sdk.dir=/Android/Sdk 가 설치된 경로
> ```
>
> 환경변수 `ANDROID_HOME` 이 잡혀 있으면 이 파일 없이도 빌드됩니다.
