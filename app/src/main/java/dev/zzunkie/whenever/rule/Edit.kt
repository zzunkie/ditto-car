package dev.zzunkie.whenever.rule

// 확인 화면에서 값(온도, %, 분, 시각, 단)을 한 단계씩 고친다. 범위 밖으로는 가지 않는다.

object Edit {
    fun editable(t: Trigger) = step(t, 1) != null
    fun editable(c: Condition) = step(c, 1) != null
    fun editable(a: Action) = step(a, 1) != null

    fun step(t: Trigger, dir: Int): Trigger? = when (t) {
        is Trigger.BatteryBelow -> t.copy(percent = (t.percent + 5 * dir).coerceIn(Limits.BATTERY_MIN, Limits.BATTERY_MAX))
        is Trigger.MinutesToArrival -> t.copy(minutes = (t.minutes + 5 * dir).coerceIn(5, Limits.ARRIVAL_MAX))
        is Trigger.Time -> t.copy(hour = (t.hour + dir + 24) % 24)
        else -> null
    }

    fun step(c: Condition, dir: Int): Condition? = when (c) {
        is Condition.TimeBefore -> c.copy(hour = (c.hour + dir + 24) % 24)
        is Condition.TimeBetween -> c.copy(toHour = (c.toHour + dir + 24) % 24)
        is Condition.BatteryBetween -> c.copy(max = (c.max + 5 * dir).coerceIn(c.min + 5, 100))
        else -> null
    }

    fun step(a: Action, dir: Int): Action? = when (a) {
        is Action.Temperature -> a.copy(celsius = (a.celsius + 0.5f * dir).coerceIn(Limits.TEMP_MIN, Limits.TEMP_MAX))
        is Action.FanSpeed -> a.copy(level = (a.level + dir).coerceIn(Limits.FAN_MIN, Limits.FAN_MAX))
        is Action.SeatClimate -> a.copy(level = (a.level + dir).coerceIn(Limits.SEAT_MIN, Limits.SEAT_MAX))
        is Action.SteeringHeat -> a.copy(level = (a.level + dir).coerceIn(0, Limits.STEER_MAX))
        is Action.AmbientLight -> a.copy(brightness = (a.brightness + 10 * dir).coerceIn(0, 100))
        else -> null
    }
}
