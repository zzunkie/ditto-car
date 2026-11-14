package dev.zzunkie.whenever.engine

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import dev.zzunkie.whenever.car.Car
import dev.zzunkie.whenever.car.CarEvent
import dev.zzunkie.whenever.car.CarState
import dev.zzunkie.whenever.car.Exec
import dev.zzunkie.whenever.car.Navi
import dev.zzunkie.whenever.car.NaviEvent
import dev.zzunkie.whenever.car.Places
import dev.zzunkie.whenever.car.Speaker
import dev.zzunkie.whenever.car.TAG
import dev.zzunkie.whenever.car.doorValues
import dev.zzunkie.whenever.rule.Action
import dev.zzunkie.whenever.rule.Condition
import dev.zzunkie.whenever.rule.GearSel
import dev.zzunkie.whenever.rule.Phrase
import dev.zzunkie.whenever.rule.Place
import dev.zzunkie.whenever.rule.Rule
import dev.zzunkie.whenever.rule.Trigger
import dev.zzunkie.whenever.rule.WarningKind
import dev.zzunkie.whenever.rule.needsPark
import ai.pleos.playground.vehicle.constant.area.DoorValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * 저장된 규칙을 지켜보다가 실행한다. 포그라운드 서비스(RuleService)가 만들고 없앤다.
 * 모든 판단은 메인 스레드 한 곳에서 한다.
 *
 * - 시작 조건은 "바뀌는 순간"에만 걸린다. 같은 상태가 이어지는 동안 반복 실행하지 않는다
 *   (배터리 19%, 18% 로 내려가도 20% 를 처음 넘어설 때 한 번).
 * - 주차(P)에서만 되는 동작은 "주차하면 이어서 실행"으로 미뤘다가 P 가 되면 실행한다.
 *   그 사이 시작 조건이 풀리면 취소하고 기록에 남긴다.
 */
object Engine {
    private var scope: CoroutineScope? = null
    private var appCtx: Context? = null
    private var car: Car? = null
    private var navi: Navi? = null
    private var speaker: Speaker? = null

    private val _car = MutableStateFlow(CarState())
    /** 화면이 읽는 차 상태 (서비스가 없으면 기본값) */
    val carState: StateFlow<CarState> = _car
    val unsupported = MutableStateFlow<Set<String>>(emptySet())
    val running = MutableStateFlow(false)

    /** 주행 중에 받아 둔 문장. 주차하면 화면이 확인 화면으로 연다. */
    val pendingText = MutableStateFlow<String?>(null)

    private data class Deferred(val recordId: String, val index: Int, val action: Action, val trigger: Trigger?)
    private val deferred = mutableListOf<Deferred>()
    private val openedSincePark = mutableSetOf<DoorValue>()

    fun start(ctx: Context) {
        if (scope != null) return
        Store.init(ctx)
        appCtx = ctx.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val c = Car(ctx, s).also { car = it }
        val n = Navi(ctx, s).also { navi = it }
        speaker = Speaker(ctx).also { it.start() }
        c.start()
        n.start()
        s.launch { c.state.collect { _car.value = it } }
        s.launch { c.unsupported.collect { unsupported.value = it } }
        s.launch { c.events.collect { onCar(it) } }
        s.launch { n.events.collect { onNavi(it) } }
        s.launch { clock() }
        running.value = true
        Log.i(TAG, "engine started")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        car?.release(); navi?.release(); speaker?.release()
        car = null; navi = null; speaker = null
        running.value = false
    }

    fun say(text: String) = speaker?.say(text)

    /** 시연용: 저장 장소로 안내를 건다(경유지 시나리오의 출발점) */
    fun navigateTo(place: Place) {
        runCatching { navi?.navigate(if (place == Place.work) Places.work else Places.home) }
    }

    /** 확인 화면의 "지금 한번 해 보기" */
    fun runNow(rule: Rule) {
        scope?.launch { fire(rule, "지금 한번 해 보기", manual = true) }
    }

    // ---------- 이벤트 → 규칙 ----------

    private suspend fun onCar(e: CarEvent) {
        val st = _car.value
        when (e) {
            is CarEvent.Door -> if (e.open) openedSincePark += e.door
            is CarEvent.Gear -> if (e.gear == GearSel.P) openedSincePark.clear()
            else -> {}
        }
        cancelReleased()
        if (e is CarEvent.Gear && e.gear == GearSel.P) runDeferred()
        for (r in activeRules()) if (fires(r.trigger, e, st)) fire(r, Phrase.trigger(r.trigger))
    }

