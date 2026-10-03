package com.August.chunkcleaner.web;


public final class ChunkState {

    public static final byte KEPT = 1;
    public static final byte CANDIDATE = 2;
    public static final byte DELETED = 3;

    private ChunkState() {}
}
