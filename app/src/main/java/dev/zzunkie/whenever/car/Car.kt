package dev.zzunkie.whenever.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import dev.zzunkie.whenever.rule.Action
import dev.zzunkie.whenever.rule.DoorSel
import dev.zzunkie.whenever.rule.GearSel
import dev.zzunkie.whenever.rule.SeatSel
import dev.zzunkie.whenever.rule.WarningKind
import dev.zzunkie.whenever.rule.WiperMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

const val TAG = "Whenever"

data class CarState(
    val gear: GearSel? = null,
    val soc: Int? = null,
    val doorsOpen: Map<DoorValue, Boolean> = emptyMap(),
    val chargePortOpen: Boolean? = null,
    val charging: Boolean? = null,
    val wiperOn: Boolean? = null,
    val windowsOpen: Map<WindowValue, Boolean> = emptyMap(),
    val warnings: Set<WarningKind> = emptySet(),
) {
    val anyWindowOpen get() = windowsOpen.values.any { it }
    fun doorOpen(sel: DoorSel) = doorValues(sel).any { doorsOpen[it] == true }
}

fun doorValues(sel: DoorSel): List<DoorValue> = when (sel) {
    DoorSel.any -> listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.REAR)
    DoorSel.driver -> listOf(DoorValue.ROW_1_LEFT)
    DoorSel.passenger -> listOf(DoorValue.ROW_1_RIGHT)
    DoorSel.rear -> listOf(DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    DoorSel.trunk -> listOf(DoorValue.REAR)
}

sealed interface CarEvent {
    data class Door(val door: DoorValue, val open: Boolean) : CarEvent
    data class Gear(val gear: GearSel?, val prev: GearSel?) : CarEvent
    data class Soc(val now: Int, val prev: Int?) : CarEvent
    data class ChargePort(val open: Boolean) : CarEvent
    data class Charging(val on: Boolean) : CarEvent
    data class Wiper(val on: Boolean) : CarEvent
    data class Window(val anyOpen: Boolean) : CarEvent
    data class Warning(val on: Set<WarningKind>, val prev: Set<WarningKind>) : CarEvent
}

sealed interface Exec {
    data object Ok : Exec
    /** 기어가 P 가 아니라 차가 거부했거나(50001) 앱이 미리 막은 경우 */
    data object NeedsPark : Exec
    data class Fail(val reason: String) : Exec
}

/**
 * Vehicle SDK 를 감싼다. 상태 감시(리스너)와 동작 실행(Set).
 * 리스너 등록 직후 오는 첫 값은 현재 상태로만 쓰고 이벤트로 내보내지 않는다(spike 8.4 의 낡은 값 문제).
 */
class Car(ctx: Context, private val scope: CoroutineScope) {
    private val v = Vehicle(ctx)
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state
    private val _events = MutableSharedFlow<CarEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CarEvent> = _events

    /** capability 확인에서 쓰기가 안 된다고 나온 동작 종류 */
    val unsupported = MutableStateFlow<Set<String>>(emptySet())

    // capability 가 돌려준 묶음 영역. 확인 전에는 spike 에서 확인한 값을 쓴다.
    private var cabin = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private var fanArea = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
    private var ambientArea = cabin
    private var windowLockArea = WindowArea(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    private var doorLockArea = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)

    private val seen = mutableSetOf<String>()

    /** 첫 값이면 false (상태만 갱신) */
    private fun fresh(key: String): Boolean = synchronized(seen) { !seen.add(key) }

    private fun emit(e: CarEvent) {
        Log.i(TAG, "event $e")
        _events.tryEmit(e)
    }

    fun start() {
        v.initialize()
        scope.launch {
            delay(1500) // 서비스 연결을 기다림 (spike 와 같은 방식)
            readCapabilities()
            listen()
            pollCharging()
        }
    }

    fun release() = runCatching { v.release() }

    private fun writable(c: CapabilityConfig) = c.accessType.toString().contains("WRITE")

    private fun mark(type: String, ok: Boolean) = unsupported.update { if (ok) it - type else it + type }

