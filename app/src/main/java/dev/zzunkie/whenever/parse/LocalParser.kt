package dev.zzunkie.whenever.parse

import dev.zzunkie.whenever.rule.Action
import dev.zzunkie.whenever.rule.Condition
import dev.zzunkie.whenever.rule.DoorSel
import dev.zzunkie.whenever.rule.GearSel
import dev.zzunkie.whenever.rule.Phrase
import dev.zzunkie.whenever.rule.Place
import dev.zzunkie.whenever.rule.RuleDraft
import dev.zzunkie.whenever.rule.SeatSel
import dev.zzunkie.whenever.rule.Trigger
import dev.zzunkie.whenever.rule.WarningKind
import dev.zzunkie.whenever.rule.WiperMode

sealed interface ParseResult {
    data class Ok(val draft: RuleDraft, val by: String) : ParseResult
    /** 이 차나 앱이 못 하는 요청. 운전자에게 그대로 보여 준다. */
    data class CannotDo(val message: String) : ParseResult
    /** 알아듣지 못함 (키 없이 앱 안 해석기만 쓸 때) */
    data class NotUnderstood(val message: String) : ParseResult
}

/**
 * API 키가 없거나 연결이 없을 때 쓰는 앱 안의 간단한 해석기.
 * 시연 문장과 둘러보기 예시, 그 변형 몇 가지를 처리한다. 넓은 표현은 LLM 경로가 맡는다.
 */
object LocalParser {
    private const val BY = "앱 안 해석기"

    fun parse(input: String): ParseResult {
        val s = input.replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?')
        unsupported(s)?.let { return ParseResult.CannotDo(it) }

        // 앞 절(언제·단)과 뒷 절(이렇게)을 첫 "~면" 에서 나눈다
        val cut = Regex("(면|전에)(\\s|,)").findAll(s).firstOrNull { m ->
            // "전에"는 "도착 10분 전에"처럼 시작 조건일 때만 자른다
            m.groupValues[1] == "면" || Regex("도착\\s*\\d+\\s*분\\s*전에$").containsMatchIn(s.substring(0, m.range.last))
        }
        var head = if (cut != null) s.substring(0, cut.range.first + cut.groupValues[1].length) else s
        val tail = if (cut != null) s.substring(cut.range.last + 1) else s

        val conditions = mutableListOf<Condition>()
        fun takeCond(re: Regex, make: (MatchResult) -> Condition) {
            re.find(head)?.let { conditions += make(it); head = head.removeRange(it.range) }
        }
        takeCond(Regex("((?:오전|아침|새벽|오후|저녁|밤|낮)\\s*)?(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분\\s*)?(?:이)?전(?:에)?")) {
            Condition.TimeBefore(hour24(it.groupValues[1], it.groupValues[2].toInt()), it.groupValues[3].toIntOrNull() ?: 0)
        }
        takeCond(Regex("((?:오전|아침|새벽|오후|저녁|밤|낮)\\s*)?(\\d{1,2})\\s*시\\s*부터\\s*((?:오전|아침|새벽|오후|저녁|밤|낮)\\s*)?(\\d{1,2})\\s*시\\s*(?:사이|까지)\\S*")) {
            Condition.TimeBetween(hour24(it.groupValues[1], it.groupValues[2].toInt()), hour24(it.groupValues[3], it.groupValues[4].toInt()))
        }
        takeCond(Regex("주차\\s*(중|해\\s*있)\\S*")) { Condition.GearIs(GearSel.P) }
        takeCond(Regex("(주행|운전)\\s*중\\S*")) { Condition.GearIs(GearSel.D) }
        takeCond(Regex("창문이?\\s*열려\\s*있\\S*")) { Condition.WindowIsOpen }
        takeCond(Regex("와이퍼가?\\s*켜져\\s*있\\S*")) { Condition.WiperIsOn }
        takeCond(Regex("(길\\s*)?안내\\s*중\\S*")) { Condition.Navigating }
        takeCond(Regex("(뒷문|뒷좌석\\s*문)\\S*\\s*열(었|렸)다\\s*닫\\S*(\\s*(뒤|다음|후)에?)?|아이를?\\s*태우고")) { Condition.DoorOpenedSincePark(DoorSel.rear) }

        val trigger = trigger(head.trim()) ?: return ParseResult.NotUnderstood(
            "언제 실행할지 알아듣지 못했어요. 예를 들어 \"충전을 시작하면\"처럼 말해 주세요."
        )
        val acts = actions(tail, trigger, conditions)
        acts.cannot?.let { return ParseResult.CannotDo(it) }
        if (acts.list.isEmpty()) return ParseResult.NotUnderstood("무엇을 할지 알아듣지 못했어요. 예를 들어 \"실내등을 켜 줘\"처럼 말해 주세요.")
        return ParseResult.Ok(RuleDraft(trigger, conditions.take(2), acts.list.take(4)), BY)
    }

