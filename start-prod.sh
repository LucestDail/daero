#!/usr/bin/env bash
# 운영 실행: prebuild 스냅샷(data/timetable.bin)이 있으면 GTFS 없이 로드 → 2GB 인스턴스에서 구동.
# 스냅샷이 없으면 최초 1회 GTFS로 빌드 후 스냅샷을 저장하므로, 그 최초 실행은 메모리 여유 있는 머신에서.
set -e
cd "$(dirname "$0")/backend"
XMX="${1:-1500m}"
echo "[daero] 운영 기동 (-Xmx$XMX, 포트 8090)"
echo "  · data/timetable.bin 있으면 스냅샷 로드(저메모리), 없으면 GTFS 빌드+스냅샷 저장"
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Xmx$XMX" \
  -Dspring-boot.run.arguments="--daero.gtfs.path=../data/pt_gtfs_2024.zip --daero.timetable.bin=../data/timetable.bin"
