package dev.zzunkie.whenever.parse

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dev.zzunkie.whenever.BuildConfig
import dev.zzunkie.whenever.car.TAG
import dev.zzunkie.whenever.rule.RuleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 문장 → 규칙 초안. OpenAI Responses API 를 HTTPS 로 직접 부르고 함수 호출로 JSON 을 받는다.
 * 함수는 "규칙 만들기"와 "못 한다고 알리기" 두 개뿐이고, 차를 움직이는 함수는 주지 않는다.
 * 프롬프트·도구·응답 읽기는 LlmRequest(평가 실행기와 공유)에 있다. 받은 초안은 화면에 보이기 전에 Validator 를 거친다.
 */
object OpenAiParser {
    fun hasKey() = BuildConfig.OPENAI_API_KEY.isNotBlank()

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** null 이면 네트워크 문제(연결 없음, 시간 초과) */
    suspend fun parse(text: String): ParseResult? = withContext(Dispatchers.IO) {
        val body = LlmRequest.body(text, BuildConfig.OPENAI_MODEL, BuildConfig.OPENAI_REASONING)
        val started = System.currentTimeMillis()
        val conn = (URL(LlmRequest.URL_RESPONSES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            Log.i(TAG, "llm $code in ${System.currentTimeMillis() - started}ms")
            if (code !in 200..299) {
                Log.w(TAG, "llm error ${resp.take(300)}")
                return@withContext if (code == 429 || code >= 500) null
                else ParseResult.NotUnderstood("문장을 해석하지 못했어요. 잠시 뒤 다시 말해 주세요.")
            }
            val (result, problem) = LlmRequest.read(RuleJson.parseToJsonElement(resp).jsonObject)
            problem?.let { Log.w(TAG, "llm $it") }
            result
        } catch (e: java.io.IOException) {
            Log.w(TAG, "llm network $e")
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** 키가 있으면 LLM, 없으면 앱 안 해석기. 연결이 없으면 새로 만들기만 안내한다(저장된 자동화는 그대로 돈다). */
object Interpreter {
    const val OFFLINE = "지금은 연결이 없어서 새 문장을 해석할 수 없어요. 둘러보기의 예시에서 골라 주세요."

    suspend fun interpret(ctx: Context, text: String): ParseResult {
        if (!OpenAiParser.hasKey()) {
            return when (val r = LocalParser.parse(text)) {
                is ParseResult.NotUnderstood -> ParseResult.NotUnderstood(r.message + " 둘러보기에서 비슷한 자동화를 고를 수도 있어요.")
                else -> r
            }
        }
        if (!OpenAiParser.online(ctx)) return ParseResult.NotUnderstood(OFFLINE)
        return OpenAiParser.parse(text) ?: ParseResult.NotUnderstood(OFFLINE)
    }
}
