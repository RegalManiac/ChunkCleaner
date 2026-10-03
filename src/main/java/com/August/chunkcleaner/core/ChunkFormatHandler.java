package com.August.chunkcleaner.core;

import com.August.chunkcleaner.config.Config;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.bukkit.util.Vector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import com.August.chunkcleaner.web.ChunkState;
import net.openhft.hashing.LongHashFunction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class ChunkFormatHandler {

    private static final long MASTER_FILE_SUPER_BLOCK = -0x200812250269L; // B_LINEAR
    private static final long LINEAR_SUPER_BLOCK = 0xc3ff13183cca9d9aL;   // Standard LINEAR
    private static final byte MASTER_FILE_VERSION = 0x02;        // BLinear v2
    private static final byte MASTER_FILE_VERSION_BUCKET = 0x03; // BLinear v3
    private static final int BUCKET_COUNT = 16;
    private static final int BUCKET_SIZE = 64;
    private static final int TOTAL_CHUNKS = 1024;


    public static List<Vector> processAnyRegionFile(File file, Config config, boolean dryRun) throws IOException {
        return processAnyRegionFile(file, config, dryRun, null);
    }

    public static List<Vector> processAnyRegionFile(File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        String name = file.getName();
        if (name.endsWith(".mca")) {
            return processMcaFile(file, config, dryRun, outChunkStates);
        } else {
            return processLinearOrBLinearFile(file, config, dryRun, outChunkStates);
        }
    }

    private static List<Vector> processLinearOrBLinearFile(File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        if (!file.exists() || file.length() < 18) return chunksToDelete;

        long magic = 0;
        boolean readOk = false;
        for (int retry = 0; retry < 3; retry++) {
            try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8);
                if (fc.read(buf) >= 8) {
                    buf.flip();
                    magic = buf.getLong();
                    readOk = true;
                    break;
                } else {
                    return chunksToDelete;
                }
            } catch (Throwable e) {
                if (retry < 2) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
            }
        }
        if (!readOk) return chunksToDelete;

        if (magic == MASTER_FILE_SUPER_BLOCK) {
            return processBLinearFile(file, config, dryRun, outChunkStates);
        } else if (magic == LINEAR_SUPER_BLOCK) {
            return processStandardLinearFile(file, config, dryRun, outChunkStates);
        }

        return chunksToDelete;
    }

    private static List<Vector> processBLinearFile(File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        if (!file.exists() || file.length() < 18) return chunksToDelete;

        long magic;
        byte version;

        long newestTimestamp = 0;
        byte compLevel;
        byte[] v2CompressedData = null;

        int xxHash32Seed = 0;
        long[] posTable = null;
        byte[][] modifiedBucketsRaw = null;
        boolean[] bucketHasData = null;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            magic = raf.readLong();
            if (magic != MASTER_FILE_SUPER_BLOCK) return chunksToDelete;

            version = raf.readByte();

            if (version == MASTER_FILE_VERSION) {
                newestTimestamp = raf.readLong();
                compLevel = raf.readByte();
                long remaining = file.length() - 18;
                if (remaining <= 0) return chunksToDelete;

                v2CompressedData = new byte[(int) remaining];
                raf.readFully(v2CompressedData);
            } else if (version == MASTER_FILE_VERSION_BUCKET) {
                if (file.length() < 142) return chunksToDelete;
                compLevel = raf.readByte();
                xxHash32Seed = raf.readInt();

                posTable = new long[BUCKET_COUNT];
                for (int i = 0; i < BUCKET_COUNT; i++) {
                    posTable[i] = raf.readLong();
                }

                modifiedBucketsRaw = new byte[BUCKET_COUNT][];
                bucketHasData = new boolean[BUCKET_COUNT];

                processV3Buckets(raf, posTable, modifiedBucketsRaw, bucketHasData, chunksToDelete, config, dryRun, outChunkStates);
            } else {
                return chunksToDelete;
            }
        }

        if (version == MASTER_FILE_VERSION) {
            byte[][] allChunks = new byte[TOTAL_CHUNKS][];
            boolean anyDataLeft = false;
            boolean modified = false;

            try (ByteArrayInputStream bais = new ByteArrayInputStream(v2CompressedData);
                 ZstdInputStream zis = new ZstdInputStream(bais);
                 DataInputStream dis = new DataInputStream(zis)) {

                for (int index = 0; index < TOTAL_CHUNKS; index++) {
                    int size;
                    try {
                        size = dis.readInt();
                    } catch (EOFException e) {
                        break;
                    }

                    if (size > 0) {
                        byte[] chunkData = new byte[size];
                        dis.readFully(chunkData);

                        boolean deleteChunk = isBadChunk(chunkData, config);

                        if (deleteChunk) {
                            int localX = index & 31;
                            int localZ = (index >> 5) & 31;
                            chunksToDelete.add(new Vector(localX, 0, localZ));
                            if (outChunkStates != null) {
                                outChunkStates[index] = com.August.chunkcleaner.web.ChunkState.CANDIDATE;
                            }

                            if (!dryRun) {
                                allChunks[index] = null;
                                modified = true;
                            } else {
                                allChunks[index] = chunkData;
                                anyDataLeft = true;
                            }
                        } else {
                            if (outChunkStates != null) {
                                outChunkStates[index] = com.August.chunkcleaner.web.ChunkState.KEPT;
                            }
                            allChunks[index] = chunkData;
                            anyDataLeft = true;
                        }
                    } else {
                        allChunks[index] = null;
                    }
                }
            }

            if (!dryRun && modified) {
                writeNewMasterFileV2(file, magic, version, newestTimestamp, compLevel, allChunks, anyDataLeft);
            }
        } else if (!dryRun && !chunksToDelete.isEmpty()) {
            writeNewMasterFileV3(file, magic, version, compLevel, xxHash32Seed, posTable, modifiedBucketsRaw, bucketHasData);
        }

        return chunksToDelete;
    }

    private static byte[] readFileBytesWithRetry(File file) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                if (retry < 2) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(15));
                }
            }
        }
        return null;
    }

    private static List<Vector> processStandardLinearFile(File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        if (!file.exists() || file.length() < 32) return chunksToDelete;

        byte[] fileBytes = readFileBytesWithRetry(file);
        if (fileBytes == null || fileBytes.length < 32) return chunksToDelete;

        ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
        long magic = buffer.getLong();
        if (magic != LINEAR_SUPER_BLOCK) return chunksToDelete;

        byte version = buffer.get();
        if (version == 1 || version == 2) {
            return processLinearV1(buffer, file, version, config, dryRun, outChunkStates);
        } else if (version == 3) {
            return processLinearV2(buffer, file, config, dryRun, outChunkStates);
        }
        return chunksToDelete;
    }

    private static List<Vector> processLinearV2(ByteBuffer buffer, File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        if (buffer.remaining() < 8 + 1 + 4 + 4 + 128) {
            return chunksToDelete;
        }

        long newestTimestamp = buffer.getLong();
        byte gridSize = buffer.get();
        if (gridSize != 1 && gridSize != 2 && gridSize != 4 && gridSize != 8 && gridSize != 16 && gridSize != 32) {
            return chunksToDelete;
        }
        int bucketSize = 32 / gridSize;
        int bucketCount = gridSize * gridSize;

        int regX = buffer.getInt();
        int regZ = buffer.getInt();

        byte[] bitmapBytes = new byte[128];
        buffer.get(bitmapBytes);
        boolean[] chunkExistence = new boolean[TOTAL_CHUNKS];
        for (int i = 0; i < 128; i++) {
            byte b = bitmapBytes[i];
            for (int j = 0; j < 8; j++) {
                chunkExistence[i * 8 + j] = ((b >> (7 - j)) & 1) == 1;
            }
        }

        ByteArrayOutputStream nbtFeaturesBytes = new ByteArrayOutputStream();
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b <= 0) {
                nbtFeaturesBytes.write(0);
                break;
            }
            nbtFeaturesBytes.write(b);
            int len = b & 0xFF;
            if (buffer.remaining() < len + 4) return chunksToDelete;
            byte[] nameBytes = new byte[len];
            buffer.get(nameBytes);
            nbtFeaturesBytes.write(nameBytes);
            byte[] valBytes = new byte[4];
            buffer.get(valBytes);
            nbtFeaturesBytes.write(valBytes);
        }

        if (buffer.remaining() < bucketCount * 13) return chunksToDelete;

        int[] bucketSizes = new int[bucketCount];
        byte[] bucketCompLevels = new byte[bucketCount];
        long[] bucketHashes = new long[bucketCount];

        for (int i = 0; i < bucketCount; i++) {
            bucketSizes[i] = buffer.getInt();
            bucketCompLevels[i] = buffer.get();
            bucketHashes[i] = buffer.getLong();
        }

        byte[][] bucketCompressed = new byte[bucketCount][];
        for (int i = 0; i < bucketCount; i++) {
            if (bucketSizes[i] > 0) {
                if (buffer.remaining() < bucketSizes[i]) return chunksToDelete;
                bucketCompressed[i] = new byte[bucketSizes[i]];
                buffer.get(bucketCompressed[i]);
            }
        }

        if (buffer.remaining() < 8) return chunksToDelete;
        long footer = buffer.getLong();
        if (footer != LINEAR_SUPER_BLOCK) {
            return chunksToDelete;
        }

        boolean anyBucketModified = false;

        for (int b = 0; b < bucketCount; b++) {
            if (bucketSizes[b] <= 0 || bucketCompressed[b] == null) {
                continue;
            }

            int bx = b / gridSize;
            int bz = b % gridSize;

            byte[] compData = bucketCompressed[b];
            byte[] decompressed;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compData);
                 ZstdInputStream zis = new ZstdInputStream(bais)) {
                decompressed = zis.readAllBytes();
            } catch (Exception e) {
                continue;
            }

            ByteBuffer bucketBuf = ByteBuffer.wrap(decompressed);

            boolean bucketModified = false;
            boolean bucketHasData = false;

            ByteArrayOutputStream newBucketStream = (!dryRun) ? new ByteArrayOutputStream(decompressed.length) : null;
            DataOutputStream newBucketOut = (newBucketStream != null) ? new DataOutputStream(newBucketStream) : null;

            for (int cx = 0; cx < bucketSize; cx++) {
                for (int cz = 0; cz < bucketSize; cz++) {
                    int chunkIndex = (bx * bucketSize + cx) + (bz * bucketSize + cz) * 32;

                    if (bucketBuf.remaining() < 12) {
                        break;
                    }

                    int chunkSize = bucketBuf.getInt();
                    long timestamp = bucketBuf.getLong();

                    if (chunkSize > 8) {
                        int dataLen = chunkSize - 8;
                        if (bucketBuf.remaining() < dataLen) {
                            break;
                        }

                        byte[] chunkData = new byte[dataLen];
                        bucketBuf.get(chunkData);

                        boolean delete = shouldDeleteChunk(chunkData, config);
                        int localX = chunkIndex & 31;
                        int localZ = (chunkIndex >> 5) & 31;

                        if (delete) {
                            chunksToDelete.add(new Vector(localX, 0, localZ));
                            if (outChunkStates != null) {
                                outChunkStates[chunkIndex] = ChunkState.CANDIDATE;
                            }
                            if (!dryRun) {
                                bucketModified = true;
                                chunkExistence[chunkIndex] = false;
                                if (newBucketOut != null) {
                                    newBucketOut.writeInt(0);
                                    newBucketOut.writeLong(0L);
                                }
                            } else {
                                bucketHasData = true;
                            }
                        } else {
                            if (outChunkStates != null) {
                                outChunkStates[chunkIndex] = ChunkState.KEPT;
                            }
                            bucketHasData = true;
                            if (!dryRun && newBucketOut != null) {
                                newBucketOut.writeInt(chunkSize);
                                newBucketOut.writeLong(timestamp);
                                newBucketOut.write(chunkData);
                            }
                        }
                    } else {
                        if (!dryRun && newBucketOut != null) {
                            newBucketOut.writeInt(0);
                            newBucketOut.writeLong(timestamp);
                        }
                    }
                }
            }

            if (!dryRun && bucketModified) {
                anyBucketModified = true;
                if (bucketHasData && newBucketStream != null) {
                    if (newBucketOut != null) newBucketOut.flush();
                    byte[] rawNew = newBucketStream.toByteArray();
                    int level = bucketCompLevels[b] > 0 ? bucketCompLevels[b] : 1;
                    byte[] newComp = Zstd.compress(rawNew, level);
                    bucketCompressed[b] = newComp;
                    bucketSizes[b] = newComp.length;
                    bucketHashes[b] = LongHashFunction.xx().hashBytes(newComp);
                } else {
                    bucketCompressed[b] = null;
                    bucketSizes[b] = 0;
                    bucketHashes[b] = 0;
                }
            }
        }

        if (!dryRun && anyBucketModified) {
            boolean anyRemaining = false;
            for (int i = 0; i < bucketCount; i++) {
                if (bucketSizes[i] > 0 && bucketCompressed[i] != null) {
                    anyRemaining = true;
                    break;
                }
            }

            if (!anyRemaining) {
                Files.deleteIfExists(file.toPath());
            } else {
                writeLinearV2File(file, newestTimestamp, gridSize, regX, regZ, chunkExistence, nbtFeaturesBytes.toByteArray(), bucketSizes, bucketCompLevels, bucketHashes, bucketCompressed);
            }
        }

        return chunksToDelete;
    }

    private static void writeLinearV2File(File file, long newestTimestamp, byte gridSize, int regX, int regZ, boolean[] chunkExistence, byte[] nbtFeatures, int[] bucketSizes, byte[] bucketCompLevels, long[] bucketHashes, byte[][] bucketCompressed) throws IOException {
        File tempFile = new File(file.getPath() + ".tmp");
        int bucketCount = gridSize * gridSize;

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeLong(LINEAR_SUPER_BLOCK);
            dos.writeByte(3); // (LINEAR_V2)
            dos.writeLong(newestTimestamp);
            dos.writeByte(gridSize);
            dos.writeInt(regX);
            dos.writeInt(regZ);

            // Existence bitmap (128 bytes)
            for (int i = 0; i < 128; i++) {
                byte b = 0;
                for (int j = 0; j < 8; j++) {
                    if (chunkExistence[i * 8 + j]) {
                        b |= (byte) (1 << (7 - j));
                    }
                }
                dos.writeByte(b);
            }

            dos.write(nbtFeatures);

            for (int i = 0; i < bucketCount; i++) {
                dos.writeInt(bucketSizes[i]);
                dos.writeByte(bucketCompLevels[i]);
                dos.writeLong(bucketHashes[i]);
            }

            for (int i = 0; i < bucketCount; i++) {
                if (bucketSizes[i] > 0 && bucketCompressed[i] != null) {
                    dos.write(bucketCompressed[i]);
                }
            }

            dos.writeLong(LINEAR_SUPER_BLOCK);

            dos.flush();
            fos.getFD().sync();
        }

        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable e) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Vector> processLinearV1(ByteBuffer buffer, File file, byte version, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        final int FOOTER_SIZE = 8;

        if (buffer.remaining() < 23) return chunksToDelete;

        long newestTimestamp = buffer.getLong();
        byte compLevel = buffer.get();
        short chunkCount = buffer.getShort();
        int dataCount = buffer.getInt();
        long dataHash = buffer.getLong();

        if (dataCount <= 0 || buffer.remaining() < dataCount + FOOTER_SIZE) {
            return chunksToDelete;
        }

        byte[] rawCompressed = new byte[dataCount];
        buffer.get(rawCompressed);

        if (LongHashFunction.xx().hashBytes(rawCompressed) != dataHash) {
            return chunksToDelete;
        }

        long footer = buffer.getLong();
        if (footer != LINEAR_SUPER_BLOCK) {
            return chunksToDelete;
        }

        byte[] decompressed;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(rawCompressed);
             ZstdInputStream zis = new ZstdInputStream(bais)) {
            decompressed = zis.readAllBytes();
        } catch (Exception e) {
            return chunksToDelete;
        }

        ByteBuffer buf = ByteBuffer.wrap(decompressed);
        int[] starts = new int[TOTAL_CHUNKS];
        int[] timestamps = new int[TOTAL_CHUNKS];
        for (int i = 0; i < TOTAL_CHUNKS; i++) {
            starts[i] = buf.getInt();
            timestamps[i] = buf.getInt();
        }

        byte[][] chunks = new byte[TOTAL_CHUNKS][];
        boolean modified = false;
        boolean anyRemaining = false;

        for (int i = 0; i < TOTAL_CHUNKS; i++) {
            if (starts[i] > 0) {
                byte[] chunkData = new byte[starts[i]];
                buf.get(chunkData);

                boolean delete = shouldDeleteChunk(chunkData, config);
                int localX = i & 31;
                int localZ = (i >> 5) & 31;

                if (delete) {
                    chunksToDelete.add(new Vector(localX, 0, localZ));
                    if (outChunkStates != null) {
                        outChunkStates[i] = ChunkState.CANDIDATE;
                    }
                    if (!dryRun) {
                        chunks[i] = null;
                        starts[i] = 0;
                        timestamps[i] = 0;
                        modified = true;
                    } else {
                        chunks[i] = chunkData;
                        anyRemaining = true;
                    }
                } else {
                    if (outChunkStates != null) {
                        outChunkStates[i] = ChunkState.KEPT;
                    }
                    chunks[i] = chunkData;
                    anyRemaining = true;
                }
            } else {
                chunks[i] = null;
            }
        }

        if (!dryRun && modified) {
            if (!anyRemaining) {
                Files.deleteIfExists(file.toPath());
            } else {
                ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(rawOut);
                for (int i = 0; i < TOTAL_CHUNKS; i++) {
                    dos.writeInt(starts[i]);
                    dos.writeInt(timestamps[i]);
                }
                for (int i = 0; i < TOTAL_CHUNKS; i++) {
                    if (chunks[i] != null) {
                        dos.write(chunks[i]);
                    }
                }
                dos.flush();

                byte[] newDecompressed = rawOut.toByteArray();
                byte[] newCompressed = Zstd.compress(newDecompressed, compLevel > 0 ? compLevel : 1);
                long newHash = LongHashFunction.xx().hashBytes(newCompressed);

                File tempFile = new File(file.getPath() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     DataOutputStream outDos = new DataOutputStream(fos)) {
                    outDos.writeLong(LINEAR_SUPER_BLOCK);
                    outDos.writeByte(version);
                    outDos.writeLong(newestTimestamp);
                    outDos.writeByte(compLevel);
                    outDos.writeShort(chunkCount);
                    outDos.writeInt(newCompressed.length);
                    outDos.writeLong(newHash);
                    outDos.write(newCompressed);
                    outDos.writeLong(LINEAR_SUPER_BLOCK);
                    outDos.flush();
                    fos.getFD().sync();
                }

                try {
                    Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Throwable e) {
                    Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        return chunksToDelete;
    }

    private static List<Vector> processMcaFile(File file, Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        List<Vector> chunksToDelete = new ArrayList<>();
        if (!file.exists() || file.length() < 8192) return chunksToDelete;

        int[] locations = new int[1024];
        boolean modified = false;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (int i = 0; i < 1024; i++) {
                locations[i] = raf.readInt();
            }

            for (int i = 0; i < 1024; i++) {
                int loc = locations[i];
                if (loc == 0) continue;

                int sectorOffset = loc >>> 8;
                long byteOffset = (long) sectorOffset * 4096L;
                if (byteOffset + 5 > file.length()) continue;

                raf.seek(byteOffset);
                int length = raf.readInt();
                if (length <= 1 || byteOffset + 4 + length > file.length()) continue;

                raf.readByte();
                byte[] chunkData = new byte[length - 1];
                raf.readFully(chunkData);

                if (shouldDeleteChunk(chunkData, config)) {
                    int localX = i & 31;
                    int localZ = (i >> 5) & 31;
                    chunksToDelete.add(new Vector(localX, 0, localZ));
                    if (outChunkStates != null) {
                        outChunkStates[i] = com.August.chunkcleaner.web.ChunkState.CANDIDATE;
                    }
                    locations[i] = 0;
                    modified = true;
                } else {
                    if (outChunkStates != null) {
                        outChunkStates[i] = com.August.chunkcleaner.web.ChunkState.KEPT;
                    }
                }
            }
        }

        if (!dryRun && modified) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                for (int i = 0; i < 1024; i++) {
                    raf.writeInt(locations[i]);
                }
            }
        }

        return chunksToDelete;
    }

    private static void processV3Buckets(RandomAccessFile raf, long[] posTable, byte[][] modifiedBucketsRaw,
                                         boolean[] bucketHasData, List<Vector> chunksToDelete,
                                         Config config, boolean dryRun, byte[] outChunkStates) throws IOException {
        for (int b = 0; b < BUCKET_COUNT; b++) {
            long offset = posTable[b];
            if (offset == 0) continue;

            raf.seek(offset);
            int originalLen = raf.readInt();
            int compressedLen = raf.readInt();

            byte[] compressedBucket = new byte[compressedLen];
            raf.readFully(compressedBucket);
            byte[] decompressedBucket = Zstd.decompress(compressedBucket, originalLen);

            boolean bucketModified = false;
            boolean hasRemainingData = false;

            try (ByteArrayInputStream bais = new ByteArrayInputStream(decompressedBucket);
                 DataInputStream dis = new DataInputStream(bais);
                 ByteArrayOutputStream newBucketBaos = new ByteArrayOutputStream();
                 DataOutputStream newBucketDos = new DataOutputStream(newBucketBaos)) {

                for (int c = 0; c < BUCKET_SIZE; c++) {
                    int chunkSectionDataSize = dis.readInt();

                    if (chunkSectionDataSize > 0) {
                        byte[] chunkData = new byte[chunkSectionDataSize];
                        dis.readFully(chunkData);

                        boolean deleteChunk = isBadChunk(chunkData, config);
                        int chunkIndex = (b << 6) | c;

                        if (deleteChunk) {
                            int localX = chunkIndex & 31;
                            int localZ = (chunkIndex >> 5) & 31;
                            chunksToDelete.add(new Vector(localX, 0, localZ));
                            if (outChunkStates != null) {
                                outChunkStates[chunkIndex] = com.August.chunkcleaner.web.ChunkState.CANDIDATE;
                            }

                            if (!dryRun) {
                                newBucketDos.writeInt(0);
                                bucketModified = true;
                            } else {
                                newBucketDos.writeInt(chunkSectionDataSize);
                                newBucketDos.write(chunkData);
                                hasRemainingData = true;
                            }
                        } else {
                            if (outChunkStates != null) {
                                outChunkStates[chunkIndex] = com.August.chunkcleaner.web.ChunkState.KEPT;
                            }
                            newBucketDos.writeInt(chunkSectionDataSize);
                            newBucketDos.write(chunkData);
                            hasRemainingData = true;
                        }
                    } else {
                        newBucketDos.writeInt(0);
                    }
                }

                if (bucketModified) {
                    modifiedBucketsRaw[b] = newBucketBaos.toByteArray();
                    bucketHasData[b] = hasRemainingData;
                } else {
                    bucketHasData[b] = true;
                }
            }
        }
    }

    private static boolean isBadChunk(byte[] chunkData, Config config) {
        if (chunkData == null || chunkData.length < 16) return false;
        try {
            ByteBuffer chunkBuf = ByteBuffer.wrap(chunkData);
            int nbtLength = chunkBuf.getInt();
            chunkBuf.getLong(); // timestamp
            chunkBuf.getInt();  // xxHash32

            if (nbtLength > 0 && nbtLength <= chunkBuf.remaining()) {
                byte[] nbtData = new byte[nbtLength];
                chunkBuf.get(nbtData);
                return shouldDeleteChunk(nbtData, config);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void writeNewMasterFileV2(File file, long magic, byte version, long newestTimestamp,
                                             byte compLevel, byte[][] allChunks, boolean hasAnyDataLeft) throws IOException {
        if (!hasAnyDataLeft) {
            Files.deleteIfExists(file.toPath());
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            for (int i = 0; i < TOTAL_CHUNKS; i++) {
                byte[] chunk = allChunks[i];
                if (chunk != null && chunk.length > 0) {
                    dos.writeInt(chunk.length);
                    dos.write(chunk);
                } else {
                    dos.writeInt(0);
                }
            }
            dos.flush();
        }

        byte[] rawBytes = baos.toByteArray();
        byte[] compressed = Zstd.compress(rawBytes, compLevel);

        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try (RandomAccessFile out = new RandomAccessFile(tempFile, "rw")) {
            out.writeLong(magic);
            out.writeByte(version);
            out.writeLong(newestTimestamp > 0 ? newestTimestamp : System.currentTimeMillis());
            out.writeByte(compLevel);
            out.write(compressed);
            try {
                out.getFD().sync();
            } catch (Exception ignored) {}
        }

        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable e) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeNewMasterFileV3(File file, long magic, byte version, byte compLevel, int seed,
                                             long[] posTable, byte[][] modifiedBuckets, boolean[] bucketHasData) throws IOException {
        boolean anyDataLeft = false;
        for (int b = 0; b < BUCKET_COUNT; b++) {
            if (modifiedBuckets[b] != null) {
                if (bucketHasData[b]) {
                    anyDataLeft = true;
                    break;
                }
            } else if (posTable[b] != 0) {
                anyDataLeft = true;
                break;
            }
        }

        if (!anyDataLeft) {
            Files.deleteIfExists(file.toPath());
            return;
        }

        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try (RandomAccessFile out = new RandomAccessFile(tempFile, "rw");
             RandomAccessFile in = new RandomAccessFile(file, "r")) {

            out.writeLong(magic);
            out.writeByte(version);
            out.writeByte(compLevel);
            out.writeInt(seed);

            long posTableOffset = out.getFilePointer();
            for (int i = 0; i < BUCKET_COUNT; i++) {
                out.writeLong(0);
            }

            long[] newPosTable = new long[BUCKET_COUNT];
            long currentOffset = out.getFilePointer();

            for (int b = 0; b < BUCKET_COUNT; b++) {
                if (modifiedBuckets[b] != null) {
                    if (bucketHasData[b]) {
                        byte[] rawBucket = modifiedBuckets[b];
                        byte[] compressed = Zstd.compress(rawBucket, compLevel);
                        newPosTable[b] = currentOffset;
                        out.seek(currentOffset);
                        out.writeInt(rawBucket.length);
                        out.writeInt(compressed.length);
                        out.write(compressed);
                        currentOffset = out.getFilePointer();
                    } else {
                        newPosTable[b] = 0;
                    }
                } else if (posTable[b] != 0) {
                    newPosTable[b] = currentOffset;
                    in.seek(posTable[b]);
                    int origLen = in.readInt();
                    int compLen = in.readInt();
                    out.seek(currentOffset);
                    out.writeInt(origLen);
                    out.writeInt(compLen);
                    byte[] compData = new byte[compLen];
                    in.readFully(compData);
                    out.write(compData);
                    currentOffset = out.getFilePointer();
                } else {
                    newPosTable[b] = 0;
                }
            }

            out.seek(posTableOffset);
            for (int i = 0; i < BUCKET_COUNT; i++) {
                out.writeLong(newPosTable[i]);
            }

            try {
                out.getFD().sync();
            } catch (Exception ignored) {}
        }

        try {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable e) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void cleanStaleTempFiles(File regionDir) {
        if (regionDir == null || !regionDir.exists() || !regionDir.isDirectory()) return;
        File[] tmpFiles = regionDir.listFiles((_, name) -> name.endsWith(".tmp"));
        if (tmpFiles != null) {
            long now = System.currentTimeMillis();
            for (File tmp : tmpFiles) {
                try {
                    if (now - tmp.lastModified() > 10000L) {
                        Files.deleteIfExists(tmp.toPath());
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public static boolean shouldDeleteChunk(byte[] nbtBytes, Config config) {
        if (nbtBytes == null || nbtBytes.length == 0) return false;
        try {
            int offset = 0;
            int length = nbtBytes.length;

            if (length > 5 && nbtBytes[0] == 0 && nbtBytes[1] == 0) {
                int compType = nbtBytes[4] & 0xFF;
                if (compType == 1 || compType == 2 || compType == 3) {
                    offset = 5;
                    length -= 5;
                }
            }

            InputStream is = new ByteArrayInputStream(nbtBytes, offset, length);
            int magic = nbtBytes[offset] & 0xFF;

            if (magic == 0x1F && length > 1 && (nbtBytes[offset + 1] & 0xFF) == 0x8B) {
                is = new GZIPInputStream(is);
            } else if (magic == 0x78) {
                is = new InflaterInputStream(is);
            }

            try (DataInputStream dis = new DataInputStream(is)) {
                CompoundTag root = NbtIo.read(dis, NbtAccounter.unlimitedHeap());

                long inhabitedTime = root.contains("InhabitedTime")
                        ? root.getLongOr("InhabitedTime", 0L)
                        : root.getCompoundOrEmpty("Level").getLongOr("InhabitedTime", 0L);

                return inhabitedTime <= config.getMinInhabitedTime();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
