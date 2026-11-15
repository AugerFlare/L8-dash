#!/bin/sh
# Start L8 Dash's read-only shell helper on the car (needed once per car boot).
# Usage: sh start-helper.sh [ip:port]   (default 192.168.50.161:5555)
export MSYS_NO_PATHCONV=1
CAR=${1:-192.168.50.161:5555}
adb connect "$CAR" >/dev/null
adb -s "$CAR" shell '
  T=/data/local/tmp/l8dash-helper
  [ -f $T.pid ] && kill $(cat $T.pid) 2>/dev/null
  apk=$(pm path com.l8dash | cut -d: -f2)
  uid=$(dumpsys package com.l8dash | sed -n "s/.*userId=\([0-9]*\).*/\1/p" | head -n 1)
  CLASSPATH=$apk setsid nohup app_process /system/bin com.l8dash.ShellHelper $uid </dev/null >$T.log 2>&1 &
  echo $! > $T.pid
  sleep 1; cat $T.log'
