package dev.zzunkie.whenever.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import dev.zzunkie.whenever.rule.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Spot(val name: String, val lat: Double, val lon: Double)

/**
 * 표본 충전소 목록. 대회 SDK 에 장소 검색이 없어 앱에 넣어 둔 시연용 데이터다(실제 운영 정보 아님).
 */
object Chargers {
    val list = listOf(
        Spot("표본 충전소 양재", 37.4846, 127.0342),
        Spot("표본 충전소 사당", 37.4766, 126.9816),
        Spot("표본 충전소 용산", 37.5298, 126.9648),
        Spot("표본 충전소 강남", 37.4979, 127.0276),
        Spot("표본 충전소 판교", 37.3947, 127.1112),
        Spot("표본 충전소 수원", 37.2636, 127.0286),
        Spot("표본 충전소 잠실", 37.5133, 127.1002),
        Spot("표본 충전소 여의도", 37.5219, 126.9245),
        Spot("표본 충전소 일산", 37.6584, 126.7697),
        Spot("표본 충전소 송도", 37.3826, 126.6566),
    )

    /** 경로(출발→목적지)에서 덜 돌아가는 충전소. 목적지가 없으면 가장 가까운 곳. */
    fun along(from: Spot, to: Spot?): Spot =
        if (to == null) list.minBy { meters(from, it) }
        else list.minBy { meters(from, it) + meters(it, to) - meters(from, to) }
}

/** 시연용 저장 장소. 설정 화면이 없어 고정값으로 둔다. */
object Places {
    val home = Spot("집", 37.3595, 127.1052)
    val work = Spot("회사", 37.5665, 126.9780)
    const val RADIUS_M = 300.0
}

fun meters(a: Spot, b: Spot): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

sealed interface NaviEvent {
    data object RouteStarted : NaviEvent
    data object Arrived : NaviEvent
    data class Remain(val seconds: Int, val prev: Int?) : NaviEvent
    data class PlaceEdge(val place: Place, val inside: Boolean) : NaviEvent
}

/** NaviHelper(내장 내비)와 FusedLocation(차 위치) */
class Navi(private val ctx: Context, private val scope: CoroutineScope) {
    private val helper = NaviHelper(ctx)
    private var location: FusedLocation? = null
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<NaviEvent> = _events

    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating
    private var destination: Spot? = null
    private var remainSec: Int? = null
    /** 내비가 알려 준 현재 위치. 없으면 FusedLocation 값 */
    @Volatile var here: Spot = Spot("현재 위치", 37.4127, 127.0947)
        private set
    private val inside = mutableMapOf<Place, Boolean>()

    fun start() {
        helper.addListener(listener)
        helper.initialize({ Log.i(TAG, "navi ready"); runCatching { helper.getCurrentLocationInfo(); helper.getDestinationInfo() } },
            { e -> Log.w(TAG, "navi init failed $e") })
        scope.launch {
            delay(1500)
            startLocation()
            while (isActive) {
                if (_navigating.value) runCatching { helper.getDestinationInfo() }
                delay(10_000)
            }
        }
    }

    fun release() {
        runCatching { helper.removeListener(listener); helper.release() }
        runCatching { location?.release() }
    }

    private fun startLocation() {
        val l = runCatching { FusedLocation(ctx).also { it.initialize(); location = it } }.getOrNull() ?: return
        scope.launch {
            delay(1500)
            runCatching {
                l.registerFusedLocationCallback(object : FusedLocationListener {
                    override fun onChanged(data: LocationData?) {
                        data ?: return
                        onLocation(Spot("현재 위치", data.latitude, data.longitude))
                    }
                    override fun onError(e: Exception) { Log.w(TAG, "location $e") }
                })
            }.onFailure { Log.w(TAG, "location register $it") }
        }
    }

    private fun onLocation(p: Spot) {
        here = p
        val near = mapOf(
            Place.home to (meters(p, Places.home) < Places.RADIUS_M),
            Place.work to (meters(p, Places.work) < Places.RADIUS_M),
            Place.charger to Chargers.list.any { meters(p, it) < Places.RADIUS_M },
        )
        for ((place, now) in near) {
            val prev = inside.put(place, now)
            // 첫 위치는 상태로만 쓴다
            if (prev != null && prev != now) {
                Log.i(TAG, "place $place inside=$now")
                _events.tryEmit(NaviEvent.PlaceEdge(place, now))
            }
        }
    }

    fun isInside(place: Place) = inside[place] == true

    // ---------- 동작 ----------

    fun navigate(to: Spot) {
        helper.requestRoute(routeInfo(to))
    }

    /** 가는 길 충전소를 경유지로 넣는다. 안내 중이 아니면 가장 가까운 충전소로 안내. 고른 충전소를 돌려준다. */
    fun addChargerWaypoint(): Spot {
        val dest = destination.takeIf { _navigating.value }
        val c = Chargers.along(here, dest)
        if (dest == null) {
            helper.requestRoute(routeInfo(c))
        } else {
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    routeInfo(dest),
                    listOf(
                        RequestWaypointInfo(
                            latitude = c.lat, longitude = c.lon, poiName = c.name,
                            waypointIndex = WaypointIndex.FIRST, poiId = "", address = "", poiSubId = "", poiProvider = "",
                        )
                    ),
                )
            )
        }
        return c
    }

    fun cancel() = helper.cancelRoute()

    private fun routeInfo(s: Spot) = RouteInfo(
        latitude = s.lat, longitude = s.lon, displayLatitude = null, displayLongitude = null,
        poiName = s.name, poiId = "", address = "", poiSubId = "",
        routeOption = RouteOption.RECOMMENDED, poiProvider = "", isMobileRequest = false,
    )

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            Log.i(TAG, "navi route started $info")
            val was = _navigating.value
            _navigating.value = true
            remainSec = null
            runCatching { helper.getDestinationInfo() }
            if (!was) _events.tryEmit(NaviEvent.RouteStarted)
        }
        // onRouteStarted 직후 한 번 오는 onRouteEnded 는 탐색 끝을 뜻해(spike 4절) 안내 상태는 NavigationStatus 로 본다
        override fun onRouteEnded() {}
        override fun onRoutingCompleted() {}
        override fun onRouteCancelled() {
            _navigating.value = false
            destination = null
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) {}
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            Log.i(TAG, "navi arrived $info")
            _navigating.value = false
            _events.tryEmit(NaviEvent.Arrived)
        }
        override fun onDestinationChanged() { runCatching { helper.getDestinationInfo() } }
        override fun onError(code: NaviErrorCode) { Log.w(TAG, "navi error $code") }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            if (location == null) here = Spot("현재 위치", info.latitude, info.longitude)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {}
        override fun onDestinationInfo(info: DestinationInfo) {
            if (info.latitude == 0.0 && info.longitude == 0.0) return
            destination = Spot(info.name.ifBlank { "목적지" }, info.latitude, info.longitude)
            val prev = remainSec
            remainSec = info.remainTime
            if (_navigating.value) _events.tryEmit(NaviEvent.Remain(info.remainTime, prev))
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {}
        override fun onDrivingInfo(info: DrivingInfo) {}
        override fun onNavigationStatus(status: NavigationStatus) {
            Log.i(TAG, "navi status $status")
            if (status == NavigationStatus.FREE_DRIVE || status == NavigationStatus.INACTIVE) _navigating.value = false
            if (status == NavigationStatus.ROUTE_DRIVE) _navigating.value = true
        }
        override fun onTripInfo(info: TripInfo) {}
    }
}