    private suspend fun onNavi(e: NaviEvent) {
        cancelReleased()
        for (r in activeRules()) {
            val t = r.trigger
            val hit = when (e) {
                NaviEvent.RouteStarted -> t is Trigger.RouteStarted
                NaviEvent.Arrived -> t is Trigger.Arrived
                is NaviEvent.Remain -> t is Trigger.MinutesToArrival && e.prev != null &&
                    e.prev > t.minutes * 60 && e.seconds <= t.minutes * 60
                is NaviEvent.PlaceEdge -> t is Trigger.NearPlace && t.place == e.place && t.enter == e.inside
            }
            if (hit) fire(r, Phrase.trigger(t))
        }
    }

    private suspend fun clock() {
        var last = minuteOfDay()
        while (scope?.isActive == true) {
            delay(15_000)
            val now = minuteOfDay()
            if (now != last) {
                for (r in activeRules()) {
                    val t = r.trigger
                    if (t is Trigger.Time) {
                        val target = t.hour * 60 + t.minute
                        val crossed = if (now >= last) target in (last + 1)..now else target > last || target <= now
                        if (crossed) fire(r, Phrase.trigger(t))
                    }
                }
                last = now
            }
        }
    }

    private fun activeRules() = Store.rules.value.filter { it.enabled }

    /** 상태가 바뀌는 순간인지(이벤트 자체가 변화일 때만 온다) */
    private fun fires(t: Trigger, e: CarEvent, st: CarState): Boolean = when (t) {
        is Trigger.Door -> e is CarEvent.Door && e.open == t.open && e.door in doorValues(t.door) &&
            // "아무 문"은 처음 열린 문 하나에만 반응
            (t.door != dev.zzunkie.whenever.rule.DoorSel.any || !t.open ||
                doorValues(t.door).count { st.doorsOpen[it] == true } == 1)
        is Trigger.Gear -> e is CarEvent.Gear && e.gear == t.gear
        is Trigger.BatteryBelow -> e is CarEvent.Soc && e.now < t.percent && (e.prev == null || e.prev >= t.percent)
        Trigger.ChargePortOpened -> e is CarEvent.ChargePort && e.open
        Trigger.ChargingStarted -> e is CarEvent.Charging && e.on
        Trigger.ChargingStopped -> e is CarEvent.Charging && !e.on
        Trigger.WiperOn -> e is CarEvent.Wiper && e.on
        Trigger.WindowOpened -> e is CarEvent.Window && e.anyOpen
        is Trigger.WarningLight -> e is CarEvent.Warning &&
            if (t.kind == WarningKind.any) e.prev.isEmpty() && e.on.isNotEmpty() else t.kind in e.on && t.kind !in e.prev
        else -> false
    }

    /** 미룬 동작을 계속 기다려도 되는지: 시작 조건이 아직 유지되는지 */
    private fun holds(t: Trigger?, st: CarState): Boolean = when (t) {
        null -> true
        is Trigger.Door -> st.doorOpen(t.door) == t.open
        is Trigger.BatteryBelow -> (st.soc ?: 0) < t.percent
        Trigger.ChargePortOpened -> st.chargePortOpen == true
        Trigger.ChargingStarted -> st.charging == true
        Trigger.ChargingStopped -> st.charging != true
        Trigger.WiperOn -> st.wiperOn == true
        Trigger.WindowOpened -> st.anyWindowOpen
        is Trigger.WarningLight -> if (t.kind == WarningKind.any) st.warnings.isNotEmpty() else t.kind in st.warnings
        is Trigger.NearPlace -> navi?.isInside(t.place) == t.enter
        else -> true // 기어·시각·도착처럼 순간에 일어나는 조건은 풀리지 않는다
    }

    private fun conditionsOk(cs: List<Condition>, st: CarState): Boolean = cs.all { c ->
        when (c) {
            is Condition.GearIs -> st.gear == c.gear
            is Condition.TimeBefore -> minuteOfDay() < c.hour * 60 + c.minute
            is Condition.TimeBetween -> {
                val h = minuteOfDay() / 60
                if (c.fromHour < c.toHour) h in c.fromHour until c.toHour else h >= c.fromHour || h < c.toHour
            }
            is Condition.BatteryBetween -> st.soc != null && st.soc in c.min..c.max
            Condition.WiperIsOn -> st.wiperOn == true
            Condition.WindowIsOpen -> st.anyWindowOpen
            is Condition.DoorIsOpen -> st.doorOpen(c.door)
            Condition.Navigating -> navi?.navigating?.value == true
            is Condition.DoorOpenedSincePark -> doorValues(c.door).any { it in openedSincePark } && !st.doorOpen(c.door)
        }
    }

