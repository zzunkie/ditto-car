package dev.zzunkie.whenever.rule

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// 이 차에서 쓸 수 있는 조건과 동작 목록. 값 범위는 검증(Validator)과 LLM 도구 스키마가 함께 쓴다.

sealed interface Param {
    val name: String
    val desc: String

    data class IntP(override val name: String, val min: Int, val max: Int, override val desc: String) : Param
    data class FloatP(override val name: String, val min: Float, val max: Float, override val desc: String) : Param
    data class BoolP(override val name: String, override val desc: String) : Param
    data class EnumP(override val name: String, val values: List<String>, override val desc: String) : Param
    data class TextP(override val name: String, val maxLen: Int, override val desc: String) : Param
}

data class Spec(val type: String, val desc: String, val params: List<Param> = emptyList(), val optional: Set<String> = emptySet())

object Limits {
    const val TEMP_MIN = 16f
    const val TEMP_MAX = 30f
    const val FAN_MIN = 1
    const val FAN_MAX = 8
    const val SEAT_MIN = -3
    const val SEAT_MAX = 3
    const val STEER_MAX = 3
    const val BATTERY_MIN = 5
    const val BATTERY_MAX = 90
    const val ARRIVAL_MAX = 60
    const val TEXT_MAX = 60
    const val MAX_CONDITIONS = 2
    const val MAX_ACTIONS = 4
}

object Catalog {
    private val doors = DoorSel.entries.map { it.name }
    private val gears = GearSel.entries.map { it.name }

    val triggers = listOf(
        Spec("door", "A door opens (open=true) or closes (open=false). door: any, driver, passenger, rear (back seat doors), trunk.",
            listOf(Param.EnumP("door", doors, "which door"), Param.BoolP("open", "true when it opens, false when it closes"))),
        Spec("gear", "The gear is changed to P (parked), D (start driving) or R (reverse).",
            listOf(Param.EnumP("gear", gears, "gear"))),
        Spec("battery_below", "EV battery charge falls below percent.",
            listOf(Param.IntP("percent", Limits.BATTERY_MIN, Limits.BATTERY_MAX, "percent"))),
        Spec("charge_port_opened", "The charge port door opens."),
        Spec("charging_started", "Charging starts."),
        Spec("charging_stopped", "Charging ends: the battery is full or charging stopped (충전이 끝나면, 다 되면)."),
        Spec("wiper_on", "Wipers turn on (use this for 'when it rains')."),
        Spec("window_opened", "Any window opens."),
        Spec("warning_light", "A warning light comes on. kind: tpms (tire pressure), seat_belt, any.",
            listOf(Param.EnumP("kind", WarningKind.entries.map { it.name }, "warning kind"))),
        Spec("route_started", "Navigation guidance starts."),
        Spec("arrived", "The car arrives at the navigation destination."),
        Spec("minutes_to_arrival", "N minutes before arriving at the navigation destination.",
            listOf(Param.IntP("minutes", 1, Limits.ARRIVAL_MAX, "minutes left"))),
        Spec("near_place", "The car comes near (enter=true) or leaves (enter=false) a saved place: home (the driver's home, 집), work (the driver's workplace, 회사/직장), or charger (any charging station).",
            listOf(Param.EnumP("place", Place.entries.map { it.name }, "place"), Param.BoolP("enter", "true when arriving near, false when leaving"))),
        Spec("time", "Every day at this clock time (24h).",
            listOf(Param.IntP("hour", 0, 23, "hour 0-23"), Param.IntP("minute", 0, 59, "minute"))),
    )

    val conditions = listOf(
        Spec("gear_is", "Only while the gear is P, D or R.", listOf(Param.EnumP("gear", gears, "gear"))),
        Spec("time_before", "Only before this clock time (from midnight). 'before 7 in the morning' = hour 7.",
            listOf(Param.IntP("hour", 0, 23, "hour 0-23"), Param.IntP("minute", 0, 59, "minute"))),
        Spec("time_between", "Only between fromHour and toHour (24h, may wrap past midnight).",
            listOf(Param.IntP("fromHour", 0, 23, "start hour"), Param.IntP("toHour", 0, 23, "end hour"))),
        Spec("battery_between", "Only while battery percent is within min..max.",
            listOf(Param.IntP("min", 0, 100, "min percent"), Param.IntP("max", 0, 100, "max percent"))),
        Spec("wiper_is_on", "Only while wipers are on."),
        Spec("window_is_open", "Only while any window is open."),
        Spec("door_is_open", "Only while this door is open.", listOf(Param.EnumP("door", doors, "door"))),
        Spec("navigating", "Only while navigation guidance is active."),
        Spec("door_opened_since_park", "Only if this door was opened and closed since the car was last parked (e.g. a child got in the back).",
            listOf(Param.EnumP("door", doors, "door"))),
    )