    private fun readCapabilities() {
        val h = v.getHVAC()
        h.checkHvacPowerCapabilities({ c, l -> l.firstOrNull()?.let { cabin = it }; mark("hvac_power", writable(c)) }, { mark("hvac_power", false) })
        h.checkHvacTemperatureCapabilities({ c, _ -> mark("temperature", writable(c)) }, { mark("temperature", false) })
        h.checkHvacFanSpeedCapabilities({ c, m -> m.keys.firstOrNull()?.let { fanArea = it }; mark("fan_speed", writable(c)) }, { mark("fan_speed", false) })
        h.checkHvacDefrosterCapabilities({ c, _ -> mark("defrost", writable(c)) }, { mark("defrost", false) })
        v.getSeat().checkSeatTemperatureCapabilities({ c, _ -> mark("seat_climate", writable(c)) }, { mark("seat_climate", false) })
        v.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities({ c, _ -> mark("steering_heat", writable(c)) }, { mark("steering_heat", false) })
        val l = v.getLight()
        l.checkAmbientLightBrightnessCapabilities({ c, m -> m.keys.firstOrNull()?.let { ambientArea = it }; mark("ambient_light", writable(c)) }, { mark("ambient_light", false) })
        l.checkCabinLightSwitchCapabilities({ c, _ -> mark("cabin_light", writable(c)) }, { mark("cabin_light", false) })
        l.checkReadingLightSwitchCapabilities({ c, _ -> mark("reading_light", writable(c)) }, { mark("reading_light", false) })
        v.getWindow().checkWindowLockCapabilities({ c, ls -> ls.firstOrNull()?.let { windowLockArea = it }; mark("window_lock", writable(c)) }, { mark("window_lock", false) })
        v.getDoor().checkDoorLockCapabilities({ c, ls -> ls.firstOrNull()?.let { doorLockArea = it }; mark("door_lock", writable(c)) }, { mark("door_lock", false) })
        v.getWiper().checkWiperSwitchCapabilities({ c, _ -> mark("wiper", writable(c)) }, { mark("wiper", false) })
        v.getEvBattery().checkEvChargePortOpenCapabilities({ c -> mark("charge_port", writable(c)) }, { mark("charge_port", false) })
    }

