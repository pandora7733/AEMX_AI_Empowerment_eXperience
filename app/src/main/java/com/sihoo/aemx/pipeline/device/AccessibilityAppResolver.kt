package com.sihoo.aemx.pipeline.device

import android.content.Context
import com.sihoo.aemx.accessibility.AppLauncher
import com.sihoo.aemx.pipeline.port.AppResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * 앱 이름을 패키지명으로 바꾼다. 접근성 서비스가 앱을 여는 방식과 같은 규칙을 써서,
 * 오케스트레이터가 기대하는 패키지와 서비스가 조작하는 패키지가 어긋나지 않게 한다.
 *
 * [resolve] 는 [AppLauncher.resolve] 와 같다: 고정 표(별칭 포함) -> 설치된 런처 앱 라벨 정확 일치
 * -> 라벨 포함 일치가 하나뿐일 때. 그래서 표에 없는 설치된 앱(예: 마켓컬리)도 풀린다.
 *
 * 풀린 앱은 패키지 -> 불린 이름으로 기억한다([nameFor]). 서비스는 앱을 이름으로 받는데, 명령에는
 * 패키지만 남기 때문이다. 불린 이름을 그대로 서비스에 넘기면 서비스도 같은 규칙으로 같은 패키지를 푼다.
 */
class AccessibilityAppResolver(
    private val resolve: (String) -> String?
) : AppResolver {

    /** 컨텍스트 없이 쓰면 고정 표만 본다(테스트와 단독 사용). */
    constructor() : this({ AppLauncher.packageFor(it) })

    /** 설치된 앱까지 본다. */
    constructor(context: Context) : this(AppLauncher(context)::resolve)

    private val names = ConcurrentHashMap<String, String>()

    override fun resolvePackage(appName: String): String? {
        val packageName = resolve(appName) ?: return null
        names[packageName] = appName.trim()
        return packageName
    }

    /** 이 패키지를 풀 때 쓴 앱 이름. 푼 적이 없으면 null. */
    fun nameFor(packageName: String?): String? = packageName?.let { names[it] }

    /** 서비스가 풀 수 있는 앱 이름인지. */
    fun isResolvable(appName: String): Boolean = resolve(appName) != null
}
