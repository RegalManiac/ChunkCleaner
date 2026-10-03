# ChunkCleaner

A lightweight chunk management plugin for Luminol/Folia. It scans and safely deletes uninhabited or briefly visited chunks based on `InhabitedTime`, reclaiming disk space without damaging world history.

### Key Features

- **Multiple Region Formats**: Support for Vanilla Anvil (`.mca`), standard Linear (`.linear`), and Buffered Linear (`.b_linear`).
- **Safe Operations**: Skips currently loaded chunks and active player zones, properly flushes memory caches, and cleans up temporary `.swp` swap files on `b_linear`.
- **Live Web Interface**: Built-in map dashboard to visualize scans and cleanup progress directly in your browser.
- **Asynchronous & Optimized**: Processing runs in background threads without blocking the main server tick loop.

### Commands

- `/chunkcleaner scan` — Run scan to identify candidate chunks.
- `/chunkcleaner clean` — Start deleting unused chunks.
- `/chunkcleaner stop` — Stop the ongoing task.
- `/chunkcleaner reload` — Reload the configuration file.

### Configuration

Main settings can be found in `plugins/ChunkCleaner/config.yml`
