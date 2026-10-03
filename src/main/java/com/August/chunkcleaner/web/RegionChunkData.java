package com.August.chunkcleaner.web;

import java.util.Base64;

public class RegionChunkData {

    private final int regX;
    private final int regZ;
    private final byte[] states = new byte[1024];

    private int keptCount = 0;
    private int candidateCount = 0;
    private int deletedCount = 0;

    public RegionChunkData(int regX, int regZ) {
        this.regX = regX;
        this.regZ = regZ;
    }

    public synchronized void setAll(byte[] newStates) {
        if (newStates == null || newStates.length != 1024) return;
        System.arraycopy(newStates, 0, this.states, 0, 1024);
        recalculateCounts();
    }

    private void incrementCount(byte state) {
        switch (state) {
            case ChunkState.KEPT -> keptCount++;
            case ChunkState.CANDIDATE -> candidateCount++;
            case ChunkState.DELETED -> deletedCount++;
        }
    }

    private void recalculateCounts() {
        keptCount = 0;
        candidateCount = 0;
        deletedCount = 0;
        for (byte s : states) {
            incrementCount(s);
        }
    }

    public synchronized String toBase64() {
        return Base64.getEncoder().encodeToString(states);
    }

    public int getRegX() {
        return regX;
    }

    public int getRegZ() {
        return regZ;
    }

    public synchronized int getKeptCount() {
        return keptCount;
    }

    public synchronized int getCandidateCount() {
        return candidateCount;
    }

    public synchronized int getDeletedCount() {
        return deletedCount;
    }
}
