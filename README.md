# daero (대로)

**열린 대중교통 경로 엔진** — GTFS 표준 데이터로 국내 대중교통(버스·지하철·철도·여객선)
door-to-door 경로를 탐색하는 오픈 API 서버. 자체 **RAPTOR** 알고리즘, **API 키 불필요**, 자가호스팅.

## 빠른 시작
```bash
./start-backend.sh          # 포트 8090 (GTFS 적재 ~30초)
cd backend && ./mvnw test   # 회귀 테스트(미니 GTFS 기반, 운영 데이터 불필요)
```
- GTFS 경로: `application.properties` 의 `daero.gtfs.path`(디렉토리 또는 .zip). 없으면 빈 피드로 기동.
- 웹 UI: 브라우저에서 `http://localhost:8090/` — 지도 클릭 또는 **정류장 이름 검색**으로 출발·도착 지정.
- Docker: `docker compose up -d --build` (스냅샷 `data/timetable.bin` 을 볼륨 마운트). 상세 [`deploy/OPERATIONS.md`](./deploy/OPERATIONS.md).

---

## Open API

Base URL: `http://<host>:8090`  ·  인증: **없음(공개)**  ·  응답: `application/json; UTF-8`

### `GET /api/plan/coords` — 좌표 door-to-door 경로
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `fromLat`, `fromLon` | double | 출발 좌표 |
| `toLat`, `toLon` | double | 도착 좌표 |
| `time` | string | 출발 시각 `HH:MM` (미지정 시 현재 시각 KST) |
| `alternatives` | int | 대안 경로 수 0~10 (기본 1) |

```jsonc
{
  "found": true,
  "queryMs": 140,
  "tag": "추천", "departure": "09:00", "arrival": "09:29",
  "durationMin": 29, "transfers": 1, "estimatedFareKrw": 1350,
  "modes": ["BUS"],
  "legs": [
    { "mode": "BUS", "route": "262", "from": "서울역버스환승센터", "to": "종로2가",
      "depart": "09:01", "arrive": "09:07", "min": 6,
      "fromLat": 37.55, "fromLon": 126.97, "toLat": 37.57, "toLon": 126.98 }
  ],
  "options": [ /* 추천 / 지하철·철도 우선 / 환승 최소 — 각 객체는 위와 동일 구조 + tag */ ]
}
```
- `mode`: `BUS` | `SUBWAY` | `RAIL` | `AIR` | `FERRY` | `WALK`
- `options`: 트레이드오프가 다른 대안 경로 목록(추천·지하철철도 우선·환승 최소). 최상위 필드는 추천 경로와 동일(하위호환).

### `GET /api/plan` — 정류장 ID 간 경로
`fromStop`, `toStop`(정류장 ID), `time`. 응답은 위와 동일.

### `GET /api/plan/search?q=이름&limit=20` — 정류장 검색
### `GET /api/plan/near?lat=&lon=&radius=800` — 좌표 근처 정류장(모드 접두어 포함)
### `GET /api/realtime?cityCode=&nodeId=` — 실시간 버스 도착(TAGO)
정류소의 실시간 도착(`arrivals[]`: `routeNo`·`routeType`·`stopsLeft`·`etaSec`·`etaMin`). 경로탐색 응답에도 추천 경로 첫 버스 leg에 자동 부착(`realtimeMin`). BIS 커버 도시만 제공. 인증키(`TAGO_KEY`) 미설정 시 비활성(경로탐색은 정상).
### `GET /api/gtfs/stats` · `GET /api/gtfs/timetable` — 적재/빌드 상태

### 에러 응답 · 레이트리밋
오류 시 일관된 JSON `{ "error": "코드", "message": "설명" }`.

| 상태 | error | 조건 |
|---|---|---|
| 400 | `missing_parameter` / `invalid_parameter` / `invalid_input` | 파라미터 누락 / 타입 오류 / 값 검증 실패(좌표 범위·time 형식·빈 검색어) |
| 429 | `rate_limited` | IP당 요청 한도 초과(`Retry-After` 후 재시도) |
| 503 | `server_busy` | 동시 경로탐색 혼잡(대기 초과) |

