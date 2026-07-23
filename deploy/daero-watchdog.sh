#!/bin/bash
# daero 헬스 워치독 — 무응답/행(hang) 자가복구.
# systemd Restart=on-failure 는 '프로세스 종료'만 커버하므로, 프로세스는 살아있는데
# 응답만 멈춘 상태(행·데드락·GC 폭주)를 잡기 위해 HTTP 헬스를 확인해 재시작한다.
#
# 3회 연속(각 5초 간격) HTTP 200 실패 시에만 재시작 → 순간 블립·기동 중 오탐 방지.
# stats 는 timetable 로딩 전(loaded:false)에도 200 이므로, 기동 직후 오재시작 없음.
# 알림 없음(자가복구 전용) — systemd 저널(logger)에만 기록.
set -u
URL="${DAERO_HEALTH_URL:-http://localhost:8090/api/gtfs/stats}"
code=""
for i in 1 2 3; do
  code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL" || echo "000")
  if [ "$code" = "200" ]; then exit 0; fi
  sleep 5
done
logger -t daero-watchdog "health check FAILED (last http=$code) → systemctl restart daero"
systemctl restart daero
