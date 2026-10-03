package com.August.chunkcleaner.web;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkMapTracker {

    private final Map<String, Map<Long, RegionChunkData>> worlds = new ConcurrentHashMap<>();

    private final AtomicLong globalKept = new AtomicLong(0);
    private final AtomicLong globalCandidates = new AtomicLong(0);
    private final AtomicLong globalDeleted = new AtomicLong(0);

    public static long packRegion(int regX, int regZ) {
        return (((long) regX) << 32) | (regZ & 0xFFFFFFFFL);
    }

    public RegionChunkData recordRegion(String worldName, int regX, int regZ, byte[] chunkStates) {
        if (worldName == null) worldName = "world";
        Map<Long, RegionChunkData> regionMap = worlds.computeIfAbsent(worldName, _ -> new ConcurrentHashMap<>());
        long key = packRegion(regX, regZ);

        RegionChunkData data = regionMap.computeIfAbsent(key, _ -> new RegionChunkData(regX, regZ));
        data.setAll(chunkStates);
        recalculateTotals();
        return data;
    }

    public void clear() {
        worlds.clear();
        globalKept.set(0);
        globalCandidates.set(0);
        globalDeleted.set(0);
    }

    public void recalculateTotals() {
        long kept = 0;
        long cand = 0;
        long del = 0;

        for (Map<Long, RegionChunkData> worldMap : worlds.values()) {
            for (RegionChunkData r : worldMap.values()) {
                kept += r.getKeptCount();
                cand += r.getCandidateCount();
                del += r.getDeletedCount();
            }
        }

        globalKept.set(kept);
        globalCandidates.set(cand);
        globalDeleted.set(del);
    }

    public Set<String> getWorldNames() {
        return Collections.unmodifiableSet(worlds.keySet());
    }

    public Map<Long, RegionChunkData> getRegions(String worldName) {
        Map<Long, RegionChunkData> map = worlds.get(worldName);
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    public RegionChunkData getRegion(String worldName, int regX, int regZ) {
        Map<Long, RegionChunkData> map = worlds.get(worldName);
        if (map == null) return null;
        return map.get(packRegion(regX, regZ));
    }

    public long getGlobalKept() {
        return globalKept.get();
    }

    public long getGlobalCandidates() {
        return globalCandidates.get();
    }

    public long getGlobalDeleted() {
        return globalDeleted.get();
    }

    public int getTotalRegionsCount() {
        int count = 0;
        for (Map<Long, RegionChunkData> worldMap : worlds.values()) {
            count += worldMap.size();
        }
        return count;
    }
}
