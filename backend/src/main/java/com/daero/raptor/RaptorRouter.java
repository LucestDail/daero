package com.daero.raptor;

import com.daero.gtfs.model.Route;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RAPTOR 경로탐색 엔진 (다기준: 도착시각 + 환승수).
 * 라운드 k = 승차 k회. 라운드별 라벨(arrK[k])을 유지해 (도착, 환승) 파레토 후보를 만든다.
 * → {@link #journeys}: 환승수↔소요시간 트레이드오프가 다른 여러 대안 경로 반환.
 * → {@link #query}: 그중 환승 페널티 최소인 "추천" 하나.
 */
@Component
@RequiredArgsConstructor
public class RaptorRouter {

    private static final int INF = Integer.MAX_VALUE;
    private static final int MAX_ROUNDS = 6;              // 승차 최대 6회(환승 5회)
    private static final int TRANSFER_PENALTY_SEC = 300;  // 추천 선택 시 환승 1회당 5분 페널티

    private final TimetableBuilder builder;

    public record Leg(String mode, String routeName,
                      String fromStopId, String fromName, String toStopId, String toName,
                      int depSec, int arrSec) {}

    public record Result(boolean found, int arrivalSec, int durationSec, int transfers, List<Leg> legs) {}

    /** RAPTOR 실행 결과(라벨·부모포인터) 컨테이너. */
    private static final class Ctx {
        int[][] arrK, pStop, pPat, pTrip, pBoard, pAlight;
        int R, departSec;
    }

    public Result query(int srcStop, int dstStop, int departSec) {
        return query(new int[]{srcStop}, new int[]{0}, new int[]{dstStop}, new int[]{0}, departSec);
    }

    /** 추천 경로(환승 페널티 포함 최소) 하나. */
    public Result query(int[] srcStops, int[] srcAccessSec, int[] dstStops, int[] dstEgressSec, int departSec) {
        List<Result> js = journeys(srcStops, srcAccessSec, dstStops, dstEgressSec, departSec);
        if (js.isEmpty()) return new Result(false, -1, -1, -1, List.of());
        Result best = js.get(0);
        int bestScore = INF;
        for (Result r : js) {
            int score = r.arrivalSec() + TRANSFER_PENALTY_SEC * r.transfers();
            if (score < bestScore) { bestScore = score; best = r; }
        }
        return best;
    }

    public List<Result> journeys(int[] srcStops, int[] srcAccessSec, int[] dstStops, int[] dstEgressSec, int departSec) {
        return journeys(srcStops, srcAccessSec, dstStops, dstEgressSec, departSec, false);
    }

    /** 파레토 대안 경로들(환승 적음↔빠름). railOnly=true 면 버스 제외(지하철·철도 우선). 도착 이른 순. */
    public List<Result> journeys(int[] srcStops, int[] srcAccessSec, int[] dstStops, int[] dstEgressSec,
                                 int departSec, boolean railOnly) {
        Timetable tt = builder.getTimetable();
        if (tt == null) return List.of();
        Ctx ctx = run(tt, srcStops, srcAccessSec, departSec, railOnly);

        // 라운드별로 도착 최소 dst 를 잡고, 라운드가 늘수록 더 이른 도착이면 새 파레토 점.
        List<Result> out = new ArrayList<>();
        int bestSoFar = INF;
        for (int k = 0; k <= ctx.R; k++) {
            int bd = -1, ba = INF;
            for (int i = 0; i < dstStops.length; i++) {
                int d = dstStops[i];
                if (ctx.arrK[k][d] == INF) continue;
                int a = ctx.arrK[k][d] + dstEgressSec[i];
                if (a < ba) { ba = a; bd = d; }
            }
            if (bd == -1 || ba >= bestSoFar) continue;
            bestSoFar = ba;
            List<Leg> legs = reconstruct(tt, ctx, bd, k);
            int transfers = (int) legs.stream().filter(l -> !"WALK".equals(l.mode())).count() - 1;
            out.add(new Result(true, ba, ba - departSec, Math.max(0, transfers), legs));
        }
        return out;
    }

    private static final int BUS_ORD = Route.Mode.BUS.ordinal();

    /** 라운드 루프 실행 → 라벨·부모포인터 채운 Ctx. railOnly 면 버스 패턴 스캔 제외. */
    private Ctx run(Timetable tt, int[] srcStops, int[] srcAccessSec, int departSec, boolean railOnly) {
        int nStops = tt.nStops, R = MAX_ROUNDS;
        Ctx c = new Ctx();
        c.R = R; c.departSec = departSec;
        c.arrK = new int[R + 1][nStops];
        c.pStop = new int[R + 1][nStops];
        c.pPat = new int[R + 1][nStops];
        c.pTrip = new int[R + 1][nStops];
        c.pBoard = new int[R + 1][nStops];
        c.pAlight = new int[R + 1][nStops];
        for (int[] a : c.arrK) Arrays.fill(a, INF);
        for (int[] a : c.pStop) Arrays.fill(a, -2);

        List<Integer> marked = new ArrayList<>();
        for (int i = 0; i < srcStops.length; i++) {
            int s = srcStops[i], t = departSec + srcAccessSec[i];
            if (t < c.arrK[0][s]) { c.arrK[0][s] = t; c.pStop[0][s] = -1; marked.add(s); }
        }
        boolean[] mk = new boolean[nStops];
        int[] patternEntry = new int[tt.nPatterns];
        Arrays.fill(patternEntry, -1);

        for (int k = 1; k <= R && !marked.isEmpty(); k++) {
            int[] prev = c.arrK[k - 1], cur = c.arrK[k];
            System.arraycopy(prev, 0, cur, 0, nStops);

            List<Integer> touched = new ArrayList<>();
            for (int s : marked) {
                for (int j = tt.stopPatOffset[s]; j < tt.stopPatOffset[s + 1]; j++) {
                    int p = tt.stopPatId[j], pos = tt.stopPatPos[j];
                    if (railOnly && tt.patternMode[p] == BUS_ORD) continue; // 버스 제외
                    if (patternEntry[p] == -1) { patternEntry[p] = pos; touched.add(p); }
                    else if (pos < patternEntry[p]) patternEntry[p] = pos;
                }
            }
            Arrays.fill(mk, false);
            List<Integer> vehImproved = new ArrayList<>();

            for (int p : touched) {
                int off = tt.patternStopOffset[p];
                int L = tt.patternStopOffset[p + 1] - off;
                int base = tt.patternTimeOffset[p];
                int T = tt.patternNumTrips[p];
                int trip = -1, boardPos = -1, boardStop = -1;
                for (int i = patternEntry[p]; i < L; i++) {
                    int stop = tt.patternStops[off + i];
                    if (trip >= 0) {
                        int a = tt.arr[base + trip * L + i];
                        if (a < cur[stop]) {
                            cur[stop] = a;
                            c.pStop[k][stop] = boardStop; c.pPat[k][stop] = p; c.pTrip[k][stop] = trip;
                            c.pBoard[k][stop] = boardPos; c.pAlight[k][stop] = i;
                            if (!mk[stop]) { mk[stop] = true; vehImproved.add(stop); }
                        }
                    }
                    if (prev[stop] != INF) {
                        int nt = earliestTrip(tt, base, L, T, i, prev[stop]);
                        if (nt != -1 && (trip == -1 || nt < trip)) { trip = nt; boardPos = i; boardStop = stop; }
                    }
                }
                patternEntry[p] = -1;
            }

            List<Integer> nextMarked = new ArrayList<>(vehImproved);
            for (int s : vehImproved) {
                for (int j = tt.transferOffset[s]; j < tt.transferOffset[s + 1]; j++) {
                    int o = tt.transferTarget[j];
                    int na = cur[s] + tt.transferSec[j];
                    if (na < cur[o]) {
                        cur[o] = na;
                        c.pStop[k][o] = s; c.pPat[k][o] = -1;
                        nextMarked.add(o);
                    }
                }
            }
            marked = nextMarked;
        }
        return c;
    }

    private List<Leg> reconstruct(Timetable tt, Ctx c, int stop, int k) {
        List<Leg> legs = new ArrayList<>();
        int cur = stop, ck = k, guard = 0;
        while (guard++ < 200) {
            while (ck > 0 && c.pStop[ck][cur] == -2) ck--;
            if (ck == 0 || c.pStop[ck][cur] == -1) break;
            int from = c.pStop[ck][cur];
            if (c.pPat[ck][cur] >= 0) {
                int p = c.pPat[ck][cur];
                int off = tt.patternStopOffset[p];
                int L = tt.patternStopOffset[p + 1] - off;
                int base = tt.patternTimeOffset[p];
                int ti = c.pTrip[ck][cur];
                int depSec = tt.dep[base + ti * L + c.pBoard[ck][cur]];
                int arrSec = tt.arr[base + ti * L + c.pAlight[ck][cur]];
                String mode = Route.Mode.values()[tt.patternMode[p]].name();
                legs.add(new Leg(mode, tt.patternRouteName[p], tt.stopId[from], tt.stopName[from],
                        tt.stopId[cur], tt.stopName[cur], depSec, arrSec));
                cur = from; ck = ck - 1;
            } else {
                legs.add(new Leg("WALK", "", tt.stopId[from], tt.stopName[from],
                        tt.stopId[cur], tt.stopName[cur], c.arrK[ck][from], c.arrK[ck][cur]));
                cur = from;
            }
        }
        Collections.reverse(legs);
        return mergeWalks(legs);
    }

    private List<Leg> mergeWalks(List<Leg> legs) {
        List<Leg> out = new ArrayList<>();
        for (Leg l : legs) {
            if (!out.isEmpty() && "WALK".equals(l.mode()) && "WALK".equals(out.get(out.size() - 1).mode())) {
                Leg prev = out.remove(out.size() - 1);
                out.add(new Leg("WALK", "", prev.fromStopId(), prev.fromName(),
                        l.toStopId(), l.toName(), prev.depSec(), l.arrSec()));
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private int earliestTrip(Timetable tt, int base, int L, int T, int i, int time) {
        int lo = 0, hi = T - 1, res = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int d = tt.dep[base + mid * L + i];
            if (d >= time) { res = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return res;
    }
}
