package dev.zzunkie.whenever.eval

import dev.zzunkie.whenever.parse.LlmRequest
import dev.zzunkie.whenever.parse.LocalParser
import dev.zzunkie.whenever.parse.ParseResult
import dev.zzunkie.whenever.rule.Phrase
import dev.zzunkie.whenever.rule.RuleDraft
import dev.zzunkie.whenever.rule.RuleJson
import dev.zzunkie.whenever.rule.Validator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.Properties

/**
 * 문장 해석 평가. eval/sentences.jsonl 의 문장을 앱과 같은 경로(LlmRequest 의 프롬프트·도구, LocalParser, Validator)로 돌려
 * eval/runs/<label>.jsonl (문장별)과 eval/runs/<label>.summary.json 을 남긴다.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ParseEval*' -PevalParser=llm [-PevalModel=gpt-6.1-terra] [-PevalLabel=이름]
 *   ./gradlew :app:testDebugUnitTest --tests '*ParseEval*' -PevalParser=local
 *
 * 키는 환경 변수 OPENAI_API_KEY 또는 local.properties 에서 읽는다. evalParser 가 없으면 건너뛴다.
 *
 * 기대 결과의 값 표기: "*" 는 값이 있기만 하면 됨, "a..b" 는 정수 범위, "x|y" 는 둘 중 하나.
 * 조건과 동작은 순서를 보지 않는다. speak/notify 의 문구는 "*" 로 두고 종류만 본다.
 */
