package com.sihoo.aemx.pipeline.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq 의 OpenAI 호환 채팅 API 로 묻는다. 라이브러리 없이 HttpURLConnection 만 쓴다.
 *
 * 키는 생성자로 받는다. 코드·로그·예외 메시지 어디에도 키를 남기지 않는다.
 * 모델 이름과 한도는 바뀔 수 있으므로 [model] 은 설정으로 바꿀 수 있게 열어 둔다.
 */
class GroqChatClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val endpoint: String = ENDPOINT,
) : CloudChatClient {

    @Volatile
    private var answeredModel: String? = null

    override val lastModel: String? get() = answeredModel

    override suspend fun complete(system: String, user: String): String {
        if (apiKey.isBlank()) throw CloudChatException("API 키가 비어 있다")
        val connection = openConnection()

        // 블로킹 read 는 코루틴 취소로 깨지지 않으므로, 취소되면 소켓을 닫아 read 를 깨운다.
        // 블록이 끝날 때까지 기다리지 않고 바로 CancellationException 으로 돌아온다.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { connection.disconnect() }
            CoroutineScope(Dispatchers.IO).launch {
                cont.resumeWith(runCatching { send(connection, system, user) })
            }
        }
    }

    private fun openConnection(): HttpURLConnection = try {
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    } catch (e: IOException) {
        throw CloudChatException("연결을 만들지 못했다")
    }

    private fun send(connection: HttpURLConnection, system: String, user: String): String {
        try {
            connection.outputStream.use { it.write(buildBody(model, system, user).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // 본문에 요청 내용이 되돌아올 수 있으므로 코드만 남긴다.
                throw CloudChatException("클라우드 응답 오류 (HTTP $code)", code)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return extractContent(body)
                ?.also { answeredModel = model }
                ?: throw CloudChatException("응답에서 답을 찾지 못했다")
        } catch (e: CloudChatException) {
            throw e
        } catch (e: IOException) {
            throw CloudChatException("네트워크 오류 (${e::class.java.simpleName})")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        /** 무료 한도와 모델 목록은 바뀐다. 안 되면 Groq 콘솔의 모델 목록에서 골라 바꾼다. */
        const val DEFAULT_MODEL = "qwen/qwen3.8-27b"

        /** 첫 모델이 한도·서버·네트워크 오류로 실패할 때 한 번 더 묻는 모델. */
        const val FALLBACK_MODEL = "openai/gpt-oss-120b"

        /** 첫 모델 한 번의 시간 한도. 전체 5초 안에서 예비 모델이 들어갈 여유를 남긴다. */
        const val DEFAULT_TIMEOUT_MS = 3_000

        /**
         * 모델마다 추론 깊이를 맞춘다. 추론을 끄면 Qwen 은 빠르고 정확하고, gpt-oss 는 낮은 추론을 쓴다.
         * 모르는 모델은 아무것도 넣지 않는다(Groq 가 기본값을 쓴다).
         */
        internal fun reasoningEffort(model: String): String? = when {
            model.contains("qwen", ignoreCase = true) -> "none"
            model.contains("gpt-oss", ignoreCase = true) -> "low"
            else -> null
        }

        /** 같은 입력은 같은 계획이 나와야 하므로 temperature 0, 형식은 JSON 객체로 고정한다. */
        internal fun buildBody(model: String, system: String, user: String): String =
            JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("max_tokens", 512)
                .put("response_format", JSONObject().put("type", "json_object"))
                .also { body -> reasoningEffort(model)?.let { body.put("reasoning_effort", it) } }
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
                .toString()

        /** choices[0].message.content 를 꺼낸다. 없으면 null. */
        internal fun extractContent(body: String): String? = runCatching {
            JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
