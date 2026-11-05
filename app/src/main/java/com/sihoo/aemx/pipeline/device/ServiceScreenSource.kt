package com.sihoo.aemx.pipeline.device

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.sihoo.aemx.accessibility.AemxAccessibilityService
import com.sihoo.aemx.accessibility.NodeFinder
import java.lang.ref.WeakReference
import java.lang.reflect.Field

/**
 * 실제 접근성 서비스에서 화면을 읽는 [ScreenSource].
 *
 * 화면을 읽으려면 서비스 인스턴스의 `rootInActiveWindow` 가 필요한데, 서비스는 인스턴스를
 * 밖에 내놓지 않는다(companion 의 `activeService` 가 private). `accessibility/` 는 다른 담당자
 * 코드라 손대지 않고, 우선 이 필드를 리플렉션으로 읽는다. 필드를 못 찾으면 [read] 는 null 만
 * 돌려주고(오케스트레이터는 화면 검증을 못 하고 실행만 한다) 로그를 한 번 남긴다.
 *
 * 근본 해결은 서비스에 읽기 전용 훅을 하나 여는 것이다. 예: `AemxAccessibilityService` 에
 * `internal fun activeRootOrNull(): AccessibilityNodeInfo?` 같은 함수를 추가하면 이 리플렉션을 지울 수 있다.
 * 아직 그런 함수는 없다. 지금은 위에서 말한 리플렉션으로 읽는다.
 * 화면 라벨 요약은 서비스가 실패 때 쓰는 [NodeFinder.describeScreen] 을 그대로 재사용한다.
 */
internal class ServiceScreenSource : ScreenSource {

    private val serviceField: Field? by lazy {
        try {
            AemxAccessibilityService::class.java.getDeclaredField("activeService").apply { isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "서비스 인스턴스 필드를 찾지 못해 화면을 읽을 수 없다", e)
            null
        }
    }

    override fun isReady(): Boolean = AemxAccessibilityService.isConnected()

    override fun read(): ScreenReading? {
        val field = serviceField ?: return null
        val service = (field.get(null) as? WeakReference<*>)?.get() as? AccessibilityService ?: return null
        val root = service.rootInActiveWindow ?: return null
        return ScreenReading(
            description = NodeFinder.describeScreen(root),
            // 루트 노드의 클래스는 늘 같은 레이아웃 이름이라 화면 구분에 쓸모가 없다. 창 제목이 낫다.
            title = root.window?.title?.toString() ?: root.className?.toString()
        )
    }

    private companion object {
        const val TAG = "ServiceScreenSource"
    }
}