class ParseEval {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "eval").exists()) it else it.parentFile }

    @Test
    fun run() {
        val parser = System.getProperty("evalParser")
        assumeTrue("set -PevalParser=llm|local to run", parser != null)
        val model = System.getProperty("evalModel") ?: "gpt-6-luna"
        val reasoning = System.getProperty("evalReasoning") ?: "none"
        val label = System.getProperty("evalLabel") ?: if (parser == "local") "local" else "$model-$reasoning"
        val key = if (parser == "llm") apiKey() else ""

        val cases = File(root, "eval/sentences.jsonl").readLines().filter { it.isNotBlank() }.map { RuleJson.parseToJsonElement(it).jsonObject }
        val outDir = File(root, "eval/runs").apply { mkdirs() }
        val rows = mutableListOf<JsonObject>()
        var inTok = 0L; var outTok = 0L

        for (c in cases) {
            val id = c["id"]!!.jsonPrimitive.content
            val text = c["text"]!!.jsonPrimitive.content
            val expect = c["expect"]!!.jsonPrimitive.content
            val started = System.nanoTime()
            var http = 0
            var problem: String? = null
            val result: ParseResult = if (parser == "local") LocalParser.parse(text) else {
                val (code, resp) = post(LlmRequest.body(text, model, reasoning).toString(), key)
                http = code
                if (code in 200..299) {
                    val o = RuleJson.parseToJsonElement(resp).jsonObject
                    (o["usage"] as? JsonObject)?.let {
                        inTok += it["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        outTok += it["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    }
                    LlmRequest.read(o).also { problem = it.second }.first
                } else {
                    problem = resp.take(300)
                    ParseResult.NotUnderstood("http $code")
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000.0

            val kind = when (result) { is ParseResult.Ok -> "make_rule"; is ParseResult.CannotDo -> "cannot_do"; else -> "not_understood" }
            val draft = (result as? ParseResult.Ok)?.draft
            val actual = draft?.let { RuleJson.encodeToJsonElement(RuleDraft.serializer(), it).jsonObject }
            val problems = draft?.let { Validator.check(it) }
            val full = kind == expect && (expect == "cannot_do" || (actual != null && matchRule(c["rule"]!!.jsonObject, actual)))
            rows += buildJsonObject {
                put("id", id.toInt()); put("text", text); put("expect", expect); put("kind", kind)
                put("kindOk", kind == expect); put("fullOk", full)
                put("validatorOk", problems?.isEmpty())
                put("ms", ms); put("http", http)
                actual?.let { put("rule", it); put("sentence", Phrase.sentence(draft)) }
                if (result is ParseResult.CannotDo) put("message", result.message)
                if (result is ParseResult.NotUnderstood) put("message", result.message)
                if (!problems.isNullOrEmpty()) put("problems", problems.joinToString(" / "))
                problem?.let { put("problem", it) }
            }
            println("${if (full) "OK  " else "MISS"} $id $kind ${"%.0f".format(ms)}ms $text")
        }

        File(outDir, "$label.jsonl").writeText(rows.joinToString("\n", postfix = "\n"))
        val summary = summarize(rows, label, parser, model, reasoning, inTok, outTok)
        File(outDir, "$label.summary.json").writeText(summary.toString() + "\n")
        println(summary)
    }

    private fun summarize(rows: List<JsonObject>, label: String, parser: String, model: String, reasoning: String, inTok: Long, outTok: Long): JsonObject {
        fun b(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content == "true"
        val sup = rows.filter { it["expect"]!!.jsonPrimitive.content == "make_rule" }
        val uns = rows.filter { it["expect"]!!.jsonPrimitive.content == "cannot_do" }
        val made = rows.filter { it["kind"]!!.jsonPrimitive.content == "make_rule" }
        val ms = rows.map { it["ms"]!!.jsonPrimitive.doubleOrNull!! }.sorted()
        fun pct(p: Double) = ms[((p * ms.size).toInt().coerceAtMost(ms.size - 1))]
        val median = if (ms.size % 2 == 0) (ms[ms.size / 2 - 1] + ms[ms.size / 2]) / 2 else ms[ms.size / 2]
        return buildJsonObject {
            put("label", label); put("parser", parser)
            if (parser == "llm") { put("model", model); put("reasoning", reasoning); put("inputTokens", inTok); put("outputTokens", outTok) }
            put("date", ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toString())
            put("n", rows.size)
            put("fullMatch", rows.count { b(it, "fullOk") })
            put("kindMatch", rows.count { b(it, "kindOk") })
            put("supportedFullMatch", "${sup.count { b(it, "fullOk") }}/${sup.size}")
            put("supportedMadeRule", "${sup.count { it["kind"]!!.jsonPrimitive.content == "make_rule" }}/${sup.size}")
            put("unsupportedRefused", "${uns.count { it["kind"]!!.jsonPrimitive.content == "cannot_do" }}/${uns.size}")
            put("unsupportedMadeRule", uns.count { it["kind"]!!.jsonPrimitive.content == "make_rule" })
            put("validatorPass", "${made.count { b(it, "validatorOk") }}/${made.size}")
            put("httpErrors", rows.count { val h = it["http"]!!.jsonPrimitive.intOrNull ?: 0; h != 0 && h !in 200..299 })
            put("msMedian", median); put("msP95", pct(0.95)); put("msMax", ms.last())
        }
    }

    // ---------- 비교 ----------

    private fun matchRule(exp: JsonObject, act: JsonObject): Boolean =
        match(exp["trigger"]!!, act["trigger"]) &&
            matchList(exp["conditions"] as? JsonArray ?: JsonArray(emptyList()), act["conditions"] as? JsonArray ?: JsonArray(emptyList())) &&
            matchList(exp["actions"] as JsonArray, act["actions"] as JsonArray)

    /** 순서 없이 하나씩 짝지어 모두 맞는지 */
    private fun matchList(exp: JsonArray, act: JsonArray): Boolean {
        if (exp.size != act.size) return false
        fun go(i: Int, used: Set<Int>): Boolean =
            i == exp.size || act.indices.any { j -> j !in used && match(exp[i], act[j]) && go(i + 1, used + j) }
        return go(0, emptySet())
    }

    private fun match(e: JsonElement, a: JsonElement?): Boolean {
        if (a == null || a is JsonNull) return false
        return when (e) {
            is JsonObject -> a is JsonObject && e.keys == a.keys && e.all { (k, v) -> match(v, a[k]) }
            is JsonArray -> a is JsonArray && matchList(e, a)
            is JsonPrimitive -> {
                if (a !is JsonPrimitive) return false
                val s = e.content
                val range = Regex("^(-?\\d+)\\.\\.(-?\\d+)$").find(s)
                when {
                    e.isString && s == "*" -> true
                    e.isString && range != null -> a.doubleOrNull?.let { it >= range.groupValues[1].toDouble() && it <= range.groupValues[2].toDouble() } ?: false
                    e.isString && s.contains('|') -> a.isString && a.content in s.split('|')
                    !e.isString && e.doubleOrNull != null -> a.doubleOrNull == e.doubleOrNull
                    else -> e.isString == a.isString && s == a.content
                }
            }
        }
    }

    // ---------- 호출 ----------

    private fun apiKey(): String {
        System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        val p = Properties().apply { File(root, "local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        return p.getProperty("OPENAI_API_KEY", "").trim().also { require(it.isNotBlank()) { "OPENAI_API_KEY 가 없어요" } }
    }

    /** 앱(OpenAiParser)과 같은 시간 제한. 429·5xx 는 2초 쉬고 한 번 더 부른다(재시도 시간도 응답 시간에 포함). */
    private fun post(body: String, key: String, retry: Boolean = true): Pair<Int, String> {
        val conn = (URL(LlmRequest.URL_RESPONSES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 20000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (retry && (code == 429 || code >= 500)) { Thread.sleep(2000); post(body, key, false) } else code to text
        } catch (e: java.io.IOException) {
            if (retry) post(body, key, false) else -1 to e.toString()
        } finally {
            conn.disconnect()
        }
    }
}
