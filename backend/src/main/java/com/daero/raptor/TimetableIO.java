package com.daero.raptor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 컬럼형 {@link Timetable} 의 바이너리 저장/로드(prebuild 스냅샷).
 * 큰 머신에서 GTFS로 한 번 빌드해 저장 → 운영 서버는 이 파일만 로드하여
 * 기동 시 GTFS 파싱·빌드 피크 없이 낮은 메모리로 구동한다.
 */
public final class TimetableIO {

    private static final int MAGIC = 0xDAE50001;
    private static final int VERSION = 1;

    private TimetableIO() {}

    public static void save(Timetable tt, Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        try (DataOutputStream o = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 20))) {
            o.writeInt(MAGIC);
            o.writeInt(VERSION);

            o.writeInt(tt.nStops);
            writeStrs(o, tt.stopId);
            writeStrs(o, tt.stopName);
            writeDoubles(o, tt.lat);
            writeDoubles(o, tt.lon);

            o.writeInt(tt.nPatterns);
            writeInts(o, tt.patternStopOffset);
            writeInts(o, tt.patternStops);
            writeInts(o, tt.patternMode);
            writeStrs(o, tt.patternRouteName);
            writeInts(o, tt.patternNumTrips);
            writeInts(o, tt.patternTimeOffset);
            writeInts(o, tt.arr);
            writeInts(o, tt.dep);

            writeInts(o, tt.stopPatOffset);
            writeInts(o, tt.stopPatId);
            writeInts(o, tt.stopPatPos);

            writeInts(o, tt.transferOffset);
            writeInts(o, tt.transferTarget);
            writeInts(o, tt.transferSec);
        }
    }

    public static Timetable load(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {
            if (in.readInt() != MAGIC) throw new IOException("잘못된 파일(매직 불일치)");
            int ver = in.readInt();
            if (ver != VERSION) throw new IOException("버전 불일치: " + ver);

            int nStops = in.readInt();
            String[] stopId = readStrs(in, nStops);
            String[] stopName = readStrs(in, nStops);
            double[] lat = readDoubles(in, nStops);
            double[] lon = readDoubles(in, nStops);

            int nPatterns = in.readInt();
            int[] patternStopOffset = readInts(in);
            int[] patternStops = readInts(in);
            int[] patternMode = readInts(in);
            String[] patternRouteName = readStrs(in, nPatterns);
            int[] patternNumTrips = readInts(in);
            int[] patternTimeOffset = readInts(in);
            int[] arr = readInts(in);
            int[] dep = readInts(in);

            int[] stopPatOffset = readInts(in);
            int[] stopPatId = readInts(in);
            int[] stopPatPos = readInts(in);

            int[] transferOffset = readInts(in);
            int[] transferTarget = readInts(in);
            int[] transferSec = readInts(in);

            // stopIndex 는 저장하지 않고 stopId 로부터 재구성(그리드는 생성자에서 재생성).
            Map<String, Integer> stopIndex = new HashMap<>(nStops * 2);
            for (int i = 0; i < nStops; i++) stopIndex.put(stopId[i], i);

            return new Timetable(nStops, stopId, stopName, lat, lon, stopIndex,
                    nPatterns, patternStopOffset, patternStops, patternMode, patternRouteName,
                    patternNumTrips, patternTimeOffset, arr, dep,
                    stopPatOffset, stopPatId, stopPatPos,
                    transferOffset, transferTarget, transferSec);
        }
    }

    // ── 배열 I/O ────────────────────────────────────────────
    private static void writeInts(DataOutputStream o, int[] a) throws IOException {
        o.writeInt(a.length);
        for (int v : a) o.writeInt(v);
    }

    private static int[] readInts(DataInputStream in) throws IOException {
        int n = in.readInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = in.readInt();
        return a;
    }

    private static void writeDoubles(DataOutputStream o, double[] a) throws IOException {
        o.writeInt(a.length);
        for (double v : a) o.writeDouble(v);
    }

    private static double[] readDoubles(DataInputStream in, int expected) throws IOException {
        int n = in.readInt();
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = in.readDouble();
        return a;
    }

    private static void writeStrs(DataOutputStream o, String[] a) throws IOException {
        o.writeInt(a.length);
        for (String s : a) o.writeUTF(s == null ? "" : s);
    }

    private static String[] readStrs(DataInputStream in, int expected) throws IOException {
        int n = in.readInt();
        String[] a = new String[n];
        for (int i = 0; i < n; i++) a[i] = in.readUTF();
        return a;
    }
}
