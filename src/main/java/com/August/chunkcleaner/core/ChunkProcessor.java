package com.August.chunkcleaner.core;

import com.August.chunkcleaner.ChunkCleanerPlugin;
import com.August.chunkcleaner.config.Config;
import com.August.chunkcleaner.util.LanguageUtil;
import com.August.chunkcleaner.web.ChunkMapTracker;
import com.August.chunkcleaner.web.ChunkState;
import com.August.chunkcleaner.web.RegionChunkData;
import com.August.chunkcleaner.web.WebServerManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChunkProcessor {

    private final ChunkCleanerPlugin plugin;
    private final Config config;
    private final ChunkMapTracker tracker;
    private WebServerManager webServerManager;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.(b_linear|linear|mca)$");
    private static final Pattern SWP_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.b_linear\\.swp$");

    private static Field storageField = null;
    private static Field cacheMapField = null;

    private final AtomicLong currentCycleFiles = new AtomicLong(0);
    private final AtomicLong currentCycleCandidates = new AtomicLong(0);
    private final AtomicLong currentCycleDeleted = new AtomicLong(0);
    private final AtomicLong currentCycleSkipped = new AtomicLong(0);
    private volatile long startTimeMs = 0;
    private volatile boolean isDryRunCurrent = false;

    private static synchronized RegionFileStorage getRegionStorage(ServerLevel serverLevel) {
        if (serverLevel == null) return null;
        Object chunkMap = serverLevel.getChunkSource().chunkMap;

        try {
            Method moonriseMethod = chunkMap.getClass().getMethod("moonrise$getRegionStorage");
            moonriseMethod.setAccessible(true);
            return (RegionFileStorage) moonriseMethod.invoke(chunkMap);
        } catch (Throwable ignored) {}

        if (storageField != null) {
            try {
                return (RegionFileStorage) storageField.get(chunkMap);
            } catch (Exception ignored) {}
        }

        Class<?> clazz = chunkMap.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (RegionFileStorage.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        storageField = f;
                        return (RegionFileStorage) f.get(chunkMap);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, ?> getRegionCacheMap(RegionFileStorage storage) {
        if (storage == null) return null;
        if (cacheMapField != null) {
            try {
                return (Map<Long, ?>) cacheMapField.get(storage);
            } catch (Exception ignored) {}
        }
        Class<?> clazz = storage.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        cacheMapField = f;
                        return (Map<Long, ?>) f.get(storage);
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static boolean isAnyChunkLoadedInRegion(World world, int regX, int regZ) {
        if (world == null) return false;
        int baseChunkX = regX << 5;
        int baseChunkZ = regZ << 5;
        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                if (world.isChunkLoaded(baseChunkX + cx, baseChunkZ + cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void invokeSyncIfNeeded(Object regionFile) {
        if (regionFile == null) return;
        try {
            Method syncMethod = regionFile.getClass().getMethod("syncIfNeeded");
            syncMethod.setAccessible(true);
            syncMethod.invoke(regionFile);
            return;
        } catch (Throwable ignored) {}

        try {
            Method flushMethod = regionFile.getClass().getMethod("flush");
            flushMethod.setAccessible(true);
            flushMethod.invoke(regionFile);
        } catch (Throwable ignored) {}
    }

    private static void invokeClose(Object regionFile) {
        if (regionFile == null) return;
        try {
            Method closeMethod = regionFile.getClass().getMethod("close");
            closeMethod.setAccessible(true);
            closeMethod.invoke(regionFile);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private static void syncAndCloseRegionIfUnloaded(World world, RegionFileStorage storage, int regX, int regZ) {
        if (world == null || storage == null) return;
        Map<Long, ?> cache = getRegionCacheMap(storage);
        if (cache == null) return;

        long key = ChunkPos.pack(regX, regZ);
        Object regionFile = cache.get(key);
        if (regionFile == null) return;

        boolean anyLoaded = isAnyChunkLoadedInRegion(world, regX, regZ);

        synchronized (storage) {
            if (!anyLoaded) {
                invokeClose(regionFile);
                cache.remove(key);
                cleanUpSwapFileIfPresent(regionFile);
            } else {
                invokeSyncIfNeeded(regionFile);
            }
        }
    }

    private static void cleanUpSwapFileIfPresent(Object regionFile) {
        if (regionFile == null) return;
        try {
            Method getPathMethod = regionFile.getClass().getMethod("getPath");
            getPathMethod.setAccessible(true);
            Object pathObj = getPathMethod.invoke(regionFile);
            if (pathObj instanceof Path path) {
                File swpFile = new File(path + ".swp");
                deleteFileWithRetry(swpFile);
            }
        } catch (Throwable ignored) {}
    }

    private static void cleanStaleSwapFiles(File regionDir, RegionFileStorage storage) {
        if (regionDir == null || !regionDir.exists() || !regionDir.isDirectory()) return;
        File[] swpFiles = regionDir.listFiles((_, name) -> name.endsWith(".swp"));
        if (swpFiles == null || swpFiles.length == 0) return;

        Map<Long, ?> cache = storage != null ? getRegionCacheMap(storage) : null;

        for (File swp : swpFiles) {
            try {
                Matcher matcher = SWP_PATTERN.matcher(swp.getName());
                if (matcher.matches()) {
                    int regX = Integer.parseInt(matcher.group(1));
                    int regZ = Integer.parseInt(matcher.group(2));
                    long key = ChunkPos.pack(regX, regZ);
                    if (cache != null && cache.containsKey(key)) {
                        continue;
                    }
                }
                deleteFileWithRetry(swp);
            } catch (Exception ignored) {}
        }
    }

    private static void deleteFileWithRetry(File file) {
        if (file == null || !file.exists()) return;
        for (int i = 0; i < 10; i++) {
            try {
                if (file.delete()) {
                    return;
                }
            } catch (Exception ignored) {}
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(15));
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception ignored) {
            file.deleteOnExit();
        }
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private static void syncStorageActiveRegions(World world, RegionFileStorage storage) {
        if (storage == null) return;
        try {
            storage.flush();
        } catch (Exception ignored) {}

        Map<Long, ?> cache = getRegionCacheMap(storage);
        if (cache == null) return;

        synchronized (storage) {
            Iterator<? extends Map.Entry<Long, ?>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, ?> entry = it.next();
                long key = entry.getKey();
                Object regionFile = entry.getValue();
                if (regionFile == null) continue;

                int regX = ChunkPos.getX(key);
                int regZ = ChunkPos.getZ(key);
                boolean anyLoaded = isAnyChunkLoadedInRegion(world, regX, regZ);

                if (!anyLoaded) {
                    invokeClose(regionFile);
                    it.remove();
                    cleanUpSwapFileIfPresent(regionFile);
                } else {
                    invokeSyncIfNeeded(regionFile);
                }
            }
        }
    }

    public ChunkProcessor(ChunkCleanerPlugin plugin, Config config, ChunkMapTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    public void setWebServerManager(WebServerManager webServerManager) {
        this.webServerManager = webServerManager;
    }

    public void sendFeedback(CommandSender sender, String msg) {
        if (sender != null) {
            sender.sendMessage(msg);
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                plugin.getServer().getConsoleSender().sendMessage(msg);
            }
        } else {
            plugin.getServer().getConsoleSender().sendMessage(msg);
        }
    }

    public long getCurrentCycleDeleted() { return currentCycleDeleted.get(); }

    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isDryRunCurrent() {
        return isDryRunCurrent;
    }

    public void startProcessing(CommandSender sender) {
        if ("STARTUP".equalsIgnoreCase(config.getMode())) {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§eРежим STARTUP активен. Очистка файлов произойдет автоматически при перезапуске сервера.",
                    "§eSTARTUP mode is active. Region files will be cleaned automatically during next server reboot."));
            return;
        }

        if (isRunning.compareAndSet(false, true)) {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§aЗапуск фоновой очистки чанков...",
                    "§aStarting background chunk cleanup..."));
            runRuntimeCleanup(sender, false);
        } else {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§cПроцесс уже выполняется! Используйте /cclean status или /cclean stop.",
                    "§cProcess is already running! Use /cclean status or /cclean stop."));
        }
    }

    public void startDryRun(CommandSender sender) {
        if (isRunning.compareAndSet(false, true)) {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§eЗапуск сканирования...",
                    "§eStarting scan..."));
            runRuntimeCleanup(sender, true);
        } else {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§cСначала дождитесь окончания или остановите текущий процесс (/cclean stop)!",
                    "§cPlease wait for current process to finish or stop it (/cclean stop)!"));
        }
    }

    public void printStatus(CommandSender sender) {
        if (!isRunning.get()) {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§7В данный момент очистка или сканирование не выполняются.",
                    "§7No cleanup or scan is currently running."));
            return;
        }

        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startTimeMs) / 1000);
        double speed = (double) currentCycleFiles.get() / elapsedSec;

        String modeName = isDryRunCurrent
                ? LanguageUtil.msg(config, "Сканирование", "Scan")
                : LanguageUtil.msg(config, "Runtime Очистка", "Runtime Cleanup");

        if (isDryRunCurrent) {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§eРежим: %s | Обработано файлов: %d | Найдено кандидатов: %d | Скорость: %.1f файлов/сек",
                    "§eMode: %s | Files processed: %d | Found candidates: %d | Speed: %.1f files/sec",
                    modeName,
                    currentCycleFiles.get(),
                    currentCycleCandidates.get(),
                    speed
            ));
        } else {
            sendFeedback(sender, LanguageUtil.msg(config,
                    "§eРежим: %s | Обработано файлов: %d | Удалено: %d | Пропущено в памяти: %d | Скорость: %.1f файлов/сек",
                    "§eMode: %s | Files processed: %d | Deleted: %d | Skipped in memory: %d | Speed: %.1f files/sec",
                    modeName,
                    currentCycleFiles.get(),
                    currentCycleDeleted.get(),
                    currentCycleSkipped.get(),
                    speed
            ));
        }
    }

    private void runRuntimeCleanup(CommandSender sender, boolean dryRun) {
        this.isDryRunCurrent = dryRun;
        this.currentCycleFiles.set(0);
        this.currentCycleCandidates.set(0);
        this.currentCycleDeleted.set(0);
        this.currentCycleSkipped.set(0);
        this.startTimeMs = System.currentTimeMillis();

        if (webServerManager != null && isDryRunCurrent && config.isWebAutoStart() && !webServerManager.isRunning()) {
            webServerManager.start(config.getWebPort());
            sendFeedback(sender, "§aВеб-интерфейс запущен: §e" + webServerManager.getWebUrl());
        }

        if (webServerManager != null && webServerManager.isRunning()) {
            webServerManager.broadcastStatus();
        }

        Bukkit.getAsyncScheduler().runNow(plugin, _ -> {
            long lastProgressReport = System.currentTimeMillis();
            AtomicLong chunkErrorsCount = new AtomicLong(0);

            try {
                for (World world : Bukkit.getWorlds()) {
                    if (!isRunning.get()) break;

                    ServerLevel serverLevel = ((CraftWorld) world).getHandle();
                    RegionFileStorage regionStorage = getRegionStorage(serverLevel);

                    List<File> regionDirs = getRegionDirectoriesForWorld(world.getWorldFolder());
                    for (File regionDir : regionDirs) {
                        if (!isRunning.get()) break;
                        if (!regionDir.exists() || !regionDir.isDirectory()) continue;

                        ChunkFormatHandler.cleanStaleTempFiles(regionDir);
                        if (!dryRun) {
                            cleanStaleSwapFiles(regionDir, regionStorage);
                        }

                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir.toPath(),
                                path -> {
                                    String name = path.getFileName().toString();
                                    return (name.endsWith(".b_linear") || name.endsWith(".linear") || name.endsWith(".mca"))
                                            && !name.endsWith(".tmp");
                                })) {

                            for (Path path : stream) {
                                if (!isRunning.get()) break;

                                File file = path.toFile();
                                Matcher matcher = REGION_PATTERN.matcher(file.getName());
                                if (!matcher.matches()) continue;

                                int regX = Integer.parseInt(matcher.group(1));
                                int regZ = Integer.parseInt(matcher.group(2));

                                byte[] chunkStates = new byte[1024];
                                List<Vector> badChunks = ChunkFormatHandler.processAnyRegionFile(file, config, true, chunkStates);

                                boolean anyDeletedInRegion = false;
                                for (Vector chunk : badChunks) {
                                    if (!isRunning.get()) break;
                                    int worldChunkX = (regX << 5) + chunk.getBlockX();
                                    int worldChunkZ = (regZ << 5) + chunk.getBlockZ();
                                    int localIndex = (chunk.getBlockZ() << 5) | chunk.getBlockX();

                                    if (dryRun) {
                                        currentCycleCandidates.incrementAndGet();
                                    } else {
                                        if (!world.isChunkLoaded(worldChunkX, worldChunkZ)) {
                                            ChunkPos pos = new ChunkPos(worldChunkX, worldChunkZ);
                                            if (regionStorage != null) {
                                                try {
                                                    regionStorage.write(pos, null);
                                                    currentCycleDeleted.incrementAndGet();
                                                    anyDeletedInRegion = true;
                                                    chunkStates[localIndex] = ChunkState.DELETED; // Красный
                                                } catch (Exception e) {
                                                    long errCount = chunkErrorsCount.incrementAndGet();
                                                    if (errCount <= 5) {
                                                        plugin.getLogger().warning(LanguageUtil.msg(config,
                                                                "Ошибка очистки чанка " + pos + ": " + e.getMessage(),
                                                                "Error cleaning chunk " + pos + ": " + e.getMessage()));
                                                    }
                                                }
                                            }
                                        } else {
                                            currentCycleSkipped.incrementAndGet();
                                            chunkStates[localIndex] = ChunkState.KEPT; // Зеленый
                                        }
                                    }
                                }

                                if (!dryRun && anyDeletedInRegion) {
                                    syncAndCloseRegionIfUnloaded(world, regionStorage, regX, regZ);
                                }

                                RegionChunkData regionData = null;
                                if (tracker != null) {
                                    regionData = tracker.recordRegion(world.getName(), regX, regZ, chunkStates);
                                }

                                if (webServerManager != null && webServerManager.isRunning() && regionData != null) {
                                    webServerManager.broadcastRegionUpdate(world.getName(), regX, regZ, regionData);
                                }

                                currentCycleFiles.incrementAndGet();

                                long currentDelay = config.getRegionDelayMs();
                                if (currentDelay > 0) {
                                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(currentDelay));
                                }

                                long now = System.currentTimeMillis();
                                long intervalMs = config.getProgressFeedbackSeconds() * 1000L;
                                if (now - lastProgressReport >= intervalMs) {
                                    lastProgressReport = now;
                                    long elapsedSec = Math.max(1, (now - startTimeMs) / 1000);
                                    double speed = (double) currentCycleFiles.get() / elapsedSec;

                                    if (webServerManager != null && webServerManager.isRunning()) {
                                        long delToSend = dryRun ? 0 : currentCycleDeleted.get();
                                        webServerManager.broadcastProgress(
                                                currentCycleFiles.get(),
                                                delToSend,
                                                currentCycleSkipped.get(),
                                                speed,
                                                0
                                        );
                                    }

                                    if (config.isProgressFeedbackEnabled()) {
                                        String msg = dryRun
                                                ? LanguageUtil.msg(config,
                                                        "§eПрогресс: %d файлов | Найдено: %d | Скорость: %.1f рег/сек",
                                                        "§eProgress: %d files | Found: %d | Speed: %.1f reg/sec",
                                                        currentCycleFiles.get(), currentCycleCandidates.get(), speed)
                                                : LanguageUtil.msg(config,
                                                        "§eПрогресс: %d файлов | Удалено: %d | Пропущено: %d | Скорость: %.1f рег/сек",
                                                        "§eProgress: %d files | Deleted: %d | Skipped: %d | Speed: %.1f reg/sec",
                                                        currentCycleFiles.get(), currentCycleDeleted.get(), currentCycleSkipped.get(), speed);

                                        sendFeedback(sender, msg);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            plugin.getLogger().warning(LanguageUtil.msg(config,
                                    "Ошибка чтения папки регионов " + regionDir.getPath() + ": " + e.getMessage(),
                                    "Error reading region folder " + regionDir.getPath() + ": " + e.getMessage()));
                        }
                    }

                    if (!dryRun) {
                        syncStorageActiveRegions(world, regionStorage);
                        for (File rDir : regionDirs) {
                            cleanStaleSwapFiles(rDir, regionStorage);
                        }
                    }
                }

                long totalTimeSec = Math.max(1, (System.currentTimeMillis() - startTimeMs) / 1000);
                if (dryRun) {
                    String finishMsg = LanguageUtil.msg(config,
                            "§aСканирование завершено за %d сек! Найдено чанков: %d (обработано файлов: %d).",
                            "§aScan completed in %d s! Found chunks: %d (processed files: %d).",
                            totalTimeSec, currentCycleCandidates.get(), currentCycleFiles.get());
                    sendFeedback(sender, finishMsg);
                } else {
                    String finishMsg = LanguageUtil.msg(config,
                            "§aОчистка успешно завершена за %d сек! Удалено чанков: %d (обработано файлов: %d).",
                            "§aCleanup successfully completed in %d s! Deleted chunks: %d (processed files: %d).",
                            totalTimeSec, currentCycleDeleted.get(), currentCycleFiles.get());
                    sendFeedback(sender, finishMsg);

                    if (currentCycleSkipped.get() > 0) {
                        sendFeedback(sender, LanguageUtil.msg(config,
                                "§eПропущено активных/загруженных чанков: " + currentCycleSkipped.get() + ".",
                                "§eSkipped active/loaded chunks: " + currentCycleSkipped.get() + "."));
                    }
                    if (chunkErrorsCount.get() > 5) {
                        plugin.getLogger().warning(LanguageUtil.msg(config,
                                "Всего ошибок при очистке чанков: " + chunkErrorsCount.get(),
                                "Total errors while cleaning chunks: " + chunkErrorsCount.get()));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, LanguageUtil.msg(config,
                        "Критическая ошибка при обработке: " + e.getMessage(),
                        "Critical error during processing: " + e.getMessage()), e);
            } finally {
                isRunning.set(false);
                if (webServerManager != null && webServerManager.isRunning()) {
                    webServerManager.broadcastStatus();
                }
            }
        });
    }

    public void runTaskSyncForOnLoad() {
        long start = System.currentTimeMillis();
        long totalDeleted = 0;
        int regionsProcessed = 0;

        plugin.getLogger().info(LanguageUtil.msg(config,
                "Запуск синхронной очистки файлов регионов...",
                "Starting synchronous region file cleanup..."));

        List<File> allRegionDirs = discoverAllRegionDirectories();

        plugin.getLogger().info(LanguageUtil.msg(config,
                "Обнаружено директорий с регионами: %d",
                "Found region directories: %d", allRegionDirs.size()));

        for (File regionDir : allRegionDirs) {
            if (!regionDir.exists() || !regionDir.isDirectory()) continue;

            ChunkFormatHandler.cleanStaleTempFiles(regionDir);
            cleanStaleSwapFiles(regionDir, null);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir.toPath(),
                    path -> {
                        String name = path.getFileName().toString();
                        return (name.endsWith(".b_linear") || name.endsWith(".linear") || name.endsWith(".mca"))
                                && !name.endsWith(".tmp");
                    })) {

                for (Path p : stream) {
                    File file = p.toFile();
                    try {
                        List<Vector> deleted = ChunkFormatHandler.processAnyRegionFile(file, config, false);
                        totalDeleted += deleted.size();
                        regionsProcessed++;
                    } catch (Exception e) {
                        plugin.getLogger().warning(LanguageUtil.msg(config,
                                "Ошибка обработки файла " + file.getName() + ": " + e.getMessage(),
                                "Error processing file " + file.getName() + ": " + e.getMessage()));
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning(LanguageUtil.msg(config,
                        "Ошибка обхода директории " + regionDir.getPath() + ": " + e.getMessage(),
                        "Error traversing directory " + regionDir.getPath() + ": " + e.getMessage()));
            }
        }

        long elapsedMs = System.currentTimeMillis() - start;
        plugin.getLogger().info(LanguageUtil.msg(config,
                "Очистка при запуске завершена за %d мс: обработано файлов %d, удалено чанков %d.",
                "Startup cleanup completed in %d ms: processed %d files, deleted %d chunks.",
                elapsedMs, regionsProcessed, totalDeleted));
    }

    public List<File> discoverAllRegionDirectories() {
        Set<File> regionDirs = new LinkedHashSet<>();

        try {
            for (World world : Bukkit.getWorlds()) {
                if (world != null) {
                    world.getWorldFolder();
                    collectRegionDirectoriesRecursively(world.getWorldFolder(), regionDirs, 0, 8);
                }
            }
        } catch (Throwable ignored) {}

        List<File> candidateRoots = new ArrayList<>();

        try {
            File worldContainer = plugin.getServer().getWorldContainer();
            candidateRoots.add(worldContainer);
        } catch (Throwable ignored) {}

        candidateRoots.add(new File("."));

        File defaultWorld = new File("world");
        if (defaultWorld.exists() && defaultWorld.isDirectory()) {
            candidateRoots.add(defaultWorld);
        }

        try {
            File dataFolder = plugin.getDataFolder();
            if (dataFolder.getParentFile() != null && dataFolder.getParentFile().getParentFile() != null) {
                candidateRoots.add(dataFolder.getParentFile().getParentFile());
            }
        } catch (Throwable ignored) {}

        Set<String> scannedRoots = new HashSet<>();
        for (File root : candidateRoots) {
            if (root == null || !root.exists() || !root.isDirectory()) continue;
            try {
                String canonical = root.getCanonicalPath();
                if (!scannedRoots.add(canonical)) continue;
            } catch (IOException e) {
                continue;
            }

            collectRegionDirectoriesRecursively(root, regionDirs, 0, 8);
        }

        return new ArrayList<>(regionDirs);
    }

    private void collectRegionDirectoriesRecursively(File dir, Set<File> result, int currentDepth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || currentDepth > maxDepth) return;

        String name = dir.getName();

        if (name.equalsIgnoreCase("region")) {
            if (hasRegionFiles(dir)) {
                try {
                    result.add(dir.getCanonicalFile());
                } catch (IOException e) {
                    result.add(dir);
                }
            }
            return;
        }

        String lowerName = name.toLowerCase();
        if (lowerName.equals("plugins") || lowerName.equals("logs") || lowerName.equals("crash-reports")
                || lowerName.equals("libraries") || lowerName.equals("bundler") || lowerName.equals("cache")
                || lowerName.equals(".gradle") || lowerName.equals(".idea") || lowerName.equals("versions")
                || lowerName.equals("config") || lowerName.equals(".git") || lowerName.equals("temp")
                || lowerName.equals("work")) {
            return;
        }

        File immediateRegion = new File(dir, "region");
        if (immediateRegion.exists() && immediateRegion.isDirectory() && hasRegionFiles(immediateRegion)) {
            try {
                result.add(immediateRegion.getCanonicalFile());
            } catch (IOException e) {
                result.add(immediateRegion);
            }
        }

        if (currentDepth < maxDepth) {
            File[] children = dir.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    if (!child.getName().equalsIgnoreCase("region")) {
                        collectRegionDirectoriesRecursively(child, result, currentDepth + 1, maxDepth);
                    }
                }
            }
        }
    }

    public static List<File> getRegionDirectoriesForWorld(File worldFolder) {
        Set<File> dirs = new LinkedHashSet<>();
        if (worldFolder == null || !worldFolder.exists() || !worldFolder.isDirectory()) {
            return new ArrayList<>();
        }

        collectRegionDirectoriesForFolder(worldFolder, dirs, 0, 8);
        return new ArrayList<>(dirs);
    }

    private static void collectRegionDirectoriesForFolder(File dir, Set<File> result, int currentDepth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || currentDepth > maxDepth) return;

        if (dir.getName().equalsIgnoreCase("region")) {
            if (hasRegionFiles(dir)) {
                try {
                    result.add(dir.getCanonicalFile());
                } catch (IOException e) {
                    result.add(dir);
                }
            }
            return;
        }

        File immediateRegion = new File(dir, "region");
        if (immediateRegion.exists() && immediateRegion.isDirectory() && hasRegionFiles(immediateRegion)) {
            try {
                result.add(immediateRegion.getCanonicalFile());
            } catch (IOException e) {
                result.add(immediateRegion);
            }
        }

        if (currentDepth < maxDepth) {
            File[] children = dir.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    if (!child.getName().equalsIgnoreCase("region")) {
                        collectRegionDirectoriesForFolder(child, result, currentDepth + 1, maxDepth);
                    }
                }
            }
        }
    }

    private static boolean hasRegionFiles(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return false;
        String[] files = dir.list((_, name) ->
                (name.endsWith(".b_linear") || name.endsWith(".linear") || name.endsWith(".mca"))
                        && !name.endsWith(".tmp")
        );
        return files != null && files.length > 0;
    }

    public void stopAllTasks() {
        isRunning.set(false);
        if (webServerManager != null && webServerManager.isRunning()) {
            webServerManager.broadcastStatus();
        }
    }
}