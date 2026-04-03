package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.support.PluginTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStoreServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsSnapshotsAndReloadsRecordedEventMetadata() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();

        service.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(UUID.randomUUID(), 10L, "minecraft:stone", null));
        service.append("world", new BlockPositionKey(worldUuid, 20, 70, 20), entry(UUID.randomUUID(), 20L, "minecraft:dirt", null));
        persistAll(service, 100L);

        assertTrue(Files.exists(dataFolder.resolve("chunks").resolve("store.meta.bin")));
        assertTrue(Files.exists(dataFolder.resolve("chunks").resolve("world").resolve("world.meta.bin")));
        assertTrue(Files.exists(dataFolder.resolve("chunks").resolve("world").resolve("region").resolve("r.0.0.lmi")));
        assertEquals(2L, service.getTotalRecordedEvents());
        assertTrue(service.getUsedDiskBytes() > 0L);

        BlockStoreService reloaded = createService(dataFolder, configuration -> {
        });
        assertEquals(2L, reloaded.getTotalRecordedEvents());
        assertTrue(reloaded.hasPersistedHistory());
        assertTrue(reloaded.getUsedDiskBytes() > 0L);
    }

    @Test
    void exposesBufferedHistoryFromPendingAndActiveWindows() throws IOException {
        BlockStoreService service = createService(tempDir.resolve("plugin-data"), configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(worldUuid, 2, 64, 2);
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();

        service.append("world", positionKey, entry(actorOne, 10L, "minecraft:stone", null));
        service.prepareSnapshotsForPersistence(100L);
        service.append("world", positionKey, entry(actorTwo, 20L, "minecraft:dirt", null));

        List<BlockHistoryEntry> timeline = service.copyBufferedTimeline(positionKey);
        Set<UUID> actors = service.collectBufferedActorsInBox(worldUuid, 0, 0, 0, 16, 255, 16);

        assertEquals(2, timeline.size());
        assertEquals(actorOne, timeline.get(0).actorUuid());
        assertEquals(actorTwo, timeline.get(1).actorUuid());
        assertEquals(Set.of(actorOne, actorTwo), actors);
    }

    @Test
    void cleanupByAgeRemovesExpiredChunkHistoryWithoutTouchingTheIndexDirectory() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        long oldTimestamp = System.currentTimeMillis() - Duration.ofDays(500).toMillis();

        writer.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(UUID.randomUUID(), oldTimestamp, "minecraft:stone", null));
        persistAll(writer, oldTimestamp);

        Path indexDirectory = dataFolder.resolve("index");
        Files.createDirectories(indexDirectory);
        Files.writeString(indexDirectory.resolve("bloom-filter.bin"), "keep-me");

        BlockStoreService cleaner = createService(dataFolder, configuration -> configuration.set("chunks.limits.max-record-age-days", 1));
        assertTrue(cleaner.requiresStartupCleanup());

        BlockStoreService.CleanupReport report = cleaner.performStartupCleanupIfNeeded();

        assertTrue(report.cleanupPerformed());
        assertEquals(0L, report.afterEventCount());
        assertEquals(0L, cleaner.getTotalRecordedEvents());
        assertFalse(cleaner.hasPersistedHistory());
        assertTrue(Files.exists(indexDirectory.resolve("bloom-filter.bin")));
    }

    @Test
    void cleanupBySizeDropsOldestBatchAndCompactsRemainingConsecutiveEntries() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(worldUuid, 1, 64, 1);
        String largeRemovedState = "x".repeat(400_000);
        long baseTimestamp = System.currentTimeMillis();

        writer.append("world", positionKey, entry(actor, baseTimestamp, "minecraft:stone", largeRemovedState));
        persistAll(writer, baseTimestamp);
        writer.append("world", positionKey, entry(actor, baseTimestamp + 1, "minecraft:dirt", largeRemovedState));
        persistAll(writer, baseTimestamp + 1);
        writer.append("world", positionKey, entry(actor, baseTimestamp + 2, "minecraft:glass", largeRemovedState));
        persistAll(writer, baseTimestamp + 2);

        BlockStoreService cleaner = createService(dataFolder, configuration -> configuration.set("chunks.limits.max-total-size-megabytes", 1));
        assertTrue(cleaner.requiresStartupCleanup());

        BlockStoreService.CleanupReport report = cleaner.performStartupCleanupIfNeeded();

        assertTrue(report.cleanupPerformed());
        assertEquals(1L, cleaner.getTotalRecordedEvents());
        assertTrue(cleaner.getUsedDiskBytes() <= 1L * 1024L * 1024L);

        BlockStoreService reloaded = createService(dataFolder, configuration -> configuration.set("chunks.limits.max-total-size-megabytes", 1));
        assertEquals(1L, reloaded.getTotalRecordedEvents());
    }

    private BlockStoreService createService(Path dataFolder, Consumer<YamlConfiguration> configurer) throws IOException {
        JavaPlugin plugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configurer);
        BlockStoreService service = new BlockStoreService(plugin);
        service.initialize();
        return service;
    }

    private void persistAll(BlockStoreService service, long createdAt) throws IOException {
        List<BlockStoreFlushSnapshot> snapshots = service.prepareSnapshotsForPersistence(createdAt);
        for (BlockStoreFlushSnapshot snapshot : snapshots) {
            service.persistSnapshot(snapshot);
            service.markSnapshotPersisted(snapshot.snapshotId());
        }
    }

    private BlockHistoryEntry entry(UUID actorUuid, long timestamp, String afterState, String removedState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestamp,
                BlockHistoryAction.PLACE_OR_REPLACE,
                afterState,
                removedState,
                null,
                null
        );
    }
}
