# daero (대로) — 열린 대중교통 경로 엔진

> 국내 대중교통(버스·지하철·철도) **door-to-door 경로탐색 엔진 + API**를 자유소프트웨어로 만든다.
> GTFS 표준 데이터를 먹여 자체 **RAPTOR** 알고리즘으로 환승·소요시간·요금을 계산한다.
> 누구나 자가호스팅해서 쓸 수 있는 오픈 경로탐색 엔진을 지향한다.

## 왜 만드나 (동기)

- 국내 **GTFS 표준 데이터가 공개**(KTDB 국가교통DB 전국, 서울·경기 등 지자체)돼 있어, 엔진만 있으면 자가호스팅 가능.
- 경로탐색 알고리즘(RAPTOR/CSA)은 논문·레퍼런스가 풍부 → **직접 구현이 현실적**이고, 이게 프로젝트의 핵심 자산.
- 자가호스팅·자유SW로 만들면 요금 규칙·데이터 소스를 유연하게 바꿔가며 쓸 수 있다.

## 결정 사항 (2026-07-14)

| 항목 | 결정 | 근거 |
|---|---|---|
| 코어 엔진 | **직접 RAPTOR 구현** | 자유SW 가치·완전한 제어·경량. OTP 래핑 아님. |
| 초기 데이터 | **전국 (KTDB GTFS)** | 커버리지 최상. 정보공개 절차·대용량은 감수. |
| 스택 | **Java 17 / Spring Boot 3.2** + Leaflet 웹UI | RAPTOR도 Java로 충분. UI는 정적 Leaflet 페이지로 간단히. |
| 라이선스 | (예정) AGPL-3.0 또는 MIT | 자유SW 지향. 서버형이면 AGPL 검토. |

## 아키텍처 개요

```
GTFS (zip/dir; KTDB 전국)
  → [GtfsLoader] CSV 파싱 → GtfsFeed (원본 도메인 모델)
  → [TimetableBuilder] RAPTOR용 컬럼형(compact) 자료구조로 변환
      - routes[] : 정차 패턴(stop pattern) 단위로 그룹
      - stopTimes[] : trip별 도착/출발 시각 평면 배열
      - transfers[] : 정류장 간 도보 환승(사전계산)
  → [RaptorRouter] 라운드 기반 경로탐색 (좌표/정류장 → 좌표/정류장)
  → [FareCalculator] 수도권 통합요금 등 요금 규칙
  → REST API (/plan) → JSON (경로·구간·소요시간·요금·환승)
                     → 웹 UI / 외부 클라이언트 소비
```

### 왜 RAPTOR
- 대중교통 경로탐색은 **시간의존(time-dependent)** 그래프라 일반 Dijkstra가 비효율.
- RAPTOR(Round-bAsed Public Transit Optimized Router): 환승 횟수 = 라운드. 라운드마다 모든 노선을 스캔해 각 정류장 최선 도착시각을 갱신 → **다중기준(도착시각·환승수) 파레토 최적**을 빠르게 산출. 전처리 그래프 불필요, 갱신 용이.
- 대안 CSA(Connection Scan)는 단일 쿼리 단순하지만 다중기준·범위쿼리에서 RAPTOR가 유리. 우선 RAPTOR, 필요 시 CSA/transfer patterns 보강.

## 데이터 모델 (GTFS 핵심 파일)

| 파일 | 도메인 | 비고 |
|---|---|---|
| stops.txt | Stop(id, name, lat, lon) | 좌표 → 근접 정류장 탐색(공간 인덱스) |
| routes.txt | Route(id, shortName, type) | type: 버스/지하철/철도 |
| trips.txt | Trip(id, routeId, serviceId) | 운행 1회 |
| stop_times.txt | StopTime(tripId, seq, arr, dep, stopId) | **가장 큼(수백만 행)** → 컬럼형 저장 |
| calendar.txt / calendar_dates.txt | ServiceCalendar | 요일·예외일 → 특정 날짜 운행여부 |
| transfers.txt | Transfer(from, to, minTime) | 없으면 좌표기반 도보환승 사전계산 |

