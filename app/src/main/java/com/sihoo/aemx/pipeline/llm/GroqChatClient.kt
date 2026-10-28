package com.sihoo.aemx.pipeline.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw CloudChatException("API 키가 비어 있다")

        val connection = try {
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

        try {
            connection.outputStream.use { it.write(buildBody(model, system, user).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // 본문에 요청 내용이 되돌아올 수 있으므로 코드만 남긴다.
                throw CloudChatException("클라우드 응답 오류 (HTTP $code)", code)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            extractContent(body) ?: throw CloudChatException("응답에서 답을 찾지 못했다")
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
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"

        /** 데모에서 오래 멈춰 있으면 안 된다. 넘으면 로컬 계획으로 되돌린다. */
        const val DEFAULT_TIMEOUT_MS = 8_000

        /** 같은 입력은 같은 계획이 나와야 하므로 temperature 0, 형식은 JSON 객체로 고정한다. */
        internal fun buildBody(model: String, system: String, user: String): String =
            JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("max_tokens", 512)
                .put("response_format", JSONObject().put("type", "json_object"))
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