공개 API 보호를 위해 **IP당 레이트리밋**(토큰버킷)과 **동시 경로탐색 상한**(CPU·메모리 보호)이 적용됩니다.

---

## 배포 메모
- **API 키 불필요.** 서버는 로컬 GTFS/스냅샷 파일만 사용(외부 유료 API 없음). 지도 타일·Leaflet 은 클라이언트가 OpenStreetMap 공개 타일에서 로드.
- **응답**: 쿼리 ~100~200ms. 인메모리·무상태 → 수평 확장 용이.
- **남용 방어 내장**: IP당 레이트리밋(토큰버킷)·동시 경로탐색 상한·입력 검증·전역 예외 처리 포함. 헬스 워치독(systemd 타이머)이 무응답 시 자동 재시작.
- **운영 가이드**: 배포·워치독·인증서 자동갱신·백업/복구 절차는 [`deploy/OPERATIONS.md`](./deploy/OPERATIONS.md).

### 운영 배포 (jar + prebuild 스냅샷)
전국 GTFS를 매번 파싱하면 기동 메모리 피크(~3GB)가 크다. 대신 **Timetable을 한 번 빌드해
바이너리 스냅샷(`data/timetable.bin`, ~206MB)** 으로 저장 → 운영 서버는 이 파일만 로드한다.
스냅샷은 **플랫폼 무관**(JVM 표준 포맷, OS·아키텍처 상관없이 동일 로드).

**빌드(여유 머신에서 1회)**
```bash
./start-backend.sh                       # GTFS로 빌드 → data/timetable.bin 생성
cd backend && ./mvnw clean package -DskipTests   # 실행 jar 생성(target/*.jar)
```
**배포**
- jar(`backend/target/daero-backend-*.jar`, ~21MB)와 스냅샷(`data/timetable.bin`, ~206MB)을 서버로.
- 스냅샷은 100MB 초과라 git 커밋 불가 → **GitHub Release 자산**으로 배포 권장.

**운영 실행 (jar, 저메모리)**
```bash
./run.sh              # java -jar, 기본 -Xmx700m (스냅샷 로드 → GTFS 불필요)
XMX=1500m ./run.sh    # 2GB 인스턴스면 여유롭게
```
**실측(전국, 스냅샷 로드)**: 기동 ~1초, 쿼리 ~150ms.
- `-Xmx700m` → **RSS ~605MB** → **1GB 인스턴스로 구동 가능**(저트래픽 권장).
- `-Xmx1500m` → RSS ~715MB → **2GB 인스턴스 여유**(공개·트래픽 대비 권장).

## 데이터
- GTFS 표준(정류장·노선·운행·시각표·환승). 출처는 공개 GTFS(예: 국가교통DB 전국, 지자체).
- 연 단위 스냅샷(정적) 기반 — 실시간 지연은 미반영, "예상 경로·소요시간" 용도.
- 요금은 도시 통합요금 근사(철도·항공 실요금 별도), 경로선은 정류장 직선(선형 데이터 없음).

## 스택
Java 17 · Spring Boot 3.2 (Maven) · Leaflet(정적 페이지, 빌드 없음) · Docker. 회귀 테스트 `./mvnw test`. 설계·진행: [`PLAN.md`](./PLAN.md).

## 라이선스
**듀얼 라이선스** — 오픈소스는 **AGPL-3.0**([`LICENSE`](./LICENSE)), 상용은 별도 계약.
자세한 내용: [`LICENSING.md`](./LICENSING.md). (AGPL: 네트워크 서비스로 제공 시 소스 공개 의무)

## 데이터·지도 출처 표기
- 지도 타일: © OpenStreetMap 기여자.
- 대중교통 데이터: 공개 GTFS 데이터 제공처 표기 준수.
