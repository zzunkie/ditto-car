package dev.zzunkie.whenever.rule

// 규칙 JSON 을 사람이 말하듯 읽히는 한국어 문장으로 바꾼다.
// 확인 화면과 목록의 문장은 모두 여기서 만든다(LLM 이 쓴 문장을 그대로 보여 주지 않는다).

object Phrase {

    fun sentence(r: RuleDraft): String {
        val prefix = conditionsClause(r.conditions)
        val acts = r.actions
        val body = acts.mapIndexed { i, a -> if (i == acts.lastIndex) action(a).second else action(a).first }
        return listOfNotNull(prefix, trigger(r.trigger), body.joinToString(" ")).joinToString(" ") + "."
    }

    fun sentence(r: Rule) = sentence(r.draft)

    /** 확인 화면 "단" 줄: 조건이 여럿이면 이어서 하나로 */
    fun conditionsClause(cs: List<Condition>): String? = when (cs.size) {
        0 -> null
        else -> cs.mapIndexed { i, c -> if (i == cs.lastIndex) condition(c).first else condition(c).second }.joinToString(" ")
    }

    fun trigger(t: Trigger): String = when (t) {
        is Trigger.Door -> "${doorName(t.door).josa("이", "가")} ${if (t.open) "열리면" else "닫히면"}"
        is Trigger.Gear -> when (t.gear) {
            GearSel.P -> "주차하면"
            GearSel.D -> "출발하면"
            GearSel.R -> "후진 기어를 넣으면"
        }
        is Trigger.BatteryBelow -> "배터리가 ${t.percent}% 아래로 내려가면"
        Trigger.ChargePortOpened -> "충전구가 열리면"
        Trigger.ChargingStarted -> "충전을 시작하면"
        Trigger.ChargingStopped -> "충전이 끝나면"
        Trigger.WiperOn -> "와이퍼가 켜지면"
        Trigger.WindowOpened -> "창문이 열리면"
        is Trigger.WarningLight -> when (t.kind) {
            WarningKind.tpms -> "타이어 공기압 경고가 뜨면"
            WarningKind.seat_belt -> "안전벨트 경고가 뜨면"
            WarningKind.any -> "경고등이 켜지면"
        }
        Trigger.RouteStarted -> "길 안내를 시작하면"
        Trigger.Arrived -> "목적지에 도착하면"
        is Trigger.MinutesToArrival -> "목적지 도착 ${t.minutes}분 전이 되면"
        is Trigger.NearPlace -> if (t.enter) "${placeName(t.place)} 근처에 오면" else "${placeName(t.place)}에서 멀어지면"
        is Trigger.Time -> "매일 ${clock(t.hour, t.minute)}가 되면"
    }

    /** (때 꼴, 그리고 꼴) */
    fun condition(c: Condition): Pair<String, String> = when (c) {
        is Condition.GearIs -> when (c.gear) {
            GearSel.P -> "주차 중일 때" to "주차 중이고"
            GearSel.D -> "주행 중일 때" to "주행 중이고"
            GearSel.R -> "후진 중일 때" to "후진 중이고"
        }
        is Condition.TimeBefore -> "${clock(c.hour, c.minute)} 전에" to "${clock(c.hour, c.minute)} 전이고"
        is Condition.TimeBetween -> "${clock(c.fromHour, 0)}부터 ${clock(c.toHour, 0)} 사이에" to "${clock(c.fromHour, 0)}부터 ${clock(c.toHour, 0)} 사이이고"
        is Condition.BatteryBetween -> "배터리가 ${c.min}~${c.max}%일 때" to "배터리가 ${c.min}~${c.max}%이고"
        Condition.WiperIsOn -> "와이퍼가 켜져 있을 때" to "와이퍼가 켜져 있고"
        Condition.WindowIsOpen -> "창문이 열려 있을 때" to "창문이 열려 있고"
        is Condition.DoorIsOpen -> "${doorName(c.door).josa("이", "가")} 열려 있을 때" to "${doorName(c.door).josa("이", "가")} 열려 있고"
        Condition.Navigating -> "길 안내 중일 때" to "길 안내 중이고"
        is Condition.DoorOpenedSincePark -> "${doorName(c.door).josa("을", "를")} 열었다 닫은 뒤" to "${doorName(c.door).josa("을", "를")} 열었다 닫았고"
    }