    private fun unsupported(s: String): String? = when {
        Regex("속도|km|킬로|시속").containsMatchIn(s) -> "이 앱은 차 속도를 읽을 수 없어서 속도로는 조건을 만들 수 없어요."
        Regex("안전벨트를?\\s*(매|채우|풀)|(사람|누가|아이가?)\\s*(타|앉)").containsMatchIn(s) -> "누가 탔는지나 안전벨트를 맸는지는 아직 알 수 없어요. 문이 열렸다 닫힌 것으로는 정할 수 있어요."
        Regex("음악|노래|라디오|볼륨").containsMatchIn(s) -> "음악과 소리 크기는 이 앱에서 다룰 수 없어요."
        Regex("시트\\S*\\s*(앞으로|뒤로|눕|세워|등받이)|등받이").containsMatchIn(s) -> "시트 위치는 아직 바꿀 수 없어요. 열선과 통풍은 할 수 있어요."
        Regex("충전\\s*한도").containsMatchIn(s) -> "충전 한도는 차에 반영되는지 확인되지 않아 아직 넣지 않았어요."
        Regex("창문\\S*\\s*(닫|올려|올리|내려|내리)|창문을?\\s*(조금\\s*)?열어").containsMatchIn(s) ->
            "창문을 자동으로 열고 닫는 건 끼일 수 있어서 넣지 않았어요. 창문이 열려 있으면 알려 드릴 수는 있어요."
        Regex("문\\S*\\s*(잠금\\s*)?(을\\s*)?(풀어|해제|열어\\s*(줘|주))").containsMatchIn(s) && !s.contains("충전구") && !s.contains("열리면") && !s.contains("열면") ->
            "문 잠금을 푸는 동작은 넣지 않았어요. 잠그는 것만 할 수 있어요."
        Regex("트렁크\\S*\\s*열어").containsMatchIn(s) -> "트렁크를 여는 동작은 아직 없어요."
        else -> null
    }

    private fun hour24(part: String, h: Int): Int {
        val p = part.trim()
        return when {
            p in listOf("오후", "저녁", "밤") && h in 1..11 -> h + 12
            p == "밤" && h == 12 -> 0
            p in listOf("오전", "아침", "새벽") && h == 12 -> 0
            else -> h % 24
        }
    }

