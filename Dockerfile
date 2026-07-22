# syntax=docker/dockerfile:1
# daero — 국내 대중교통 경로탐색 엔진 (M7 컨테이너 이미지)
# 멀티스테이지: Maven 빌드 → JRE 런타임. 스냅샷(timetable.bin)은 이미지에 굽지 않고
# 런타임에 볼륨(/app/data)으로 마운트한다(용량 206MB·데이터/코드 분리).

# ── build ──
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src/backend
COPY backend/pom.xml pom.xml
COPY backend/.mvn .mvn
# 의존성 레이어 캐시(소스 변경 시 재다운로드 회피). 오프라인 실패해도 무시하고 진행.
RUN mvn -q -B -DskipTests dependency:go-offline || true
COPY backend/src src
RUN mvn -q -B -DskipTests clean package \
 && cp target/*.jar /app.jar

# ── run ──
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app.jar app.jar

# JVM 힙: 2GB 인스턴스 기준 1500m(스냅샷 로드형). 필요 시 compose/run 에서 덮어쓰기.
ENV JAVA_OPTS="-Xmx1500m"
# Spring relaxed binding: DAERO_TIMETABLE_BIN → daero.timetable.bin, DAERO_GTFS_PATH → daero.gtfs.path
ENV DAERO_TIMETABLE_BIN="/app/data/timetable.bin"
ENV DAERO_GTFS_PATH=""

EXPOSE 8090
# 스냅샷이 있으면 GTFS 없이 로드. 실시간 키(TAGO_KEY/SEOUL_BUS_KEY)는 환경변수로 주입.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