    val actions = listOf(
        Spec("hvac_power", "Turn climate control on or off.", listOf(Param.BoolP("on", "on"))),
        Spec("temperature", "Set cabin temperature in Celsius (0.5 steps).",
            listOf(Param.FloatP("celsius", Limits.TEMP_MIN, Limits.TEMP_MAX, "celsius"))),
        Spec("fan_speed", "Set fan speed.", listOf(Param.IntP("level", Limits.FAN_MIN, Limits.FAN_MAX, "fan level"))),
        Spec("defrost", "Front windshield defroster on or off.", listOf(Param.BoolP("on", "on"))),
        Spec("seat_climate", "Seat heating (level 1..3) or ventilation (level -1..-3), 0 turns it off. seat: driver, passenger, front (both).",
            listOf(Param.EnumP("seat", SeatSel.entries.map { it.name }, "seat"), Param.IntP("level", Limits.SEAT_MIN, Limits.SEAT_MAX, "level"))),
        Spec("steering_heat", "Steering wheel heating level, 0 is off.", listOf(Param.IntP("level", 0, Limits.STEER_MAX, "level"))),
        Spec("ambient_light", "Ambient mood light brightness 0..100 and optional color as #RRGGBB.",
            listOf(Param.IntP("brightness", 0, 100, "brightness percent"), Param.TextP("color", 7, "#RRGGBB")), optional = setOf("color")),
        Spec("window_lock", "Window switch lock on or off (stops passengers from opening windows). Windows cannot be opened or closed by this app.",
            listOf(Param.BoolP("on", "on"))),
        Spec("wiper", "Wipers to auto or off.", listOf(Param.EnumP("mode", WiperMode.entries.map { it.name }, "mode"))),
        Spec("cabin_light", "Cabin (dome) light on or off. Needs the car parked; if the car is moving, the app waits and runs it after parking, so no gear condition is needed.", listOf(Param.BoolP("on", "on"))),
        Spec("reading_light", "Reading lights on or off. Needs the car parked; if the car is moving, the app waits and runs it after parking, so no gear condition is needed.", listOf(Param.BoolP("on", "on"))),
        Spec("door_lock", "Lock all doors. Needs the car parked; if the car is moving, the app waits and runs it after parking, so no gear condition is needed. Unlocking is not available."),
        Spec("charge_port", "Open the charge port door. Needs the car parked; if the car is moving, the app waits and runs it after parking, so no gear condition is needed."),
        Spec("navigate", "Start navigation to a saved place: home (집) or work (회사/직장).",
            listOf(Param.EnumP("place", listOf("home", "work"), "place"))),
        Spec("charger_waypoint", "Add a charging station along the current route as a waypoint (or route to the nearest one if no route)."),
        Spec("cancel_route", "Stop navigation guidance."),
        Spec("speak", "Say a short sentence out loud in Korean.", listOf(Param.TextP("text", Limits.TEXT_MAX, "Korean sentence to say"))),
        Spec("notify", "Show a short notification on screen in Korean.", listOf(Param.TextP("text", Limits.TEXT_MAX, "Korean text"))),
    )

    private fun paramSchema(p: Param): JsonObject = buildJsonObject {
        when (p) {
            is Param.IntP -> { put("type", "integer"); put("minimum", p.min); put("maximum", p.max) }
            is Param.FloatP -> { put("type", "number"); put("minimum", p.min); put("maximum", p.max) }
            is Param.BoolP -> put("type", "boolean")
            is Param.EnumP -> { put("type", "string"); putJsonArray("enum") { p.values.forEach { add(it) } } }
            is Param.TextP -> { put("type", "string"); put("maxLength", p.maxLen) }
        }
        put("description", p.desc)
    }

    private fun variant(s: Spec): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", s.desc)
        putJsonObject("properties") {
            putJsonObject("type") { put("const", s.type) }
            s.params.forEach { put(it.name, paramSchema(it)) }
        }
        putJsonArray("required") {
            add("type")
            s.params.filter { it.name !in s.optional }.forEach { add(it.name) }
        }
        put("additionalProperties", false)
    }

    private fun anyOf(specs: List<Spec>) = buildJsonObject { put("anyOf", JsonArray(specs.map(::variant))) }

    /** LLM 에 주는 도구 두 개: 규칙 만들기, 못 하는 요청 알리기. 차를 직접 움직이는 도구는 없다. */
    fun tools(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", "make_rule")
            put("description", "Turn the driver's request into one automation rule for this car. Use only the trigger, condition and action types listed in the schema.")
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    put("trigger", anyOf(triggers))
                    putJsonObject("conditions") {
                        put("type", "array"); put("maxItems", Limits.MAX_CONDITIONS)
                        put("items", anyOf(conditions))
                    }
                    putJsonObject("actions") {
                        put("type", "array"); put("minItems", 1); put("maxItems", Limits.MAX_ACTIONS)
                        put("items", anyOf(actions))
                    }
                }
                putJsonArray("required") { add("trigger"); add("conditions"); add("actions") }
                put("additionalProperties", false)
            }
        })
        add(buildJsonObject {
            put("name", "cannot_do")
            put("description", "Use when the request needs something this car or app cannot do (not in the make_rule schema), or is not an automation request.")
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "One short, plain Korean sentence (해요체) telling the driver what cannot be done and, if possible, what they could ask instead.")
                    }
                }
                putJsonArray("required") { add("message") }
                put("additionalProperties", false)
            }
        })
    }
}
