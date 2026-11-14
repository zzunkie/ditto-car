#!/usr/bin/env bash
# 시연 시나리오 1~4를 에뮬레이터와 Pleos Connect Virtual Console 로 차례로 돌린다.
#
#   scripts/demo.sh            # 설치된 앱으로 전체 시나리오 (앱 데이터 초기화 포함)
#   scripts/demo.sh 3          # 시나리오 하나만
#   PAUSE=4 scripts/demo.sh    # 단계 사이 기다림(초). 영상 촬영 때 늘린다
#   SHOTS=docs/screens scripts/demo.sh   # 단계마다 화면 캡처를 남김
#
# 필요: 에뮬레이터(emulator-5584)가 떠 있고 앱이 설치되어 있을 것, 맥에 python3.
# 에뮬레이터 마이크에 소리를 넣을 방법이 없어 "말하기"는 앱의 글 입력 입구(--es text)로 대신한다.
set -euo pipefail

DEV=${DEV:-emulator-5584}
PKG=dev.zzunkie.whenever
B=http://127.0.0.1:18088
PAUSE=${PAUSE:-2}
SHOTS=${SHOTS:-}
ONLY=${1:-all}

adbe() { adb -s "$DEV" "$@"; }
say_step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pause() { sleep "${1:-$PAUSE}"; }

# ---------- Virtual Console ----------
adbe forward tcp:18088 tcp:8088 >/dev/null
# 콘솔 브라우저 탭이 없으면 값 쓰기가 503 이 되므로 살아 있다는 신호를 계속 보낸다
( while true; do curl -s -o /dev/null -X POST "$B/browser/session?client=cli"; sleep 1; done ) &
KEEPALIVE=$!
trap 'kill $KEEPALIVE 2>/dev/null || true' EXIT
sleep 1

console() { curl -s -o /dev/null -w "%{http_code}" -X PUT "$B$1"; echo "  PUT $1"; }
gear() { console "/vehicle/gear?gear=$1"; }

state() {
  curl -s "$B/vehicle/state" | python3 -c '
import json, sys
d = json.load(sys.stdin)
h, ev = d["hvac"], d["ev_battery"]
b = d["body"]
print("  기어", d["gear"]["actual"]["value"],
      "| 배터리", ev["state_of_charge"], "| 충전 중", ev["charging_activated"], "| 충전구 열림", ev["charging_door_opened"])
print("  서리 제거", h["front_defroster_on"], "| 온도", h["driver"]["temp_c"], "| 운전석 시트", h["driver"]["seat_temp"], h["driver"]["seat_mode"],
      "| 핸들 열선", d["steering_wheel_heat"]["level"])
print("  무드등", b["ambient_light"]["brightness_pct"], "%", b["ambient_light"]["color"],
      "| 창문 잠금", d["window_lock"]["locked"]["all"], "| 실내등", b["cabin_light"]["on"], "| 문 잠금", b["door_locks"]["all"])
'
}

# ---------- 앱 ----------
app() { adbe shell am start -n "$PKG/.ui.MainActivity" "$@" >/dev/null; }
# 운전자가 말한 문장 (STT 결과와 같은 경로로 들어간다)
speak() { echo "  말하기: $1"; app --es text "'$1'"; }

shot() {
  [ -n "$SHOTS" ] || return 0
  mkdir -p "$SHOTS"
  adbe exec-out screencap -p > "$SHOTS/$1.png"
  echo "  캡처 $SHOTS/$1.png"
}

# 화면에서 글자로 버튼을 찾아 누른다 (좌표를 박아 두지 않음)
tap() {
  local xml xy="" try
  # 문장 해석 응답이 늦을 수 있어 버튼이 보일 때까지 최대 12초쯤 다시 찾는다
  for try in 1 2 3 4 5 6; do
  adbe shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  xml=$(adbe exec-out cat /sdcard/ui.xml)
  xy=$(TEXT="$1" python3 -c '
import os, re, sys
xml = sys.stdin.read()
t = os.environ["TEXT"]
for m in re.finditer(r"<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", xml):
    if m.group(1) == t:
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2); break
' <<<"$xml")
  [ -n "$xy" ] && break; sleep 2
  done
  if [ -z "$xy" ]; then echo "  '$1' 버튼을 찾지 못함"; return 1; fi
  echo "  누름: $1"
  adbe shell input tap $xy
}

reset_all() {
  say_step "준비: 앱 데이터와 차량 상태 초기화"
  adbe shell am force-stop "$PKG"
  adbe shell pm clear --user current "$PKG" >/dev/null   # AAOS 는 운전자가 user 0 이 아니다
  for p in android.permission.RECORD_AUDIO android.permission.ACCESS_FINE_LOCATION android.permission.POST_NOTIFICATIONS pleos.car.permission.FUSED_LOCATION; do
    adbe shell pm grant --user current "$PKG" "$p" 2>/dev/null || true
  done
  gear Park
  console "/vehicle/ev-battery?field=soc&value=60"
  console "/vehicle/ev-battery?field=charge_switch&value=false"
  console "/vehicle/ev-battery?field=charge_port_open&value=false"
  console "/vehicle/hvac?front_defroster=false"
  console "/vehicle/hvac?driver_seat=0"
  console "/vehicle/steering-wheel-heat?level=0"
  console "/vehicle/body?ambient_brightness=70"
  adbe emu geo fix 127.0947 37.4127 >/dev/null
  app --es screen home
  sleep 6   # 서비스가 차량 리스너를 붙일 때까지
  state
}

