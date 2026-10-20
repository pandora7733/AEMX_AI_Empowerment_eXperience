package com.sihoo.aemx.pipeline.device

import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.port.AppResolver

/**
 * 앱 이름을 패키지명으로 바꾼다. 접근성 서비스가 지원하는 앱과 같은 표를 써서,
 * 오케스트레이터가 기대하는 패키지와 서비스가 조작하는 패키지가 어긋나지 않게 한다.
 */
class AccessibilityAppResolver : AppResolver {
    override fun resolvePackage(appName: String): String? = AppLauncher.packageFor(appName)
}
