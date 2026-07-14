package com.daero.gtfs.model;

/**
 * GTFS routes.txt — 노선.
 * <p>KTDB 데이터 규칙: **route_id 접두어 + route_type** 으로 모드 판정.
 * BR=버스, SR=연안해운(여객선), AR=항공, RR=철도(route_type 1이면 도시철도/지하철, 그 외 일반철도).
 * 접두어가 없으면 route_type 로 폴백.
 */
public record Route(String id, String shortName, String longName, int type) {

    public Mode mode() {
        if (id != null && id.length() >= 2) {
            switch (id.substring(0, 2)) {
                case "BR": return Mode.BUS;
                case "SR": return Mode.FERRY;      // 연안해운(선착장)
                case "AR": return Mode.AIR;
                case "RR": return type == 1 ? Mode.SUBWAY : Mode.RAIL; // 1=도시철도/전철
                default: break;
            }
        }
        return switch (type) {   // GTFS 표준 route_type 폴백
            case 0 -> Mode.BUS;
            case 1 -> Mode.SUBWAY;
            case 2 -> Mode.RAIL;
            case 4 -> Mode.FERRY;
            default -> Mode.BUS;
        };
    }

    public enum Mode { BUS, SUBWAY, RAIL, AIR, FERRY, WALK }
}
