#!/bin/bash
# LIVE-mode demo used for docs/demo.mp4 (part ②).
# Changes vehicle state through the Pleos Connect Virtual Console REST API and taps the app's
# big buttons with adb. Prerequisites: emulator running, app installed, tools/vc_heartbeat.sh running.
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:${VC_PORT:-18088}
P(){ curl -s -X POST "$V$1" >/dev/null; }
tapPrimary(){ $ADB shell input tap 1280 1226; }       # big primary button (2560x1440 display)
tapSickChip(){ $ADB shell input tap 2440 140; }       # "동승자: 멀미나" chip
waitLog(){ for i in $(seq 1 80); do $ADB logcat -d | grep -q "$1" && return 0; sleep 0.5; done; return 1; }

$ADB forward tcp:${VC_PORT:-18088} tcp:8088 >/dev/null
P "/vehicle/gear?gear=Park"; P "/vehicle/controls?steering=0"; P "/vehicle/controls?brake=0"
for a in rear_left rear_right passenger; do P "/vehicle/seat?kind=occupied&area=$a&value=false"; done
for q in power=true recirc=true fan_speed=2 driver_temp=24 passenger_temp=24; do P "/vehicle/hvac?$q"; done
$ADB shell am force-stop com.mulmizero.app; $ADB logcat -c
$ADB shell am start -n com.mulmizero.app/.MainActivity --es mode live >/dev/null
sleep 4
P "/vehicle/seat?kind=occupied&area=rear_left&value=true"     # rear passenger sits down
sleep 1; P "/vehicle/gear?gear=Drive"                           # shift to D → trip starts
waitLog "동승자 케어를 켤까요" && sleep 2 && tapPrimary          # driver: 케어 모드 시작
sleep 1
for i in 1 2 3; do                                              # winding road: steering + brake
  for a in -170 -60 110 210 80 -130; do P "/vehicle/controls?steering=$a"; sleep 0.45; done
  P "/vehicle/controls?brake=65"; sleep 0.8; P "/vehicle/controls?brake=0"
done
waitLog "질문: 멀미 위험 지수" && sleep 1.5 && tapPrimary        # driver: 실내 케어 실행 (Vehicle SDK set)
sleep 3
tapSickChip                                                     # passenger: 멀미나 → max care
sleep 4; P "/vehicle/controls?steering=0"