    private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    // ---------- 실행 ----------

    private suspend fun fire(rule: Rule, cause: String, manual: Boolean = false) {
        val st = _car.value
        if (!manual && !conditionsOk(rule.conditions, st)) {
            Log.i(TAG, "rule ${rule.id} trigger hit but conditions not met")
            return
        }
        Log.i(TAG, "fire ${rule.id} ($cause)")
        val id = UUID.randomUUID().toString()
        val results = mutableListOf<ActionRun>()
        // 먼저 "실행 중" 기록을 남겨 화면이 바로 보이게 한다
        Store.putRun(RunRecord(id, rule.id, Phrase.sentence(rule), cause, System.currentTimeMillis(), emptyList(), manual))
        for ((i, a) in rule.actions.withIndex()) {
            val text = Phrase.action(a).second
            val r = if (a.needsPark && _car.value.gear != GearSel.P) Exec.NeedsPark else execute(a)
            results += when (r) {
                Exec.Ok -> ActionRun(text, Outcome.done, note(a))
                Exec.NeedsPark -> {
                    deferred += Deferred(id, i, a, if (manual) null else rule.trigger)
                    ActionRun(text, Outcome.deferred, "주차하면 이어서 실행해요")
                }
                is Exec.Fail -> ActionRun(text, Outcome.failed, r.reason)
            }
            Store.putRun(RunRecord(id, rule.id, Phrase.sentence(rule), cause, Store.run(id)?.at ?: System.currentTimeMillis(), results.toList(), manual))
        }
        lastNote = null
    }

    private var lastNote: String? = null
    private fun note(a: Action) = if (a is Action.ChargerWaypoint) lastNote.orEmpty() else ""

    private suspend fun execute(a: Action): Exec = when (a) {
        is Action.Speak -> { speaker?.say(a.text); Exec.Ok }
        is Action.Notify -> { notifyText(a.text); Exec.Ok }
        is Action.Navigate -> runCatching { navi!!.navigate(if (a.place == Place.work) Places.work else Places.home); Exec.Ok }
            .getOrElse { Exec.Fail("내비를 열지 못했어요") }
        Action.ChargerWaypoint -> runCatching { lastNote = navi!!.addChargerWaypoint().name + "을 경유지로 넣었어요"; Exec.Ok }
            .getOrElse { Exec.Fail("내비에 경유지를 넣지 못했어요") }
        Action.CancelRoute -> runCatching { navi!!.cancel(); Exec.Ok }.getOrElse { Exec.Fail("안내를 끝내지 못했어요") }
        else -> car?.run(a) ?: Exec.Fail("차와 연결되지 않았어요")
    }

    private fun updateAction(recordId: String, index: Int, run: ActionRun) {
        val rec = Store.run(recordId) ?: return
        val list = rec.actions.toMutableList()
        if (index in list.indices) list[index] = run
        Store.putRun(rec.copy(actions = list))
    }

    private fun cancelReleased() {
        val st = _car.value
        val gone = deferred.filter { !holds(it.trigger, st) }
        for (d in gone) {
            Log.i(TAG, "deferred cancelled ${d.action}")
            updateAction(d.recordId, d.index, ActionRun(Phrase.action(d.action).second, Outcome.cancelled, "주차 전에 시작 조건이 풀려서 취소했어요"))
        }
        deferred.removeAll(gone)
    }

    private suspend fun runDeferred() {
        val list = deferred.toList()
        deferred.clear()
        for (d in list) {
            val text = Phrase.action(d.action).second
            val run = when (val r = execute(d.action)) {
                Exec.Ok -> ActionRun(text, Outcome.done_after_park, "주차한 뒤 실행했어요")
                Exec.NeedsPark -> ActionRun(text, Outcome.failed, "주차했는데도 차가 받아 주지 않았어요")
                is Exec.Fail -> ActionRun(text, Outcome.failed, r.reason)
            }
            updateAction(d.recordId, d.index, run)
        }
    }

    private fun notifyText(text: String) {
        val ctx = appCtx ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = android.app.Notification.Builder(ctx, RuleService.CHANNEL_ALERTS)
            .setContentTitle("Ditto")
            .setContentText(text)
            .setSmallIcon(dev.zzunkie.whenever.R.drawable.ic_stat)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}
