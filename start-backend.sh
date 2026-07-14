#!/usr/bin/env bash
# daero 백엔드 실행. 전국 GTFS(2200만 stop_times) 적재 위해 힙을 넉넉히 잡는다.
# 사용: ./start-backend.sh [gtfs경로]  (기본: data/pt_gtfs_2024.zip)
set -e
cd "$(dirname "$0")/backend"
GTFS="${1:-../data/pt_gtfs_2024.zip}"
echo "[daero] GTFS=$GTFS 로 기동 (포트 8090, 로딩 ~30초)"
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Xmx10g" \
  -Dspring-boot.run.arguments="--daero.gtfs.path=$GTFS"
