#!/usr/bin/env bash
# 운영 실행(패키징된 jar). 개발용 start-*.sh(mvnw) 와 달리 java -jar 로 바로 구동.
#
# 준비:
#   1) 빌드(한 번): cd backend && ./mvnw clean package -DskipTests
#   2) 스냅샷 준비: data/timetable.bin (여유 머신에서 ./start-backend.sh 로 생성 후 복사,
#      또는 GitHub Release 등에서 내려받기). 스냅샷이 있으면 GTFS 없이 저메모리로 기동.
#   3) 실행: ./run.sh            (기본 -Xmx700m, 1GB 인스턴스 목표)
#            XMX=1500m ./run.sh  (2GB 인스턴스면 여유롭게)
set -e
cd "$(dirname "$0")"
XMX="${XMX:-700m}"
JAR=$(ls backend/target/daero-backend-*.jar 2>/dev/null | grep -v original | head -1)
[ -z "$JAR" ] && { echo "jar 없음 → 먼저 빌드: (cd backend && ./mvnw clean package -DskipTests)"; exit 1; }
GTFS="${GTFS:-data/pt_gtfs_2024.zip}"
BIN="${BIN:-data/timetable.bin}"
echo "[daero] java -jar (-Xmx$XMX) · bin=$BIN · 포트 8090"
exec java -Xmx"$XMX" -jar "$JAR" \
  --daero.gtfs.path="$GTFS" \
  --daero.timetable.bin="$BIN"