    private fun listen() {
        val door = v.getDoor()
        for (dv in listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT, DoorValue.REAR)) {
            door.registerDoorPosition(DoorArea(dv), object : DoorPositionListener {
                override fun onPositionUpdated(value: Int?) {
                    val open = (value ?: 0) > 0
                    val prev = _state.value.doorsOpen[dv]
                    _state.update { it.copy(doorsOpen = it.doorsOpen + (dv to open)) }
                    if (fresh("door$dv") && prev != open) emit(CarEvent.Door(dv, open))
                }
                override fun onFailed(e: Exception) { Log.w(TAG, "door $dv listener $e") }
            })
        }
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) {
                val g = gearOf(value)
                val prev = _state.value.gear
                _state.update { it.copy(gear = g) }
                if (fresh("gear") && prev != g) emit(CarEvent.Gear(g, prev))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "gear listener $e") }
        })
        v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                value ?: return
                val prev = _state.value.soc
                _state.update { it.copy(soc = value) }
                if (fresh("soc") && prev != value) emit(CarEvent.Soc(value, prev))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "soc listener $e") }
        })
        v.getEvBattery().registerEvChargePortOpen(object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(value: Boolean?) {
                value ?: return
                val prev = _state.value.chargePortOpen
                _state.update { it.copy(chargePortOpen = value) }
                if (fresh("port") && prev != value) emit(CarEvent.ChargePort(value))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "port listener $e") }
        })
        v.getWiper().registerWiperState(front, object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) {
                val on = value == WiperState.ON
                val prev = _state.value.wiperOn
                _state.update { it.copy(wiperOn = on) }
                if (fresh("wiper") && prev != on) emit(CarEvent.Wiper(on))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "wiper listener $e") }
        })
        for (wv in listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)) {
            v.getWindow().registerWindowPosition(WindowArea(wv), object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) {
                    val before = _state.value.anyWindowOpen
                    _state.update { it.copy(windowsOpen = it.windowsOpen + (wv to ((value ?: 0) > 0))) }
                    val after = _state.value.anyWindowOpen
                    if (fresh("window$wv") && before != after) emit(CarEvent.Window(after))
                }
                override fun onFailed(e: Exception) { Log.w(TAG, "window $wv listener $e") }
            })
        }
        v.getSafety().registerTelltales(object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(value: Telltales?) {
                val on = value?.telltales.orEmpty()
                    .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
                    .mapNotNull {
                        when (it.type) {
                            TelltaleType.TPMS -> WarningKind.tpms
                            TelltaleType.SEAT_BELT -> WarningKind.seat_belt
                            else -> WarningKind.any
                        }
                    }.toSet()
                val prev = _state.value.warnings
                _state.update { it.copy(warnings = on) }
                if (fresh("telltale") && prev != on) emit(CarEvent.Warning(on, prev))
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "telltale listener $e") }
        })
        Log.i(TAG, "vehicle listeners registered")
    }

    /** 충전 중 여부는 콜백이 없어 읽기로 확인한다(spike 8.3) */
    private suspend fun pollCharging() {
        while (scope.isActive) {
            v.getEvBattery().getEvChargeSwitchState({ on ->
                on ?: return@getEvChargeSwitchState
                val prev = _state.value.charging
                _state.update { it.copy(charging = on) }
                if (fresh("charging") && prev != on) emit(CarEvent.Charging(on))
            }, { e -> Log.w(TAG, "charging read $e") })
            delay(1000)
        }
    }

    private fun gearOf(g: VehicleGear?): GearSel? = when (g) {
        VehicleGear.GEAR_PARK -> GearSel.P
        VehicleGear.GEAR_REVERSE -> GearSel.R
        null, VehicleGear.GEAR_UNKNOWN -> null
        else -> GearSel.D // D, N, 1~9 는 모두 주행 쪽으로 본다
    }

    // ---------- 동작 실행 ----------

    private suspend fun call(block: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit): Exec =
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    block({ if (cont.isActive) cont.resume(Exec.Ok) }, { e -> if (cont.isActive) cont.resume(classify(e)) })
                }.onFailure { e -> if (cont.isActive) cont.resume(classify(e)) }
            }
        } ?: Exec.Fail("차가 응답하지 않았어요")

    private fun classify(e: Throwable): Exec {
        Log.w(TAG, "set failed $e")
        return if (e.message?.contains("50001") == true) Exec.NeedsPark else Exec.Fail(e.message?.take(80) ?: e.javaClass.simpleName)
    }

    suspend fun run(a: Action): Exec {
        val h = v.getHVAC()
        return when (a) {
            is Action.HvacPower -> call { ok, f -> h.setHvacPower(cabin, a.on, { _, _ -> ok() }, f) }
            is Action.Temperature -> {
                // 공조가 꺼져 있으면 일부 설정이 거부되어 먼저 켠다(spike 8.1 풍량)
                call { ok, f -> h.setHvacPower(cabin, true, { _, _ -> ok() }, f) }
                call { ok, f -> h.setHvacTemperature(driver, a.celsius, TemperatureUnit.CELSIUS, { _, _ -> ok() }, f) }
            }
            is Action.FanSpeed -> {
                call { ok, f -> h.setHvacPower(cabin, true, { _, _ -> ok() }, f) }
                call { ok, f -> h.setHvacFanSpeed(fanArea, a.level, { _, _ -> ok() }, f) }
            }
            is Action.Defrost -> call { ok, f -> h.setHvacDefroster(front, a.on, { _, _ -> ok() }, f) }
            is Action.SeatClimate -> {
                val seats = when (a.seat) {
                    SeatSel.driver -> listOf(driver)
                    SeatSel.passenger -> listOf(passenger)
                    SeatSel.front -> listOf(driver, passenger)
                }
                seats.map { s -> call { ok, f -> v.getSeat().setHvacSeatTemperature(s, a.level, { _, _ -> ok() }, f) } }
                    .firstOrNull { it != Exec.Ok } ?: Exec.Ok
            }
            is Action.SteeringHeat -> call { ok, f -> v.getSteeringWheel().setHvacSteeringWheelHeat(a.level, { ok() }, f) }
            is Action.AmbientLight -> {
                val l = v.getLight()
                val r = call { ok, f -> l.setAmbientLightBrightness(ambientArea, a.brightness, { _, _ -> ok() }, f) }
                val color = a.color?.removePrefix("#")?.toIntOrNull(16)
                if (r == Exec.Ok && color != null) call { ok, f -> l.setAmbientLightBrightnessColor(ambientArea, color, { _, _ -> ok() }, f) } else r
            }
            is Action.WindowLock -> call { ok, f -> v.getWindow().setWindowLock(windowLockArea, a.on, { _, _ -> ok() }, f) }
            is Action.Wiper -> call { ok, f ->
                v.getWiper().setWiperSwitch(front, if (a.mode == WiperMode.auto) WiperSwitch.AUTO else WiperSwitch.OFF, { _, _ -> ok() }, f)
            }
            is Action.CabinLight -> call { ok, f -> v.getLight().setCabinLightSwitch(if (a.on) LightSwitch.ON else LightSwitch.OFF, { ok() }, f) }
            is Action.ReadingLight -> {
                listOf(driver, passenger).map { s ->
                    call { ok, f -> v.getLight().setReadingLightSwitch(s, if (a.on) LightSwitch.ON else LightSwitch.OFF, { ok() }, f) }
                }.firstOrNull { it != Exec.Ok } ?: Exec.Ok
            }
            Action.DoorLock -> call { ok, f -> v.getDoor().setDoorLock(doorLockArea, true, { _, _ -> ok() }, f) }
            Action.ChargePort -> call { ok, f -> v.getEvBattery().setEvChargePortOpen(true, { ok() }, f) }
            else -> Exec.Fail("차량 동작이 아니에요")
        }
    }
}
