# AEMX ProGuard/R8 규칙
# 현재 release 빌드는 minifyEnabled=false 라서 실제로 적용되지 않지만,
# 추후 난독화를 켤 때를 대비해 최소 규칙을 미리 정리해 둔다.

# 디버깅용 스택 트레이스를 읽을 수 있도록 소스/라인 정보를 남긴다.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 애노테이션·시그니처 정보 유지 (리플렉션/제네릭 대응)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# XML 레이아웃에서 이름으로 참조되는 View 계열은 생성자를 유지해야 한다.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Fragment 는 클래스 이름으로 재생성되므로 유지한다.
-keep public class * extends androidx.fragment.app.Fragment

# ViewBinding 이 생성한 바인딩 클래스의 inflate/bind 정적 메서드 유지
-keep class com.aemx.autologin.databinding.** { *; }

# 데이터 모델은 필드명이 보존되어야 향후 직렬화 확장이 쉽다.
-keep class com.aemx.autologin.data.model.** { *; }

# Kotlin 표준 라이브러리 경고 억제
-dontwarn kotlin.**