## 성능·규모 고려 (전국)
- stop_times 수백만 행 → 객체 리스트로 들면 GC/RAM 폭발. **RAPTOR 단계에서 int 배열 컬럼형**(도착/출발=자정기준 초, stopId=정수 인덱스)으로 변환해 상주.
- 정류장 좌표 근접탐색: k-d tree 또는 grid 공간 인덱스.
- 목표: 단일 쿼리 수십 ms, 전국 그래프 상주 RAM 수 GB 이내.

## 마일스톤

- **M0 — 골격**(완료 2026-07-14): 프로젝트 구조, GTFS 도메인 모델, CSV 로더, 로딩 통계 엔드포인트.
- **M1 — 로더 완성 + 전국 실적재**(완료 2026-07-14): KTDB 전국 GTFS(2024기준) 로딩 성공.
  - 실적재: **stops 220,577 · routes 30,895 · trips 374,301 · stop_times 21,889,865 · transfers 286**, 로딩 32.8초, 힙 -Xmx6g.
  - 실전 교훈: (1) zip 엔트리명 CP949 → `ZipFile(file, ISO_8859_1)` 로 "invalid CEN header" 회피. (2) GTFS 가 zip 하위폴더(`202403_GTFS_DataSet/`)에 있어 접미어 매칭 필요. (3) 경로 공백 → 공백없는 심볼릭 링크. (4) stopId/tripId 인터닝으로 메모리 급감. (5) **route_type 비표준(버스=0) → route_id 접두어 BR/SR/RR/AR 로 모드 판정.** (6) **calendars=1** — KTDB 는 요일 구분 없는 대표 스케줄(전 요일 운행, 2017~2030).
- **M2 — Timetable 빌드**(완료 2026-07-14): 컬럼형(`raptor/Timetable`)+정차패턴 그룹+좌표 그리드+도보환승 사전계산. `TimetableBuilder`가 ApplicationReady 후 빌드. 실적: **패턴 33,071 · 정차엔트리 2189만 · 환승엣지 571,065**(GTFS 286+좌표기반 근접 200m), 빌드 3.2초.
- **M3 — RAPTOR MVP**(완료 2026-07-14): `raptor/RaptorRouter`(라운드=환승, 패턴스캔+이분탐색 승차+도보환승). `GET /api/plan?fromStop=&toStop=&time=` + `/api/plan/search?q=`. 검증: **서울역→강남역 34분·환승1·44ms**, **서울역→부산역 197분(KTX)·환승1·77ms**. 정류장→정류장 최소도착시각.
- **M3b — 경로 복원**(완료 2026-07-14): 부모포인터로 leg(mode·노선명·승하차 정류장·시각) 재구성 + 연속 도보 병합.
- **M4 — 좌표 door-to-door**(완료 2026-07-14): `GET /api/plan/coords?fromLat=&fromLon=&toLat=&toLon=` — 좌표→근접정류장(반경 800m, Timetable.nearestStops)→RAPTOR→하차도보. 검증: 시청→강남역 25분·환승1·119ms.
- **M5 — 다기준·요금**(완료 2026-07-14): **다기준 RAPTOR**(라운드별 라벨 arrK[k] + 환승 페널티 5분 → 도착+환승 파레토에서 합리적 여정 선택). 환승난발(서울역→강남 5회→1회) 해결. 수도권 통합요금 근사(기본1250+10km초과 5km당100). 응답에 legs 포함.
- **M6 — API/UI**(완료 2026-07-14): REST(`/api/plan`·`/api/plan/coords`·`/search`·`/near`·`/gtfs/*`) + Leaflet 지도 UI(구간별 색·지점 노드·시각·옵션 카드). 대안 경로(추천·지하철철도우선·환승최소).
- **M7 — 배포·자유SW**(미구현/다음): Docker, 상주 배포, 라이선스 확정, 오픈 API 공개·문서.

> 구현 현황: **M0~M6 완료**. M7(배포/라이선스/공개)만 남음. 요금은 도시 통합요금 근사(철도·항공 실요금 미반영), 폴리라인은 정류장 직선(shapes 없음) — 기능이라기보단 알려진 한계.