    /** (이어지는 꼴 "~하고", 끝맺는 꼴 "~합니다") */
    fun action(a: Action): Pair<String, String> = when (a) {
        is Action.HvacPower -> if (a.on) "냉난방을 켜고" to "냉난방을 켭니다" else "냉난방을 끄고" to "냉난방을 끕니다"
        is Action.Temperature -> "온도를 ${num(a.celsius)}도로 맞추고" to "온도를 ${num(a.celsius)}도로 맞춥니다"
        is Action.FanSpeed -> "바람 세기를 ${a.level}단으로 하고" to "바람 세기를 ${a.level}단으로 합니다"
        is Action.Defrost -> if (a.on) "앞유리 서리 제거를 켜고" to "앞유리 서리 제거를 켭니다" else "앞유리 서리 제거를 끄고" to "앞유리 서리 제거를 끕니다"
        is Action.SeatClimate -> {
            val s = seatName(a.seat)
            when {
                a.level > 0 -> "$s 열선을 ${a.level}단으로 켜고" to "$s 열선을 ${a.level}단으로 켭니다"
                a.level < 0 -> "$s 통풍을 ${-a.level}단으로 켜고" to "$s 통풍을 ${-a.level}단으로 켭니다"
                else -> "$s 열선과 통풍을 끄고" to "$s 열선과 통풍을 끕니다"
            }
        }
        is Action.SteeringHeat -> if (a.level > 0) "핸들 열선을 ${a.level}단으로 켜고" to "핸들 열선을 ${a.level}단으로 켭니다"
            else "핸들 열선을 끄고" to "핸들 열선을 끕니다"
        is Action.AmbientLight -> {
            val c = a.color?.let { colorName(it) }
            val what = if (c != null) "무드등을 $c, 밝기 ${a.brightness}%로" else "무드등 밝기를 ${a.brightness}%로"
            "$what 맞추고" to "$what 맞춥니다"
        }
        is Action.WindowLock -> if (a.on) "창문 잠금을 켜고" to "창문 잠금을 켭니다" else "창문 잠금을 풀고" to "창문 잠금을 풉니다"
        is Action.Wiper -> if (a.mode == WiperMode.auto) "와이퍼를 자동으로 켜고" to "와이퍼를 자동으로 켭니다" else "와이퍼를 끄고" to "와이퍼를 끕니다"
        is Action.CabinLight -> if (a.on) "실내등을 켜고" to "실내등을 켭니다" else "실내등을 끄고" to "실내등을 끕니다"
        is Action.ReadingLight -> if (a.on) "독서등을 켜고" to "독서등을 켭니다" else "독서등을 끄고" to "독서등을 끕니다"
        Action.DoorLock -> "문을 잠그고" to "문을 잠급니다"
        Action.ChargePort -> "충전구를 열고" to "충전구를 엽니다"
        is Action.Navigate -> "${placeName(a.place).josa("으로", "로")} 길 안내를 시작하고" to "${placeName(a.place).josa("으로", "로")} 길 안내를 시작합니다"
        Action.ChargerWaypoint -> "가는 길에 있는 충전소를 경유지로 넣고" to "가는 길에 있는 충전소를 경유지로 넣습니다"
        Action.CancelRoute -> "길 안내를 끝내고" to "길 안내를 끝냅니다"
        is Action.Speak -> "\"${a.text}\"라고 말하고" to "\"${a.text}\"라고 말합니다"
        is Action.Notify -> "화면에 \"${a.text}\" 알림을 띄우고" to "화면에 \"${a.text}\" 알림을 띄웁니다"
    }

    fun doorName(d: DoorSel) = when (d) {
        DoorSel.any -> "문"
        DoorSel.driver -> "운전석 문"
        DoorSel.passenger -> "조수석 문"
        DoorSel.rear -> "뒷문"
        DoorSel.trunk -> "트렁크"
    }

    fun seatName(s: SeatSel) = when (s) {
        SeatSel.driver -> "운전석"
        SeatSel.passenger -> "조수석"
        SeatSel.front -> "앞좌석"
    }

    fun placeName(p: Place) = when (p) {
        Place.home -> "집"
        Place.work -> "회사"
        Place.charger -> "충전소"
    }

    fun clock(hour: Int, minute: Int): String {
        val h = ((hour % 24) + 24) % 24
        val (part, h12) = when (h) {
            0 -> "밤" to 12
            in 1..5 -> "새벽" to h
            in 6..11 -> "아침" to h
            12 -> "낮" to 12
            in 13..17 -> "오후" to h - 12
            in 18..20 -> "저녁" to h - 12
            else -> "밤" to h - 12
        }
        return if (minute == 0) "$part ${h12}시" else "$part ${h12}시 ${minute}분"
    }

    fun num(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()

    private val colors = mapOf(
        "#FF0000" to "빨간색", "#FF8800" to "주황색", "#FFD000" to "노란색", "#00C853" to "초록색",
        "#0066FF" to "파란색", "#AA00FF" to "보라색", "#FFFFFF" to "흰색", "#FFB070" to "따뜻한 색",
    )

    fun colorName(hex: String) = colors[hex.uppercase()] ?: "고른 색($hex)"

    val colorByName = colors.entries.associate { (k, v) -> v.removeSuffix("색") to k }
}

/** 받침에 따라 조사를 붙인다. ㄹ 받침 + 으로/로 는 "로". 숫자는 읽는 소리로 판단. */
fun String.josa(withBatchim: String, without: String): String {
    val last = trimEnd().lastOrNull() ?: return this + without
    val (has, rieul) = when {
        last in '가'..'힣' -> {
            val jong = (last - '가') % 28
            (jong != 0) to (jong == 8)
        }
        last.isDigit() -> when (last) {
            '0', '3', '6' -> true to false
            '1', '7', '8' -> true to true
            else -> false to false
        }
        else -> false to false
    }
    val useWith = has && !(rieul && withBatchim == "으로")
    return this + if (useWith) withBatchim else without
}
