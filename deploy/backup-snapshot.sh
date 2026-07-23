#!/bin/bash
# daero 데이터 스냅샷 백업 — timetable.bin(재빌드 가능하나 서버엔 GTFS 원본 없음) + bus_stops.csv.
# 타임스탬프 사본을 ~/daero/backup 에 두고 최근 N개만 보관. 크론/수동 실행.
set -eu
DATA="${DAERO_DATA:-/home/ubuntu/daero/data}"
DEST="${DAERO_BACKUP:-/home/ubuntu/daero/backup}"
KEEP="${DAERO_BACKUP_KEEP:-3}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$DEST"
for f in timetable.bin bus_stops.csv; do
  if [ -f "$DATA/$f" ]; then
    cp -p "$DATA/$f" "$DEST/${f%.bin}.${STAMP}.bak" 2>/dev/null || cp -p "$DATA/$f" "$DEST/${f}.${STAMP}.bak"
  fi
done

# 오래된 백업 정리(파일별 최근 KEEP개 유지)
for base in timetable bus_stops.csv; do
  ls -1t "$DEST/${base}".*.bak 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
done
echo "[backup] $STAMP → $DEST (최근 ${KEEP}개 유지)"
ls -lh "$DEST" 2>/dev/null | tail -n +2
