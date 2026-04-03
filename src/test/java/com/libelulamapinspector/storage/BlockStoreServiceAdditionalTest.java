package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.libelulamapinspector.storage.StorageTestSupport.createBlockStoreService;
import static com.libelulamapinspector.storage.StorageTestSupport.entry;
import static com.libelulamapinspector.storage.StorageTestSupport.persistAll;
import static com.libelulamapinspector.storage.StorageTestSupport.readAllEntries;
import static com.libelulamapinspector.storage.StorageTestSupport.readStoreEventCount;
import static com.libelulamapinspector.storage.StorageTestSupport.regionFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStoreServiceAdditionalTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToDefaultChunkLimitsAndBufferBudgetWhenConfigurationIsInvalid() throws IOException {
        BlockStoreService service = createBlockStoreService(tempDir.resolve("plugin-data"), configuration -> {
            configuration.set("index.buffer-megabytes", 0);
            configuration.set("chunks.limits.max-total-size-megabytes", 0);
            configuration.set("chunks.limits.max-record-age-days", 0);
        });

        assertEquals(50, service.getBufferMegabytes());
        assertEquals(50L * 1024L * 1024L, service.getBufferBytesLimit());
        assertEquals(1024, service.getMaxTotalSizeMegabytes());
        assertEquals(365, service.getMaxRecordAgeDays());
    }

    @Test
    void appendSignalsWhenTheWriteBufferBudgetHasBeenExceeded() throws IOException {
        BlockStoreService service = createBlockStoreService(tempDir.resolve("plugin-data"), configuration ->
                configuration.set("index.buffer-megabytes", 1));

        boolean shouldPersist = service.append(
                "world",
                new BlockPositionKey(UUID.randomUUID(), 1, 64, 1),
                entry(UUID.randomUUID(), 10L, "minecraft:stone", "x".repeat(700_000))
        );

        assertTrue(shouldPersist);
        assertTrue(service.getEstimatedBufferedBytes() >= service.getBufferBytesLimit());
    }

    @Test
    void createsDistinctWorldDirectoriesWhenSanitizedWorldNamesCollide() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });

        service.append("creative/world", new BlockPositionKey(UUID.randomUUID(), 1, 64, 1), entry(UUID.randomUUID(), 10L, "minecraft:stone", null));
        service.append("creative:world", new BlockPositionKey(UUID.randomUUID(), 2, 64, 2), entry(UUID.randomUUID(), 20L, "minecraft:dirt", null));
        persistAll(service, 100L);

        List<String> directories;
        try (java.util.stream.Stream<Path> stream = Files.list(dataFolder.resolve("chunks"))) {
            directories = stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertEquals(List.of("creative_world", "creative_world-1"), directories);
    }

    @Test
    void createsMultipleRegionFilesForEntriesThatLandInDifferentRegions() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();

        service.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(UUID.randomUUID(), 10L, "minecraft:stone", null));
        service.append("world", new BlockPositionKey(worldUuid, 600, 70, 1), entry(UUID.randomUUID(), 20L, "minecraft:dirt", null));
        service.append("world", new BlockPositionKey(worldUuid, 1, 80, 600), entry(UUID.randomUUID(), 30L, "minecraft:glass", null));
        persistAll(service, 100L);

        List<String> fileNames = regionFiles(dataFolder).stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertEquals(List.of("r.0.0.lmi", "r.0.1.lmi", "r.1.0.lmi"), fileNames);
    }

    @Test
    void readsOnlyTheRequestedPersistedTimelineForOneBlock() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        BlockPositionKey targetBlock = new BlockPositionKey(worldUuid, 1, 64, 1);

        writer.append("world", targetBlock, entry(actorOne, 10L, "minecraft:stone", null));
        persistAll(writer, 100L);
        writer.append("world", new BlockPositionKey(worldUuid, 2, 64, 2), entry(UUID.randomUUID(), 15L, "minecraft:dirt", null));
        persistAll(writer, 200L);
        writer.append("world", targetBlock, entry(actorTwo, 20L, "minecraft:glass", null));
        persistAll(writer, 300L);

        BlockStoreService reader = createBlockStoreService(dataFolder, configuration -> {
        });
        List<BlockHistoryEntry> timeline = reader.readPersistedTimeline(targetBlock);

        assertEquals(2, timeline.size());
        assertEquals(actorOne, timeline.get(0).actorUuid());
        assertEquals(actorTwo, timeline.get(1).actorUuid());
        assertEquals(List.of("minecraft:stone", "minecraft:glass"),
                timeline.stream().map(BlockHistoryEntry::afterBlockDataString).toList());
    }

    @Test
    void collectsPersistedActorsAcrossChunksAndRegionsInsideTheRequestedBox() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        UUID actorThree = UUID.randomUUID();
        UUID actorOutside = UUID.randomUUID();

        writer.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(actorOne, 10L, "minecraft:stone", null));
        writer.append("world", new BlockPositionKey(worldUuid, 20, 64, 20), entry(actorTwo, 20L, "minecraft:dirt", null));
        writer.append("world", new BlockPositionKey(worldUuid, 600, 64, 600), entry(actorThree, 30L, "minecraft:glass", null));
        writer.append("world", new BlockPositionKey(worldUuid, 900, 200, 900), entry(actorOutside, 40L, "minecraft:sand", null));
        persistAll(writer, 100L);

        BlockStoreService reader = createBlockStoreService(dataFolder, configuration -> {
        });
        Set<UUID> actors = reader.collectPersistedActorsInBox(worldUuid, 0, 0, 0, 620, 100, 620);

        assertEquals(Set.of(actorOne, actorTwo, actorThree), actors);
    }

    @Test
    void roundTripsRemovedStateAndSignSnapshotsThroughTheChunkFiles() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        SignBlockSnapshot signSnapshot = new SignBlockSnapshot(
                new String[]{"front-1", "front-2", "", ""},
                new String[]{"back-1", "", "", ""},
                "BLACK",
                "BLUE",
                true,
                false,
                true
        );

        service.append(
                "world",
                new BlockPositionKey(worldUuid, 1, 64, 1),
                entry(actorUuid, 1234L, "minecraft:oak_sign[facing=north]", "minecraft:air", signSnapshot, signSnapshot)
        );
        persistAll(service, 2000L);

        StorageTestSupport.DecodedEntry stored = readAllEntries(dataFolder).get(0);
        StorageTestSupport.DecodedSignSnapshot resultSnapshot = (StorageTestSupport.DecodedSignSnapshot) stored.resultSnapshot();
        StorageTestSupport.DecodedSignSnapshot removedSnapshot = (StorageTestSupport.DecodedSignSnapshot) stored.removedSnapshot();

        assertEquals(actorUuid, stored.actorUuid());
        assertEquals("minecraft:oak_sign[facing=north]", stored.afterBlockDataString());
        assertEquals("minecraft:air", stored.removedBlockDataString());
        assertArrayEquals(new String[]{"front-1", "front-2", "", ""}, resultSnapshot.frontLines());
        assertArrayEquals(new String[]{"back-1", "", "", ""}, resultSnapshot.backLines());
        assertEquals("BLACK", resultSnapshot.frontColorName());
        assertEquals("BLUE", resultSnapshot.backColorName());
        assertTrue(resultSnapshot.frontGlowingText());
        assertFalse(resultSnapshot.backGlowingText());
        assertTrue(resultSnapshot.waxed());
        assertArrayEquals(resultSnapshot.frontLines(), removedSnapshot.frontLines());
    }

    @Test
    void roundTripsContainerSnapshotsIncludingDoubleChestOffsets() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        ContainerBlockSnapshot containerSnapshot = new ContainerBlockSnapshot(
                true,
                1,
                0,
                0,
                54,
                List.of(
                        new ContainerSlotSnapshot(3, new byte[]{1, 2, 3}),
                        new ContainerSlotSnapshot(10, new byte[]{4, 5})
                )
        );

        service.append(
                "world",
                new BlockPositionKey(worldUuid, 1, 64, 1),
                entry(actorUuid, 1234L, "minecraft:chest[facing=north,type=left]", "minecraft:air", null, containerSnapshot)
        );
        persistAll(service, 2000L);

        StorageTestSupport.DecodedEntry stored = readAllEntries(dataFolder).get(0);
        StorageTestSupport.DecodedContainerSnapshot removedSnapshot = (StorageTestSupport.DecodedContainerSnapshot) stored.removedSnapshot();

        assertEquals(actorUuid, stored.actorUuid());
        assertTrue(removedSnapshot.doubleChest());
        assertEquals(1, removedSnapshot.partnerOffsetX());
        assertEquals(0, removedSnapshot.partnerOffsetY());
        assertEquals(0, removedSnapshot.partnerOffsetZ());
        assertEquals(54, removedSnapshot.inventorySize());
        assertEquals(2, removedSnapshot.slots().size());
        assertEquals(3, removedSnapshot.slots().get(0).slotIndex());
        assertArrayEquals(new byte[]{1, 2, 3}, removedSnapshot.slots().get(0).serializedItemBytes());
    }

    @Test
    void cleanupByAgeFiltersMixedBatchesInsteadOfDroppingTheWholeBatch() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        long oldTimestamp = System.currentTimeMillis() - Duration.ofDays(500).toMillis();
        long recentTimestamp = System.currentTimeMillis();

        writer.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(actorUuid, oldTimestamp, "minecraft:stone", null));
        writer.append("world", new BlockPositionKey(worldUuid, 2, 64, 2), entry(actorUuid, recentTimestamp, "minecraft:dirt", null));
        persistAll(writer, recentTimestamp);

        BlockStoreService cleaner = createBlockStoreService(dataFolder, configuration -> configuration.set("chunks.limits.max-record-age-days", 365));
        BlockStoreService.CleanupReport report = cleaner.performStartupCleanupIfNeeded();

        List<StorageTestSupport.DecodedEntry> entries = readAllEntries(dataFolder);
        assertTrue(report.cleanupPerformed());
        assertEquals(1L, cleaner.getTotalRecordedEvents());
        assertEquals(1L, readStoreEventCount(dataFolder));
        assertEquals(1, entries.size());
        assertEquals("minecraft:dirt", entries.get(0).afterBlockDataString());
        assertEquals(recentTimestamp, entries.get(0).timestampEpochMillisUtc());
    }

    @Test
    void cleanupBySizeDropsTheOldestBatchesEvenWhenTheyAreSplitAcrossRegionFiles() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService writer = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        String largeRemovedState = "x".repeat(400_000);
        long baseTimestamp = System.currentTimeMillis();

        writer.append("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(UUID.randomUUID(), baseTimestamp, "minecraft:stone", largeRemovedState));
        persistAll(writer, baseTimestamp);
        writer.append("world", new BlockPositionKey(worldUuid, 600, 64, 1), entry(UUID.randomUUID(), baseTimestamp + 1, "minecraft:dirt", largeRemovedState));
        persistAll(writer, baseTimestamp + 1);
        writer.append("world", new BlockPositionKey(worldUuid, 1, 64, 600), entry(UUID.randomUUID(), baseTimestamp + 2, "minecraft:glass", largeRemovedState));
        persistAll(writer, baseTimestamp + 2);

        BlockStoreService cleaner = createBlockStoreService(dataFolder, configuration -> configuration.set("chunks.limits.max-total-size-megabytes", 1));
        BlockStoreService.CleanupReport report = cleaner.performStartupCleanupIfNeeded();
        List<StorageTestSupport.DecodedEntry> entries = readAllEntries(dataFolder);

        assertTrue(report.cleanupPerformed());
        assertEquals(2, entries.size());
        assertEquals(Set.of(baseTimestamp + 1, baseTimestamp + 2),
                entries.stream().map(StorageTestSupport.DecodedEntry::timestampEpochMillisUtc).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2L, readStoreEventCount(dataFolder));
        assertTrue(cleaner.getUsedDiskBytes() <= 1L * 1024L * 1024L);
    }

    @Test
    void deleteStorageFilesRemovesTheChunksDirectory() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });
        service.append("world", new BlockPositionKey(UUID.randomUUID(), 1, 64, 1), entry(UUID.randomUUID(), 10L, "minecraft:stone", null));
        persistAll(service, 100L);

        service.deleteStorageFiles();

        assertTrue(Files.notExists(dataFolder.resolve("chunks")));
    }
}
