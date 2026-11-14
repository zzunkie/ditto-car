package dev.zzunkie.whenever.parse

import dev.zzunkie.whenever.rule.Catalog
import dev.zzunkie.whenever.rule.RuleDraft
import dev.zzunkie.whenever.rule.RuleJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Responses API 요청 본문 만들기와 응답 읽기. 안드로이드에 기대지 않아서
 * 앱(OpenAiParser)과 평가 실행기(eval)가 같은 프롬프트·스키마·해석을 쓴다.
 */
object LlmRequest {
    const val URL_RESPONSES = "https://api.openai.com/v1/responses"

    val SYSTEM = """
        You turn a driver's Korean request into one automation rule for an in-car app on a Hyundai Pleos Connect (Android Automotive) electric car.
        A rule is: one trigger (when), up to 2 extra conditions (only if), and 1 to 4 actions.
        Call make_rule with values from the schema only. If the request needs a trigger, condition or action that is not in the schema, or is not an automation request, call cannot_do with one short Korean sentence in 해요체.
        Notes:
        - "비가 오면" means the wipers turn on (trigger wiper_on).
        - "아침 7시 전에 출발하면" is trigger gear D with condition time_before hour 7.
        - "아이 태우고 출발하면" is trigger gear D with condition door_opened_since_park door rear.
        - The app cannot open or close windows and cannot unlock doors. It can lock window switches, lock doors, and tell the driver a window is open (condition window_is_open + speak).
        - The app cannot read vehicle speed, seat belt buckling, or who is seated.
        - For speak and notify, write short, plain Korean in 해요체 without exclamation marks.
        - Keep numbers the driver said. If a level is not given, use 2 for seat/steering heat.
        - If the driver leaves out a number the rule needs (a battery percent, a brightness), pick a common value. The driver sees and can change every value on the confirm screen before saving.
        - Add a condition only when the driver asked for one. Do not add a condition that repeats or is already implied by the trigger (for example navigating with an arrival trigger), and do not add gear_is P for actions that need parking: the app holds those until the car is parked.
        - Add only the actions the driver asked for.
        - Use a trigger, condition or action only when it means what the driver asked. Do not swap in something that merely looks related (another place, another device, another event); call cannot_do instead.
    """.trimIndent()

    /** Catalog 의 도구 정의(name, description, input_schema)를 Responses API 의 함수 형식으로 옮긴다. */
    fun tools(): JsonArray = buildJsonArray {
        Catalog.tools().forEach { t ->
            val o = t.jsonObject
            addJsonObject {
                put("type", "function")
                put("name", o["name"]!!)
                put("description", o["description"]!!)
                put("parameters", o["input_schema"]!!)
                // 스키마가 anyOf 와 선택 필드를 써서 strict 모드 조건에 맞지 않는다. 검증은 Validator 가 한다.
                put("strict", false)
            }
        }
    }

    fun body(text: String, model: String, reasoning: String): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", SYSTEM)
        putJsonArray("input") {
            addJsonObject { put("role", "user"); put("content", text) }
        }
        put("tools", tools())
        put("tool_choice", "required")
        put("parallel_tool_calls", false)
        putJsonObject("reasoning") { put("effort", reasoning) }
        put("max_output_tokens", 2048)
        put("store", false)
    }

    /** 응답 본문 → 해석 결과. 실패 이유는 problem 에 담아 부르는 쪽이 로그로 남긴다. */
    fun read(resp: JsonObject): Pair<ParseResult, String?> {
        val call = resp["output"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "function_call" }
            ?: return ParseResult.NotUnderstood("무엇을 자동화할지 알아듣지 못했어요. 다시 말해 주세요.") to "no function_call"
        // arguments 는 JSON 을 담은 문자열로 온다.
        val input = try {
            RuleJson.parseToJsonElement(call["arguments"]!!.jsonPrimitive.content).jsonObject
        } catch (e: Exception) {
            return ParseResult.NotUnderstood("규칙으로 옮기지 못했어요. 조금 다르게 말해 주세요.") to "arguments parse failed $e"
        }
        return when (call["name"]?.jsonPrimitive?.content) {
            "make_rule" -> try {
                ParseResult.Ok(RuleJson.decodeFromJsonElement(RuleDraft.serializer(), input), "LLM") to null
            } catch (e: Exception) {
                ParseResult.NotUnderstood("규칙으로 옮기지 못했어요. 조금 다르게 말해 주세요.") to "rule decode failed $e in $input"
            }
            "cannot_do" -> ParseResult.CannotDo(input["message"]?.jsonPrimitive?.content ?: "이 차에서는 할 수 없는 요청이에요.") to null
            else -> ParseResult.NotUnderstood("무엇을 자동화할지 알아듣지 못했어요.") to "unknown function"
        }
    }
}
