package dev.zzunkie.whenever.rule

// 규칙 초안이 이 차에서 실행 가능한지 코드로 확인한다. LLM 이 만든 초안도, 둘러보기 예시도, QR 로 받은 규칙도 여기를 거친다.
// 문제가 있으면 운전자에게 보여 줄 짧은 한국어 문장 목록을 돌려준다(비어 있으면 통과).

object Validator {

    /** unsupported: 이 차의 capability 확인에서 쓰기가 안 된다고 나온 동작 종류(type 이름) */
    fun check(r: RuleDraft, unsupported: Set<String> = emptySet()): List<String> {
        val p = mutableListOf<String>()
        if (r.actions.isEmpty()) p += "할 일을 하나 이상 정해 주세요."
        if (r.actions.size > Limits.MAX_ACTIONS) p += "할 일은 ${Limits.MAX_ACTIONS}개까지 넣을 수 있어요."
        if (r.conditions.size > Limits.MAX_CONDITIONS) p += "추가 조건은 ${Limits.MAX_CONDITIONS}개까지 넣을 수 있어요."

        val t0 = r.trigger
        if (t0 is Trigger.Gear && t0.gear != GearSel.P && r.actions.any { it.needsPark })
            p += "출발하거나 후진할 때는 실내등, 독서등, 문 잠금, 충전구를 쓸 수 없어요. 차가 주차 중일 때만 움직여요."
        when (val t = r.trigger) {
            is Trigger.BatteryBelow -> if (t.percent !in Limits.BATTERY_MIN..Limits.BATTERY_MAX)
                p += "배터리 기준은 ${Limits.BATTERY_MIN}~${Limits.BATTERY_MAX}% 사이로 정할 수 있어요."
            is Trigger.MinutesToArrival -> if (t.minutes !in 1..Limits.ARRIVAL_MAX)
                p += "도착 전 알림은 1~${Limits.ARRIVAL_MAX}분 사이로 정할 수 있어요."
            is Trigger.Time -> if (t.hour !in 0..23 || t.minute !in 0..59) p += "시각이 올바르지 않아요."
            else -> {}
        }

        for (c in r.conditions) when (c) {
            is Condition.TimeBefore -> if (c.hour !in 0..23 || c.minute !in 0..59) p += "시각이 올바르지 않아요."
            is Condition.TimeBetween -> if (c.fromHour !in 0..23 || c.toHour !in 0..23 || c.fromHour == c.toHour)
                p += "시간대가 올바르지 않아요."
            is Condition.BatteryBetween -> if (c.min !in 0..100 || c.max !in 0..100 || c.min >= c.max)
                p += "배터리 범위가 올바르지 않아요."
            else -> {}
        }

        for (a in r.actions) {
            when (a) {
                is Action.Temperature -> if (a.celsius !in Limits.TEMP_MIN..Limits.TEMP_MAX || (a.celsius * 2) % 1f != 0f)
                    p += "온도는 ${Limits.TEMP_MIN.toInt()}~${Limits.TEMP_MAX.toInt()}도 사이에서 0.5도 단위로 맞출 수 있어요."
                is Action.FanSpeed -> if (a.level !in Limits.FAN_MIN..Limits.FAN_MAX)
                    p += "바람 세기는 ${Limits.FAN_MIN}~${Limits.FAN_MAX}단 사이로 정할 수 있어요."
                is Action.SeatClimate -> if (a.level !in Limits.SEAT_MIN..Limits.SEAT_MAX)
                    p += "시트 열선과 통풍은 3단까지예요."
                is Action.SteeringHeat -> if (a.level !in 0..Limits.STEER_MAX) p += "핸들 열선은 3단까지예요."
                is Action.AmbientLight -> {
                    if (a.brightness !in 0..100) p += "무드등 밝기는 0~100% 사이로 정할 수 있어요."
                    if (a.color != null && !Regex("^#[0-9A-Fa-f]{6}$").matches(a.color)) p += "무드등 색을 알아보지 못했어요."
                }
                is Action.Navigate -> if (a.place == Place.charger) p += "충전소 안내는 '충전소를 경유지로 넣기'를 써 주세요."
                is Action.Speak -> if (a.text.isBlank() || a.text.length > Limits.TEXT_MAX) p += "말할 문장은 ${Limits.TEXT_MAX}자 안으로 써 주세요."
                is Action.Notify -> if (a.text.isBlank() || a.text.length > Limits.TEXT_MAX) p += "알림 문구는 ${Limits.TEXT_MAX}자 안으로 써 주세요."
                else -> {}
            }
            val type = typeName(a)
            if (type in unsupported) p += "이 차에서 지원하지 않는 동작이 있어요: ${Phrase.action(a).second}"
        }
        return p.distinct()
    }

    fun typeName(a: Action): String = RuleJson.encodeToJsonElement(Action.serializer(), a)
        .let { (it as kotlinx.serialization.json.JsonObject)["type"].toString().trim('"') }
}