# 현재 시각보다 한두 시간 뒤를 "~시 전에"로 말하게 한다 (아침 7시 전이면 그대로 7시)
before_clock() {
  local h; h=$(date +%H); h=$((10#$h))
  if [ "$h" -lt 7 ]; then echo "아침 7시"; return; fi
  local t=$(( (h + 2) % 24 ))
  if   [ "$t" -eq 0 ];  then echo "밤 12시"
  elif [ "$t" -le 5 ];  then echo "새벽 ${t}시"
  elif [ "$t" -le 11 ]; then echo "아침 ${t}시"
  elif [ "$t" -eq 12 ]; then echo "낮 12시"
  elif [ "$t" -le 17 ]; then echo "오후 $((t - 12))시"
  elif [ "$t" -le 20 ]; then echo "저녁 $((t - 12))시"
  else echo "밤 $((t - 12))시"; fi
}

scenario1() {
  say_step "시나리오 1: 추운 아침 출발 (시각 조건 + 기어 D)"
  gear Park; pause 1
  speak "$(before_clock) 전에 출발하면 앞유리 서리 제거와 스티어링 휠 열선을 켜고 시트 열선을 2단으로 해 줘"
  pause 3; shot s1-confirm
  tap "저장"; pause; shot s1-saved
  state
  gear Drive; pause 3
  state
  gear Park; pause 2   # 주행 중에는 앱 화면이 가려져 주차한 뒤 기록을 본다
  app --es screen log; pause; shot s1-log
}

scenario2() {
  say_step "시나리오 2: 배터리 20% 아래 → 가는 길 충전소를 경유지로"
  gear Park; console "/vehicle/ev-battery?field=soc&value=60"; pause 1
  app --es screen home
  speak "배터리가 20% 아래로 내려가면 가는 길 충전소를 경유지로 넣어 줘"
  pause 3; shot s2-confirm
  tap "저장"; pause
  echo "  회사로 길 안내를 먼저 건다(목적지가 있어야 경유지가 의미 있음)"
  app --es route_to work; pause 6
  app --es screen home; pause 1
  gear Drive; pause 1
  console "/vehicle/ev-battery?field=soc&value=15"
  pause 6; shot s2-navi   # 내비가 앞으로 나와 경유지 경로를 안내하는지
  console "/vehicle/ev-battery?field=soc&value=14"   # 계속 아래여도 다시 실행하지 않아야 한다
  pause 3
  app --es screen log; pause; shot s2-log
  gear Park
}

scenario3() {
  say_step "시나리오 3: 충전소에 도착하면 충전구 열기 → 주행 중이면 주차할 때까지 미룸"
  gear Park; console "/vehicle/ev-battery?field=charge_port_open&value=false"
  adbe emu geo fix 127.0947 37.4127 >/dev/null; pause 1
  app --es screen home
  speak "충전소에 도착하면 충전구를 열어 줘"
  pause 3; shot s3-confirm
  tap "저장"; pause
  gear Drive; pause 1
  echo "  위치를 표본 충전소(양재)로 옮김"
  adbe emu geo fix 127.0342 37.4846 >/dev/null
  pause 4
  echo "  (주행 중에는 Pleos 가 앱 화면을 가린다. 미룬 상태는 주차 뒤 실행 기록에서 본다)"
  state
  gear Park; pause 4
  app --es screen log; pause; shot s3-after-park
  state
  echo "  미룬 동작 취소 확인: 다시 출발 → 충전소 도착 → 주차 전에 벗어남"
  adbe emu geo fix 127.0947 37.4127 >/dev/null
  console "/vehicle/ev-battery?field=charge_port_open&value=false"
  gear Drive; pause 3
  adbe emu geo fix 127.0342 37.4846 >/dev/null; pause 4
  adbe emu geo fix 127.0947 37.4127 >/dev/null; pause 4
  gear Park; pause 3
  app --es screen log; pause; shot s3-cancelled
  state
}

scenario4() {
  say_step "시나리오 4: 둘러보기 '충전하며 쉴 때' → 충전 시작"
  gear Park; console "/vehicle/ev-battery?field=charge_switch&value=false"
  console "/vehicle/hvac?driver_seat=0"; console "/vehicle/body?ambient_brightness=70"; pause 1
  app --es screen browse; pause; shot s4-browse
  app --es pick "'충전하며 쉴 때'"; pause 2; shot s4-confirm1
  tap "저장"; pause 2; shot s4-confirm2
  tap "저장"; pause
  state
  console "/vehicle/ev-battery?field=charge_switch&value=true"
  pause 5; state
  app --es screen log; pause; shot s4-log
  console "/vehicle/ev-battery?field=charge_switch&value=false"
  pause 5; state
  app --es screen log; pause; shot s4-log-end
}

reset_all
case "$ONLY" in
  1) scenario1 ;;
  2) scenario2 ;;
  3) scenario3 ;;
  4) scenario4 ;;
  *) scenario1; scenario2; scenario3; scenario4 ;;
esac
say_step "끝. 앱 기록: adb -s $DEV logcat -d -s Whenever:I"