## 데이터 규칙(KTDB GTFS) — route_id 접두어
- **BR**=버스, **SR**=연안해운(여객선·선착장), **RR**=철도(route_type 1이면 도시철도/지하철, 그 외 일반철도), **AR**=항공.
- ⚠️ route_type 비표준(버스=0). 모드 판정은 접두어+type. (초기에 SR을 지하철로 오인 → 정정)
- 지하철역은 성겨(전국 도시철도) 접근/환승 반경을 넉넉히: 접근 1200m, 도보환승 350m.
- 참고: 버스 stop_times 는 스케줄 기반(정체 미반영) → 버스가 도착시각상 낙관적으로 나올 수 있음. "지하철 우선" 대안은 모드 선호 기준으로 추후.

## 대안 경로(파레토)
- `journeys()` 가 라운드별 파레토(환승↔소요시간)를 반환 → API 는 추천(환승 페널티 최소) + 대안 최대 2개. Leaflet UI 에서 옵션 카드로 전환.

## 알려진 정리거리(후속)
- 쿼리당 라벨 배열 48MB 할당(6라운드×6배열×220k) → 재사용 풀로 최적화 여지. 현재 100~190ms.
- 요금: 철도·항공 실요금 미반영(근사만). 수도권 외 통합요금 규칙 상이.
- "지하철 우선" 등 모드 선호 대안, shapes.txt 없어 폴리라인은 정류장 직선(실제 선형 아님).

## 데이터 확보 (M1 병행) — 3트랙

**트랙 A — KTDB 전국 GTFS (공식 전국, 목표)**
- ktdb.go.kr → 정보공개 › 자료신청 › 교통분석자료 신청 → 교통망 GIS DB › 대중교통 › 대중교통(지역별) 전국.
- 2025-05-30 제공 개시, 2023-03 기준. 전국 시내·마을버스·도시철도·철도·시외·고속·공항리무진·국내선 항공. 정차지/노선기본/운행회차/회차별 도착·출발시각표(≈GTFS).
- ⚠️ 공식 안내: "접근성 지표 분석용 내부 **파일럿** 자료, 배포 목적 아님, **ID 체계 비표준화**" → 순수 GTFS zip이 아닐 수 있음 → **정규화 어댑터 필요 가능성**. 신청형(며칠). 무료·무기한.

**트랙 B — 지자체/공개 피드 (즉시 착수용) ⭐**
- Mobility Database(mobilitydatabase.org/feeds)에서 "Korea" 필터 → 공개 피드 있으면 zip URL 즉시 다운로드.
- 서울 열린데이터광장·경기데이터드림 등 지자체 GTFS. 전국 아니어도 수도권으로 M2~M3 엔진 개발 가능.

**트랙 C — data.go.kr TAGO API 자체 조립 (백업)**
- TAGO 버스노선·정류장·경유정류장 API로 GTFS 생성. 시각표 완전성 불확실·작업량 큼. A 부실 시 대안.

**전략**: 지금 B로 수도권 확보해 엔진 개발 → 병행해 A 신청 → 도착 시 ID 정규화 후 전국 확장.
- GTFS 스냅샷은 연 단위 → 실시간 아님. "예상 경로·소요시간" 용도라 허용.
- 로더는 dir/zip 모두 지원 → `daero/data/gtfs/`에 넣고 재기동, `/api/gtfs/stats`로 파서 호환 즉시 검증.

## API 초안 (확정 전)
```
GET /api/plan?fromLat=&fromLon=&toLat=&toLon=&date=YYYYMMDD&time=HHMM
→ { itineraries: [ { totalMin, transfers, fareKrw, legs: [
     { mode: BUS|SUBWAY|RAIL|WALK, routeName, from, to, departAt, arriveAt, stops[] } ] } ] }
GET /api/gtfs/stats   → 로딩된 stop/route/trip/stopTime 카운트 (헬스/검증)
GET /actuator/health
```

## 열린 질문
- 라이선스 최종(AGPL vs MIT).
- 실시간(도착정보) 결합 여부 — 초기엔 정적 GTFS만.
- 전국 요금 규칙 범위(수도권 외 지역 통합요금 상이).