    private fun trigger(h: String): Trigger? {
        fun has(re: String) = Regex(re).containsMatchIn(h)
        Regex("배터리\\S*\\s*(\\d{1,2})\\s*(%|퍼센트|프로)").find(h)?.let {
            if (has("아래|밑|이하|미만|떨어지|내려가|안\\s*되")) return Trigger.BatteryBelow(it.groupValues[1].toInt())
        }
        if (has("충전\\S*\\s*(을\\s*)?시작|충전\\s*(을\\s*)?하면|충전하면")) return Trigger.ChargingStarted
        if (has("충전\\S*\\s*(끝나|끝내|다\\s*되|완료|멈추)")) return Trigger.ChargingStopped
        if (has("충전구\\S*\\s*열")) return Trigger.ChargePortOpened
        Regex("도착\\s*(\\d{1,2})\\s*분\\s*전").find(h)?.let { return Trigger.MinutesToArrival(it.groupValues[1].toInt()) }
        if (has("충전소\\S*\\s*(근처|가까이|도착|오면|들어서)")) return Trigger.NearPlace(Place.charger, true)
        if (has("충전소\\S*\\s*(멀어지|떠나|벗어나)")) return Trigger.NearPlace(Place.charger, false)
        for ((word, place) in listOf("집" to Place.home, "회사" to Place.work)) {
            if (has("${word}\\S*\\s*(근처|가까이|도착|에\\s*오면|오면)")) return Trigger.NearPlace(place, true)
            if (has("${word}\\S*\\s*(멀어지|떠나|나서|벗어나)")) return Trigger.NearPlace(place, false)
        }
        if (has("목적지\\S*\\s*도착|도착하면")) return Trigger.Arrived
        if (has("(길\\s*)?안내\\S*\\s*시작")) return Trigger.RouteStarted
        if (has("공기압")) return Trigger.WarningLight(WarningKind.tpms)
        if (has("안전벨트\\s*경고")) return Trigger.WarningLight(WarningKind.seat_belt)
        if (has("경고등")) return Trigger.WarningLight(WarningKind.any)
        if (has("비가?\\s*(오|내리)|와이퍼\\S*\\s*켜")) return Trigger.WiperOn
        if (has("창문\\S*\\s*열리")) return Trigger.WindowOpened
        Regex("(뒷문|뒷좌석\\s*문|뒷자리\\s*문|운전석\\s*문|조수석\\s*문|트렁크|문)\\s*(이|가|을|를)?\\s*(열|닫)").find(h)?.let {
            val w = it.groupValues[1].replace(" ", "")
            val door = when {
                w.startsWith("뒷") -> DoorSel.rear
                w.startsWith("운전석") -> DoorSel.driver
                w.startsWith("조수석") -> DoorSel.passenger
                w == "트렁크" -> DoorSel.trunk
                else -> DoorSel.any
            }
            return Trigger.Door(door, it.groupValues[3] == "열")
        }
        if (has("주차\\s*(하|할|해)|파킹|P로|피로\\s*바꾸")) return Trigger.Gear(GearSel.P)
        if (has("출발|D로|디로\\s*바꾸|운전을\\s*시작")) return Trigger.Gear(GearSel.D)
        if (has("후진")) return Trigger.Gear(GearSel.R)
        Regex("((?:오전|아침|새벽|오후|저녁|밤|낮)\\s*)?(\\d{1,2})\\s*시\\s*(?:(\\d{1,2})\\s*분)?\\S*\\s*(되면|넘으면|넘어가면|면)").find(h)?.let {
            return Trigger.Time(hour24(it.groupValues[1], it.groupValues[2].toInt()), it.groupValues[3].toIntOrNull() ?: 0)
        }
        return null
    }

    private class Acts(val list: List<Action>, val cannot: String?)

