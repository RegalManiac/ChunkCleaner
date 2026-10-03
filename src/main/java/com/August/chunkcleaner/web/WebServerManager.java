package com.August.chunkcleaner.web;

import com.August.chunkcleaner.ChunkCleanerPlugin;
import com.August.chunkcleaner.config.Config;
import com.August.chunkcleaner.core.ChunkProcessor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class WebServerManager {

    private final ChunkCleanerPlugin plugin;
    private final Config config;
    private final ChunkMapTracker tracker;
    private ChunkProcessor processor;

    private HttpServer server;
    private ExecutorService executor;
    private ScheduledExecutorService heartbeatExecutor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private String authToken;
    private int boundPort = 8765;

    private volatile long lastActivityTime = System.currentTimeMillis();
    private final List<OutputStream> sseClients = new CopyOnWriteArrayList<>();

    private static class IpAttempt {
        int count = 0;
        long blockedUntil = 0;
    }
    private final Map<String, IpAttempt> failedAttempts = new ConcurrentHashMap<>();

    public WebServerManager(ChunkCleanerPlugin plugin, Config config, ChunkMapTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    public void setProcessor(ChunkProcessor processor) {
        this.processor = processor;
    }

    public synchronized boolean start(int port) {
        if (isRunning.get()) {
            return false;
        }

        try {
            byte[] tokenBytes = new byte[16];
            new SecureRandom().nextBytes(tokenBytes);
            StringBuilder sbToken = new StringBuilder(32);
            for (byte b : tokenBytes) {
                sbToken.append(String.format("%02x", b));
            }
            this.authToken = sbToken.toString();
            this.boundPort = port > 0 ? port : config.getWebPort();

            String host = config.getWebHost();
            InetSocketAddress address = host.isBlank() || "0.0.0.0".equals(host)
                    ? new InetSocketAddress(boundPort)
                    : new InetSocketAddress(InetAddress.getByName(host), boundPort);

            this.server = HttpServer.create(address, 50);
            this.executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ChunkCleaner-WebWorker");
                t.setDaemon(true);
                return t;
            });
            this.server.setExecutor(executor);

            registerRoutes();

            this.server.start();
            this.isRunning.set(true);
            this.lastActivityTime = System.currentTimeMillis();

            startHeartbeatAndIdleCheck();

            plugin.getLogger().info("Веб-интерфейс запущен на порту " + boundPort);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось запустить веб-интерфейс: " + e.getMessage(), e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        for (OutputStream os : sseClients) {
            try {
                os.close();
            } catch (Exception ignored) {}
        }
        sseClients.clear();
        failedAttempts.clear();

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }

        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception ignored) {}
            server = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        plugin.getLogger().info("Веб-интерфейс ChunkCleaner остановлен.");
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getBoundPort() {
        return boundPort;
    }

    public String getWebUrl() {
        return "http://localhost:" + boundPort + "/?token=" + authToken;
    }

    private void registerRoutes() {
        server.createContext("/", this::handleRoot);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/stream", this::handleStream);
        server.createContext("/api/regions", this::handleRegions);
        server.createContext("/api/region-data", this::handleRegionData);
        server.createContext("/api/action", this::handleAction);
    }

    private boolean checkAuthAndRateLimit(HttpExchange exchange) throws IOException {
        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();

        IpAttempt attempt = failedAttempts.get(clientIp);
        if (attempt != null && now < attempt.blockedUntil) {
            long remainingSec = (attempt.blockedUntil - now) / 1000;
            sendResponse(exchange, 429, "text/plain; charset=utf-8",
                    "429 Too Many Requests: IP blocked due to repeated unauthorized attempts. Retry in " + remainingSec + "s.");
            return false;
        }

        if (isAuthorized(exchange)) {
            failedAttempts.remove(clientIp);
            return true;
        }

        IpAttempt att = failedAttempts.computeIfAbsent(clientIp, _ -> new IpAttempt());
        att.count++;
        if (att.count >= 5) {
            att.blockedUntil = now + 15 * 60 * 1000L;
            plugin.getLogger().warning("IP " + clientIp + " временно заблокирован на 15 минут за попытки подбора токена Web UI.");
        }

        sendResponse(exchange, 401, "text/plain; charset=utf-8", "401 Unauthorized: Invalid or missing token.");
        return false;
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && "token".equalsIgnoreCase(pair[0])) {
                    if (constantTimeEquals(authToken, pair[1])) {
                        return true;
                    }
                }
            }
        }

        String authHeader = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (constantTimeEquals(authToken, authHeader)) {
            return true;
        }

        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String c : cookie.split(";")) {
                String[] pair = c.trim().split("=", 2);
                if (pair.length == 2 && "ccleaner_token".equals(pair[0])) {
                    if (constantTimeEquals(authToken, pair[1])) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        if (exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("token=")) {
            exchange.getResponseHeaders().add("Set-Cookie", "ccleaner_token=" + authToken + "; Path=/; HttpOnly; SameSite=Lax");
        }

        try (InputStream is = plugin.getResource("web/index.html")) {
            if (is == null) {
                sendResponse(exchange, 404, "text/plain; charset=utf-8", "Web UI template not found in resources!");
                return;
            }
            byte[] bytes = is.readAllBytes();
            sendResponse(exchange, 200, "text/html; charset=utf-8", bytes);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        String json = buildStatusJson();
        sendResponse(exchange, 200, "application/json", json);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        sseClients.add(os);
        sendSse(os, buildStatusJson());
    }

    private void handleRegions(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        String world = getQueryParam(exchange.getRequestURI().getQuery(), "world");
        if (world == null || world.isBlank()) {
            Set<String> worlds = tracker.getWorldNames();
            world = worlds.isEmpty() ? "world" : worlds.iterator().next();
        }

        Map<Long, RegionChunkData> regions = tracker.getRegions(world);
        StringBuilder sb = new StringBuilder(regions.size() * 32 + 32);
        sb.append("{\"world\":\"").append(world).append("\",\"regions\":[");

        boolean first = true;
        for (RegionChunkData r : regions.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(r.getRegX())
                    .append(',').append(r.getRegZ())
                    .append(',').append(r.getKeptCount())
                    .append(',').append(r.getCandidateCount())
                    .append(',').append(r.getDeletedCount())
                    .append(']');
        }
        sb.append("]}");

        sendResponse(exchange, 200, "application/json", sb.toString());
    }

    private void handleRegionData(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String world = getQueryParam(query, "world");
        String rxStr = getQueryParam(query, "rx");
        String rzStr = getQueryParam(query, "rz");

        if (world == null || rxStr == null || rzStr == null) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"missing parameters\"}");
            return;
        }

        try {
            int rx = Integer.parseInt(rxStr);
            int rz = Integer.parseInt(rzStr);
            RegionChunkData r = tracker.getRegion(world, rx, rz);
            if (r == null) {
                sendResponse(exchange, 404, "application/json", "{\"error\":\"region not found\"}");
                return;
            }

            String json = "{\"world\":\"" + world + "\",\"rx\":" + rx + ",\"rz\":" + rz +
                    ",\"b64\":\"" + r.toBase64() + "\"}";
            sendResponse(exchange, 200, "application/json", json);
        } catch (NumberFormatException e) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"invalid numbers\"}");
        }
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        updateActivity();
        if (!checkAuthAndRateLimit(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        String action = getJsonField(body);

        if (processor == null) {
            sendResponse(exchange, 500, "application/json", "{\"error\":\"processor not ready\"}");
            return;
        }

        switch (action.toLowerCase()) {
            case "scan" -> {
                processor.startDryRun(plugin.getServer().getConsoleSender());
                sendResponse(exchange, 200, "application/json", "{\"status\":\"ok\",\"action\":\"scan\"}");
            }
            case "start" -> {
                processor.startProcessing(plugin.getServer().getConsoleSender());
                sendResponse(exchange, 200, "application/json", "{\"status\":\"ok\",\"action\":\"start\"}");
            }
            case "stop" -> {
                processor.stopAllTasks();
                sendResponse(exchange, 200, "application/json", "{\"status\":\"ok\",\"action\":\"stop\"}");
            }
            case "clear_map" -> {
                tracker.clear();
                broadcastStatus();
                sendResponse(exchange, 200, "application/json", "{\"status\":\"ok\",\"action\":\"clear_map\"}");
            }
            case "shutdown" -> {
                sendResponse(exchange, 200, "application/json", "{\"status\":\"ok\",\"action\":\"shutdown\"}");
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, _ -> stop(), 500, TimeUnit.MILLISECONDS);
            }
            default -> sendResponse(exchange, 400, "application/json", "{\"error\":\"unknown action\"}");
        }
    }

    private void updateActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    private void startHeartbeatAndIdleCheck() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChunkCleaner-WebHeartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!isRunning.get()) return;

            byte[] ping = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
            for (OutputStream os : sseClients) {
                try {
                    os.write(ping);
                    os.flush();
                } catch (Exception e) {
                    sseClients.remove(os);
                }
            }

            int timeoutMin = config.getWebIdleTimeoutMinutes();
            if (timeoutMin > 0 && sseClients.isEmpty()) {
                long idleMs = System.currentTimeMillis() - lastActivityTime;
                if (idleMs > timeoutMin * 60L * 1000L) {
                    plugin.getLogger().info("Веб-интерфейс автоматически отключен из-за неактивности (" + timeoutMin + " мин).");
                    stop();
                }
            }
        }, 10, 15, TimeUnit.SECONDS);
    }

    public void broadcastRegionUpdate(String world, int regX, int regZ, RegionChunkData data) {
        if (sseClients.isEmpty()) return;
        String json = "{\"world\":\"" + world + "\",\"rx\":" + regX + ",\"rz\":" + regZ +
                ",\"k\":" + data.getKeptCount() + ",\"c\":" + data.getCandidateCount() +
                ",\"d\":" + data.getDeletedCount() +
                ",\"b64\":\"" + data.toBase64() + "\"}";
        broadcastSse("region", json);
    }

    public void broadcastProgress(long processedFiles, long deleted, long skipped, double speed, long etaSec) {
        if (sseClients.isEmpty()) return;
        String json = "{\"files\":" + processedFiles + ",\"deleted\":" + deleted +
                ",\"skipped\":" + skipped + ",\"speed\":" + String.format(Locale.US, "%.1f", speed) +
                ",\"eta\":" + etaSec + ",\"kept\":" + tracker.getGlobalKept() +
                ",\"candidates\":" + tracker.getGlobalCandidates() + "}";
        broadcastSse("progress", json);
    }

    public void broadcastStatus() {
        if (sseClients.isEmpty()) return;
        broadcastSse("status", buildStatusJson());
    }

    private void broadcastSse(String event, String data) {
        byte[] payload = ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try {
                os.write(payload);
                os.flush();
            } catch (Exception e) {
                sseClients.remove(os);
            }
        }
    }

    private void sendSse(OutputStream os, String data) {
        try {
            byte[] payload = ("event: " + "status" + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
            os.write(payload);
            os.flush();
        } catch (Exception e) {
            sseClients.remove(os);
        }
    }

    private String buildStatusJson() {
        boolean running = processor != null && processor.isRunning();
        boolean dryRun = processor != null && processor.isDryRunCurrent();
        String state = !running ? "IDLE" : (dryRun ? "SCANNING" : "CLEANING");

        long cycleDeleted = processor != null ? (processor.isDryRunCurrent() ? 0 : processor.getCurrentCycleDeleted()) : 0;

        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
                .append("\"state\":\"").append(state).append("\",")
                .append("\"isRunning\":").append(running).append(',')
                .append("\"isDryRun\":").append(dryRun).append(',')
                .append("\"totalKept\":").append(tracker.getGlobalKept()).append(',')
                .append("\"totalCandidates\":").append(tracker.getGlobalCandidates()).append(',')
                .append("\"totalDeleted\":").append(cycleDeleted).append(',')
                .append("\"totalRegions\":").append(tracker.getTotalRegionsCount()).append(',')
                .append("\"worlds\":[");

        boolean first = true;
        for (String w : tracker.getWorldNames()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(w).append('"');
        }
        sb.append("],");

        sb.append("\"colors\":{")
                .append("\"kept\":\"#22c55e\",")
                .append("\"candidate\":\"#3b82f6\",")
                .append("\"deleted\":\"#ef4444\",")
                .append("\"empty\":\"#1e293b\"")
                .append('}')
                .append('}');

        return sb.toString();
    }

    private void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendResponse(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendResponse(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String getQueryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private String getJsonField(String json) {
        if (json == null) return "";
        int idx = json.indexOf("\"" + "action" + "\"");
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx);
        if (colon == -1) return "";
        int start = json.indexOf('"', colon);
        if (start == -1) return "";
        int end = json.indexOf('"', start + 1);
        if (end == -1) return "";
        return json.substring(start + 1, end);
    }
}
