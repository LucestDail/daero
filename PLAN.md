# daero (대로) — 설계 및 로드맵

> 국내 대중교통(버스·지하철·철도·여객선) **door-to-door 경로탐색 엔진 + API**를 자유소프트웨어로.
> GTFS 표준 데이터 → 자체 **RAPTOR** 알고리즘 → 경로·환승·소요시간·요금.
> API/사용법은 [`README.md`](./README.md)가 authoritative. 이 문서는 설계 의도와 로드맵.

## 설계 결정
| 항목 | 결정 | 근거 |
|---|---|---|
| 코어 엔진 | 직접 RAPTOR 구현 | 자유SW 가치·완전한 제어·경량 (외부 엔진 래핑 아님) |
| 데이터 | 전국 GTFS(KTDB) | 커버리지 최상 |
| 스택 | Java 17 / Spring Boot 3.2 + Leaflet 정적 UI | RAPTOR도 Java로 충분, UI는 빌드 없이 간단히 |
| 라이선스 | **AGPL-3.0 + 상용 듀얼** | 네트워크 서비스 카피레프트 유지 + 상용 여지 ([`LICENSING.md`](./LICENSING.md)) |

## 아키텍처
```
GTFS(zip/dir)
  → GtfsLoader        CSV 파싱 → GtfsFeed(원본 도메인 모델)
  → TimetableBuilder  컬럼형 int[] 자료구조: 정차패턴 그룹·시각 평면배열·정류장↔패턴 역인덱스
                      · 좌표 그리드(근접 정류장)· 도보환승 사전계산
  → RaptorRouter      라운드 기반 경로탐색(다기준: 도착시각+환승), 경로 복원, 좌표 door-to-door
  → REST API          JSON(추천 + 대안 경로) → Leaflet UI / 외부 클라이언트
```

### 왜 RAPTOR
대중교통은 시간의존 그래프라 일반 Dijkstra가 비효율. RAPTOR는 라운드(=환승 횟수)마다 노선을
스캔해 각 정류장 최선 도착시각을 갱신 → (도착시각, 환승수) 다중기준 파레토를 빠르게 산출.
전처리 그래프 불필요. (필요 시 CSA/transfer-patterns로 보강 여지)

## 데이터 규칙 (KTDB GTFS)
- **route_id 접두어로 모드 판정**: `BR`=버스, `SR`=연안해운, `RR`=철도(route_type 1이면 도시철도/지하철), `AR`=항공.
  (route_type이 비표준 — 버스=0 — 이라 접두어를 우선 사용)
- 지하철역이 성겨(전국 도시철도) 접근 반경 1200m·도보환승 350m로 넉넉히.
- 스냅샷은 연 단위(정적) → 실시간 지연 미반영. "예상 경로·소요시간" 용도.

## 마일스톤
- **M0~M6 완료**: GTFS 로더 → 컬럼형 Timetable → 다기준 RAPTOR(경로 복원·좌표 door-to-door·요금 근사·대안 경로) → Leaflet UI.
  - 규모: 전국 stops 22만·trips 37만·stop_times 2189만·패턴 3.3만. 적재 ~30초, 쿼리 ~100~200ms, 힙 ~2GB.
- **M7 완료 — 배포·공개**: 실배포 **https://daero.duckdns.org** (Lightsail 2GB, systemd+nginx+Let's Encrypt, prebuild 스냅샷). Docker 이미지(멀티스테이지 + compose). 정류장 이름 검색 UI. 실시간 버스도착(TAGO/서울) 연동(`/api/realtime` + leg 부착). SEO(robots/sitemap/OG).
- **M8 완료 — 완성도(운영제품화)**: 회귀 테스트(로더·RAPTOR·API 계약, `./mvnw test`), 전역 예외 처리 + 입력 검증, IP당 레이트리밋, 동시 경로탐색 게이트(CPU·메모리 보호), 헬스 워치독(자가복구), 백업/복구 절차([`deploy/OPERATIONS.md`](./deploy/OPERATIONS.md)), UX 에러·로딩 마감.
- **후보(선택)**: 온프레미스 오프라인 지도(OSM mbtiles), shapes 실선형, 철도·항공 실요금, waynai 등 외부 서비스 연계.

## 알려진 한계 / 후속
- 요금은 도시 통합요금 **근사**(철도·항공 실요금 미반영, 수도권 외 규칙 상이).
- 경로선은 정류장 직선(GTFS shapes 없음 → 실제 선형 아님).
- 버스 stop_times가 스케줄 기반(정체 미반영) → 버스가 도착시각상 낙관적일 수 있음. 실시간(TAGO/서울)은 추천 경로 첫 버스 leg에 보조 부착.
- 쿼리당 라벨 배열(~40MB)은 **재사용 풀 + 동시 실행 게이트**로 관리(2vCPU 처리량 상한 자체는 코어 증설 필요).
- 향후(선택): shapes 기반 실선형, 요금 정교화, 오프라인 지도, 외부 서비스 연계.

## 구현 노트 (재현 시 주의)
- KTDB zip 엔트리명이 CP949 → `ZipFile(file, ISO_8859_1)` 로 열고, GTFS가 하위폴더에 있어 접미어 매칭.
- stop_times 2천만 행 → stopId/tripId 인터닝 + 컬럼형 int 배열로 상주(객체 리스트 지양).
- 경로 공백 있는 파일명은 인자 파싱이 깨지므로 공백 없는 심볼릭 링크 사용.
