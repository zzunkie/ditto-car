package dev.zzunkie.whenever.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 규칙 하나 = 언제(시작 조건 1개) + 단(추가 조건 0~2개) + 이렇게(동작 1~4개).
// JSON 모양은 LLM 도구 스키마(Catalog.kt)와 같다. "type" 값으로 종류를 가른다.

@Serializable
data class RuleDraft(
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
)

@Serializable
data class Rule(
    val id: String,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
    val enabled: Boolean = true,
    val spoken: String = "",
    val createdAt: Long = 0,
) {
    val draft get() = RuleDraft(trigger, conditions, actions)
}

@Serializable
enum class DoorSel { any, driver, passenger, rear, trunk }

@Serializable
enum class GearSel { P, D, R }

@Serializable
enum class SeatSel { driver, passenger, front }

@Serializable
enum class Place { home, work, charger }

@Serializable
enum class WarningKind { any, tpms, seat_belt }

@Serializable
enum class WiperMode { auto, off }

@Serializable
sealed interface Trigger {
    @Serializable @SerialName("door")
    data class Door(val door: DoorSel = DoorSel.any, val open: Boolean = true) : Trigger

    @Serializable @SerialName("gear")
    data class Gear(val gear: GearSel) : Trigger

    @Serializable @SerialName("battery_below")
    data class BatteryBelow(val percent: Int) : Trigger

    @Serializable @SerialName("charge_port_opened")
    data object ChargePortOpened : Trigger

    @Serializable @SerialName("charging_started")
    data object ChargingStarted : Trigger

    @Serializable @SerialName("charging_stopped")
    data object ChargingStopped : Trigger

    @Serializable @SerialName("wiper_on")
    data object WiperOn : Trigger

    @Serializable @SerialName("window_opened")
    data object WindowOpened : Trigger

    @Serializable @SerialName("warning_light")
    data class WarningLight(val kind: WarningKind = WarningKind.any) : Trigger

    @Serializable @SerialName("route_started")
    data object RouteStarted : Trigger

    @Serializable @SerialName("arrived")
    data object Arrived : Trigger

    @Serializable @SerialName("minutes_to_arrival")
    data class MinutesToArrival(val minutes: Int) : Trigger

    @Serializable @SerialName("near_place")
    data class NearPlace(val place: Place, val enter: Boolean = true) : Trigger

    @Serializable @SerialName("time")
    data class Time(val hour: Int, val minute: Int = 0) : Trigger
}

@Serializable
sealed interface Condition {
    @Serializable @SerialName("gear_is")
    data class GearIs(val gear: GearSel) : Condition

    /** 이 시각 전 (자정부터 hour:minute 까지) */
    @Serializable @SerialName("time_before")
    data class TimeBefore(val hour: Int, val minute: Int = 0) : Condition

    @Serializable @SerialName("time_between")
    data class TimeBetween(val fromHour: Int, val toHour: Int) : Condition

    @Serializable @SerialName("battery_between")
    data class BatteryBetween(val min: Int, val max: Int) : Condition

    @Serializable @SerialName("wiper_is_on")
    data object WiperIsOn : Condition

    @Serializable @SerialName("window_is_open")
    data object WindowIsOpen : Condition

    @Serializable @SerialName("door_is_open")
    data class DoorIsOpen(val door: DoorSel = DoorSel.any) : Condition

    @Serializable @SerialName("navigating")
    data object Navigating : Condition

    /** 마지막으로 주차(P)한 뒤 이 문이 열렸다 닫힌 적이 있음 */
    @Serializable @SerialName("door_opened_since_park")
    data class DoorOpenedSincePark(val door: DoorSel = DoorSel.rear) : Condition
}

@Serializable
sealed interface Action {
    @Serializable @SerialName("hvac_power")
    data class HvacPower(val on: Boolean) : Action

    @Serializable @SerialName("temperature")
    data class Temperature(val celsius: Float) : Action

    @Serializable @SerialName("fan_speed")
    data class FanSpeed(val level: Int) : Action

    @Serializable @SerialName("defrost")
    data class Defrost(val on: Boolean) : Action

    /** level 1~3 은 열선, -1~-3 은 통풍, 0 은 끄기 */
    @Serializable @SerialName("seat_climate")
    data class SeatClimate(val seat: SeatSel = SeatSel.driver, val level: Int) : Action

    @Serializable @SerialName("steering_heat")
    data class SteeringHeat(val level: Int) : Action

    /** color 는 "#RRGGBB", 없으면 색은 그대로 둔다 */
    @Serializable @SerialName("ambient_light")
    data class AmbientLight(val brightness: Int, val color: String? = null) : Action

    @Serializable @SerialName("window_lock")
    data class WindowLock(val on: Boolean) : Action

    @Serializable @SerialName("wiper")
    data class Wiper(val mode: WiperMode) : Action

    @Serializable @SerialName("cabin_light")
    data class CabinLight(val on: Boolean) : Action

    @Serializable @SerialName("reading_light")
    data class ReadingLight(val on: Boolean) : Action

    /** 잠금만 둔다. 잠금 해제는 넣지 않는다. */
    @Serializable @SerialName("door_lock")
    data object DoorLock : Action

    @Serializable @SerialName("charge_port")
    data object ChargePort : Action

    @Serializable @SerialName("navigate")
    data class Navigate(val place: Place) : Action

    @Serializable @SerialName("charger_waypoint")
    data object ChargerWaypoint : Action

    @Serializable @SerialName("cancel_route")
    data object CancelRoute : Action

    @Serializable @SerialName("speak")
    data class Speak(val text: String) : Action

    @Serializable @SerialName("notify")
    data class Notify(val text: String) : Action
}

/** 차가 기어 P 일 때만 받아 주는 동작 (주행 중엔 50001 로 거부됨, spike/FINDINGS.md 8.2) */
val Action.needsPark: Boolean
    get() = this is Action.CabinLight || this is Action.ReadingLight ||
        this is Action.DoorLock || this is Action.ChargePort

val RuleJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