    private fun actions(t: String, trigger: Trigger, conds: List<Condition>): Acts {
        val found = mutableListOf<Pair<Int, Action>>()
        var text = t
        fun off(seg: String) = Regex("끄|꺼|끈|해제|풀").containsMatchIn(seg)
        fun level(seg: String, def: Int) = Regex("(\\d)\\s*단").find(seg)?.groupValues?.get(1)?.toInt() ?: def
        fun take(re: Regex, make: (MatchResult) -> Action?) {
            re.findAll(text).toList().forEach { m ->
                make(m)?.let { found += m.range.first to it }
                // 같은 글자를 두 번 쓰지 않도록 가린다
                text = text.replaceRange(m.range, " ".repeat(m.value.length))
            }
        }
        // 따옴표 안의 말은 먼저 떼어 둔다
        take(Regex("[\"“'‘](.+?)[\"”'’]\\s*(이?라고)?\\s*(말|알려|얘기|안내)\\S*(\\s*(줘|주세요|주고))?")) { Action.Speak(it.groupValues[1].trim()) }
        take(Regex("(핸들|스티어링(\\s*휠)?)\\s*열선\\S*(\\s*(\\d)\\s*단\\S*)?(\\s*(으로|로))?\\s*(켜|끄|꺼|올려|해)?\\S*")) {
            Action.SteeringHeat(if (off(it.value)) 0 else level(it.value, 2))
        }
        take(Regex("(운전석|조수석|앞좌석|앞자리|시트)?\\s*(열선|통풍)\\S*(\\s*(\\d)\\s*단\\S*)?(\\s*(으로|로))?\\s*(켜|끄|꺼|해|틀어)?\\S*")) {
            val seat = when {
                it.value.contains("조수석") -> SeatSel.passenger
                it.value.contains("앞좌석") || it.value.contains("앞자리") -> SeatSel.front
                else -> SeatSel.driver
            }
            val l = level(it.value, 2)
            Action.SeatClimate(seat, if (off(it.value)) 0 else if (it.value.contains("통풍")) -l else l)
        }
        take(Regex("실내등\\S*\\s*(켜|끄|꺼)\\S*")) { Action.CabinLight(!off(it.value)) }
        take(Regex("독서등\\S*\\s*(켜|끄|꺼)\\S*")) { Action.ReadingLight(!off(it.value)) }
        take(Regex("(온도|실내\\s*온도)\\S*\\s*(\\d{2}(\\.5)?)\\s*도\\S*(\\s*(맞춰|맞추|해|올려|내려)\\S*)?")) { Action.Temperature(it.groupValues[2].toFloat()) }
        take(Regex("(에어컨|히터|냉난방|공조)\\S*\\s*(켜|끄|꺼|틀어)\\S*")) { Action.HvacPower(!off(it.value)) }
        take(Regex("(바람|풍량)\\S*\\s*(\\d)\\s*단\\S*(\\s*(으로|로))?(\\s*(해|맞춰|올려|내려)\\S*)?")) { Action.FanSpeed(it.groupValues[2].toInt()) }
        take(Regex("(앞\\s*유리\\s*)?(서리|김\\s*서림|성에)\\S*(\\s*제거\\S*)?(\\s*(켜|끄|꺼|없애|해)\\S*)?")) { Action.Defrost(!off(it.value)) }
        take(Regex("(무드등|앰비언트(\\s*라이트)?|실내\\s*조명)\\S*(\\s*\\S+){0,3}?\\s*(어둡게|밝게|켜|끄|꺼|\\d+\\s*%)\\S*")) {
            val v = it.value
            val pct = Regex("(\\d+)\\s*%").find(v)?.groupValues?.get(1)?.toInt()
            val b = when {
                pct != null -> pct
                v.contains("어둡") -> 20
                v.contains("밝게") -> 80
                off(v) -> 0
                else -> 60
            }
            val color = Phrase.colorByName.entries.firstOrNull { (name, _) -> v.contains(name) }?.value
            Action.AmbientLight(b.coerceIn(0, 100), color)
        }
        take(Regex("창문\\S*\\s*잠(금|가|그)\\S*(\\s*(켜|해|풀|해제)\\S*)?")) { Action.WindowLock(!off(it.value)) }
        take(Regex("와이퍼\\S*\\s*(켜|끄|꺼|자동)\\S*")) { Action.Wiper(if (off(it.value)) WiperMode.off else WiperMode.auto) }
        take(Regex("충전구\\S*\\s*열\\S*")) { Action.ChargePort }
        take(Regex("문\\S*\\s*(을\\s*)?잠(가|그|금)\\S*")) { Action.DoorLock }
        take(Regex("충전소\\S*(\\s*\\S+){0,3}?\\s*(경유|들러|들르|넣어|안내)\\S*(\\s*(줘|주세요))?")) { Action.ChargerWaypoint }
        take(Regex("(집|회사)\\s*(으로|로)\\S*\\s*(길\\s*)?안내\\S*")) { Action.Navigate(if (it.groupValues[1] == "집") Place.home else Place.work) }
        take(Regex("(길\\s*)?안내\\S*\\s*(끝|취소|멈|꺼|그만)\\S*")) { Action.CancelRoute }
        take(Regex("알림\\S*\\s*(띄|보여|줘)\\S*")) { Action.Notify(defaultMessage(trigger, conds)) }
        take(Regex("(말로\\s*)?(알려|말해|얘기해)\\S*(\\s*(줘|주세요))?")) { Action.Speak(defaultMessage(trigger, conds)) }

        return Acts(found.sortedBy { it.first }.map { it.second }.distinct(), null)
    }

    fun defaultMessage(t: Trigger, conds: List<Condition>): String = when {
        Condition.WindowIsOpen in conds -> "창문이 열려 있어요"
        t is Trigger.ChargingStopped -> "충전이 끝났어요"
        t is Trigger.ChargingStarted -> "충전을 시작했어요"
        t is Trigger.BatteryBelow -> "배터리가 ${t.percent}% 아래로 내려갔어요"
        t is Trigger.Door -> "${Phrase.doorName(t.door)}${if (t.door == DoorSel.trunk) "가" else "이"} ${if (t.open) "열렸어요" else "닫혔어요"}"
        t is Trigger.WindowOpened -> "창문이 열렸어요"
        t is Trigger.Arrived -> "목적지에 도착했어요"
        t is Trigger.MinutesToArrival -> "도착 ${t.minutes}분 전이에요"
        t is Trigger.WarningLight -> "경고등이 켜졌어요. 계기판을 확인해 주세요"
        t is Trigger.WiperOn -> "비가 와요"
        else -> "자동화를 실행했어요"
    }
}
