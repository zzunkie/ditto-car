package dev.zzunkie.whenever.rule

import dev.zzunkie.whenever.parse.LocalParser
import dev.zzunkie.whenever.parse.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleLogicTest {

    private fun ok(text: String): RuleDraft {
        val r = LocalParser.parse(text)
        assertTrue("$text -> $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).draft
    }

    @Test
    fun demoSentencesBecomeRulesAndReadBack() {
        val cases = listOf(
            "아침 7시 전에 출발하면 앞유리 서리 제거와 스티어링 휠 열선을 켜고 시트 열선을 2단으로 해 줘" to
                "아침 7시 전에 출발하면 앞유리 서리 제거를 켜고 핸들 열선을 2단으로 켜고 운전석 열선을 2단으로 켭니다.",
            "배터리가 20% 아래로 내려가면 가는 길 충전소를 경유지로 넣어 줘" to
                "배터리가 20% 아래로 내려가면 가는 길에 있는 충전소를 경유지로 넣습니다.",
            "충전소에 도착하면 충전구를 열어 줘" to "충전소 근처에 오면 충전구를 엽니다.",
            "목적지에 도착하면 충전구를 열어 줘" to "목적지에 도착하면 충전구를 엽니다.",
            "뒷문이 열렸다 닫힌 뒤 출발하면 창문 잠그고 온도 22도로 맞춰 줘" to
                "뒷문을 열었다 닫은 뒤 출발하면 창문 잠금을 켜고 온도를 22도로 맞춥니다.",
            "주차할 때 창문이 열려 있으면 알려 줘" to "창문이 열려 있을 때 주차하면 \"창문이 열려 있어요\"라고 말합니다.",
            "충전을 시작하면 무드등을 어둡게 해 줘" to "충전을 시작하면 무드등 밝기를 20%로 맞춥니다.",
            "밤 10시 전에 뒷문이 열리면 실내등 켜 줘" to "밤 10시 전에 뒷문이 열리면 실내등을 켭니다.",
        )
        for ((text, expected) in cases) assertEquals(text, expected, Phrase.sentence(ok(text)))
    }

    @Test
    fun unsupportedRequestsAreRefusedWithOneSentence() {
        for (text in listOf("주차하면 창문 닫아 줘", "시속 100킬로 넘으면 알려 줘", "집에 오면 문 잠금 풀어 줘")) {
            val r = LocalParser.parse(text)
            assertTrue("$text -> $r", r is ParseResult.CannotDo)
        }
    }

    @Test
    fun validatorChecksRangesAndParkOnlyOnDriveGear() {
        val bad = RuleDraft(Trigger.Gear(GearSel.D), emptyList(), listOf(Action.Temperature(35f), Action.DoorLock))
        assertEquals(2, Validator.check(bad).size)
        val good = Samples.bundles.flatMap { it.rules }
        good.forEach { assertTrue(Phrase.sentence(it), Validator.check(it).isEmpty()) }
        assertTrue(Validator.check(good[0], unsupported = setOf("defrost")).isNotEmpty())
    }

    @Test
    fun ruleJsonRoundTrip() {
        val d = Samples.bundles[2].rules[0]
        val json = RuleJson.encodeToString(RuleDraft.serializer(), d)
        assertTrue(json, json.contains("\"type\":\"charging_started\""))
        assertEquals(d, RuleJson.decodeFromString(RuleDraft.serializer(), json))
    }
}
