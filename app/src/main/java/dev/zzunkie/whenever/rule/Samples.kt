package dev.zzunkie.whenever.rule

// 둘러보기 화면의 예시 묶음. 고르면 확인 화면에서 값을 고친 뒤 저장한다.

data class Bundle(val title: String, val summary: String, val rules: List<RuleDraft>)

object Samples {
    val bundles = listOf(
        Bundle(
            "추운 아침 출발", "이른 아침에 출발하면 앞유리와 핸들, 시트를 데웁니다.",
            listOf(
                RuleDraft(
                    Trigger.Gear(GearSel.D), listOf(Condition.TimeBefore(7)),
                    listOf(Action.Defrost(true), Action.SteeringHeat(2), Action.SeatClimate(SeatSel.driver, 2)),
                )
            ),
        ),
        Bundle(
            "배터리가 모자랄 때", "배터리가 20% 아래로 내려가면 가는 길의 충전소를 들르게 합니다.",
            listOf(
                RuleDraft(
                    Trigger.BatteryBelow(20), emptyList(),
                    listOf(Action.ChargerWaypoint, Action.Speak("배터리가 부족해서 가는 길에 있는 충전소를 경유지로 넣었어요")),
                )
            ),
        ),
        Bundle(
            "충전하며 쉴 때", "충전하는 동안 실내를 어둡고 따뜻하게 하고, 끝나면 알려 줍니다.",
            listOf(
                RuleDraft(
                    Trigger.ChargingStarted, emptyList(),
                    listOf(Action.AmbientLight(20), Action.SeatClimate(SeatSel.driver, 2), Action.Speak("충전이 끝나면 알려 드릴게요")),
                ),
                RuleDraft(
                    Trigger.ChargingStopped, emptyList(),
                    listOf(Action.AmbientLight(70), Action.SeatClimate(SeatSel.driver, 0), Action.Speak("충전이 끝났어요")),
                ),
            ),
        ),
        Bundle(
            "아이 태우고 출발할 때", "뒷문을 열었다 닫은 뒤 출발하면 창문 잠금을 켜고 온도를 맞춥니다.",
            listOf(
                RuleDraft(
                    Trigger.Gear(GearSel.D), listOf(Condition.DoorOpenedSincePark(DoorSel.rear)),
                    listOf(Action.WindowLock(true), Action.Temperature(22f)),
                )
            ),
        ),
        Bundle(
            "주차할 때 창문 확인", "주차했는데 창문이 열려 있으면 말로 알려 줍니다.",
            listOf(
                RuleDraft(
                    Trigger.Gear(GearSel.P), listOf(Condition.WindowIsOpen),
                    listOf(Action.Speak("창문이 열려 있어요. 닫고 내리세요")),
                )
            ),
        ),
        Bundle(
            "비 올 때", "와이퍼가 켜지면 앞유리 서리 제거를 켜고 바람을 세게 합니다.",
            listOf(
                RuleDraft(
                    Trigger.WiperOn, emptyList(),
                    listOf(Action.Defrost(true), Action.FanSpeed(4)),
                )
            ),
        ),
    )
}
