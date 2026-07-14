# daero (대로)

**열린 대중교통 경로 엔진** — GTFS 표준 데이터로 국내 대중교통(버스·지하철·철도·여객선)
door-to-door 경로를 탐색하는 오픈 API 서버. 자체 **RAPTOR** 알고리즘, **API 키 불필요**, 자가호스팅.

## 빠른 시작
```bash
./start-backend.sh          # 포트 8090 (GTFS 적재 ~30초)
```
- GTFS 경로: `application.properties` 의 `daero.gtfs.path`(디렉토리 또는 .zip). 없으면 빈 피드로 기동.
- 웹 UI: 브라우저에서 `http://localhost:8090/` — 지도에서 출발·도착 클릭.

---

## Open API

Base URL: `http://<host>:8090`  ·  인증: **없음(공개)**  ·  응답: `application/json; UTF-8`

### `GET /api/plan/coords` — 좌표 door-to-door 경로
| 파라미터 | 타입 | 설명 |
|---|---|---|
| `fromLat`, `fromLon` | double | 출발 좌표 |
| `toLat`, `toLon` | double | 도착 좌표 |
| `time` | string | 출발 시각 `HH:MM` (기본 `08:00`) |

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
### `GET /api/gtfs/stats` · `GET /api/gtfs/timetable` — 적재/빌드 상태

---

## 배포 메모
- **API 키 불필요.** 서버는 로컬 GTFS/스냅샷 파일만 사용(외부 유료 API 없음). 지도 타일·Leaflet 은 클라이언트가 OpenStreetMap 공개 타일에서 로드.
- **응답**: 쿼리 ~100~200ms. 인메모리·무상태 → 수평 확장 용이.
- **공개 시 고려**: 인증이 없으므로 앞단에 rate limit/프록시 권장. 대량 트래픽이면 지도 타일은 자체/유료 타일 사용.

### 메모리·prebuild (권장 배포)
전국 GTFS를 매번 파싱하면 기동 시 메모리 피크(~3GB)가 커서 큰 인스턴스가 필요하다.
대신 **Timetable을 한 번 빌드해 바이너리 스냅샷(`data/timetable.bin`, ~206MB)으로 저장** → 운영 서버는 이 파일만 로드하면 된다.
- 스냅샷 로드 모드 실측: 기동 1초대, **RSS ~700MB**(`-Xmx1500m`) → **2GB 인스턴스로 전국 구동**.
- 워크플로우: ① 여유 머신에서 `./start-backend.sh` 1회 실행 → `data/timetable.bin` 생성. ② 그 `.bin`만 서버로 복사(GTFS zip 불필요). ③ 서버에서 `./start-prod.sh` 실행(스냅샷 자동 로드).
- 빌드 머신(스냅샷 없을 때)은 `-Xmx4g` 이상 권장, 운영(스냅샷 로드)은 `-Xmx1.5g`면 충분.

## 데이터
- GTFS 표준(정류장·노선·운행·시각표·환승). 출처는 공개 GTFS(예: 국가교통DB 전국, 지자체).
- 연 단위 스냅샷(정적) 기반 — 실시간 지연은 미반영, "예상 경로·소요시간" 용도.
- 요금은 도시 통합요금 근사(철도·항공 실요금 별도), 경로선은 정류장 직선(선형 데이터 없음).

## 스택
Java 17 · Spring Boot 3.2 (Maven) · Leaflet(정적 페이지, 빌드 없음). 설계·진행: [`PLAN.md`](./PLAN.md).

## 라이선스
**듀얼 라이선스** — 오픈소스는 **AGPL-3.0**([`LICENSE`](./LICENSE)), 상용은 별도 계약.
자세한 내용: [`LICENSING.md`](./LICENSING.md). (AGPL: 네트워크 서비스로 제공 시 소스 공개 의무)

## 데이터·지도 출처 표기
- 지도 타일: © OpenStreetMap 기여자.
- 대중교통 데이터: 공개 GTFS 데이터 제공처 표기 준수.
