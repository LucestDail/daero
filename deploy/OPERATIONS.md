# daero 운영 가이드

프로덕션: **https://daero.duckdns.org** (AWS Lightsail 2GB, Ubuntu 24.04, `ubuntu@43.203.124.186`).
구성: systemd(`daero.service`, `-Xmx1500m`) + nginx 리버스 프록시(443) + Let's Encrypt + prebuild 스냅샷(`~/daero/data/timetable.bin`).

## 배포 (jar 교체)
로컬에서:
```bash
cd backend && ./mvnw clean package -DskipTests
scp -i <key> target/daero-backend-0.1.0-SNAPSHOT.jar ubuntu@<ip>:~/daero/daero.jar.new
ssh -i <key> ubuntu@<ip> 'cd ~/daero && cp daero.jar daero.jar.bak && mv daero.jar.new daero.jar && sudo systemctl restart daero'
```
- timetable 은 ApplicationReady **이후 ~3초 비동기 로드** → 재시작 직후 `/api/gtfs/stats` 가 잠깐 `loaded:false`로 보이는 건 정상. 수 초 뒤 `loaded:true`.
- 롤백: `mv daero.jar.bak daero.jar && sudo systemctl restart daero`.

## 자가복구 워치독 (행·무응답 자동 재시작)
`daero.service` 의 `Restart=on-failure` 는 프로세스 종료만 커버. 프로세스는 살아있으나 응답이 멈춘
상태(행·GC 폭주)는 **워치독**이 헬스체크로 잡아 재시작한다(2분 주기, 3회 연속 실패 시). 알림 없음.

설치(최초 1회):
```bash
# repo deploy/ 를 서버 ~/daero/deploy/ 로 복사한 뒤
chmod +x ~/daero/deploy/daero-watchdog.sh ~/daero/deploy/backup-snapshot.sh
sudo cp ~/daero/deploy/daero-watchdog.service /etc/systemd/system/
sudo cp ~/daero/deploy/daero-watchdog.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now daero-watchdog.timer
```
확인: `systemctl list-timers daero-watchdog.timer` · 동작 로그 `journalctl -t daero-watchdog`.

## 인증서 (Let's Encrypt 자동 갱신 + nginx 자동 반영)
- certbot.timer 가 만료 30일 전 자동 갱신. 갱신 성공 시
  `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`(`systemctl reload nginx`)가 자동 실행되어
  **새 인증서가 즉시 반영**된다(수동 SSL 블록 구성이라 reload 필수 — 이 훅으로 해결).
- 점검: `sudo certbot renew --dry-run` (성공해야 정상). 서빙 인증서 확인은 **서버 내부**에서
  `echo | openssl s_client -connect 127.0.0.1:443 | openssl x509 -noout -enddate`
  (회사망 등 Zscaler TLS 검사 환경에선 외부에서 본 만료일이 가짜일 수 있음 → 서버 내부에서 확인).

## 백업 / 복구 / 재빌드
서버엔 GTFS 원본이 없고 **스냅샷(`timetable.bin`)과 `bus_stops.csv`만** 상주.
- 백업: `~/daero/deploy/backup-snapshot.sh` → `~/daero/backup/` 에 타임스탬프 사본(최근 3개). 크론 예:
  `0 4 * * 0 /home/ubuntu/daero/deploy/backup-snapshot.sh`(매주 일 04:00).
- 스냅샷 손상/유실 복구: 백업본을 `~/daero/data/timetable.bin` 로 복원 후 `sudo systemctl restart daero`.
- 스냅샷 **재빌드**(데이터 갱신 시)는 GTFS 원본이 있는 로컬/개발 머신에서:
  `./start-backend.sh`(GTFS 로드→빌드→`data/timetable.bin` 저장) → 새 `timetable.bin` 을 서버로 scp.
