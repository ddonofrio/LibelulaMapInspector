package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.undo.UndoScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.libelulamapinspector.storage.StorageTestSupport.createBlockStoreService;
import static com.libelulamapinspector.storage.StorageTestSupport.persistAll;
import static com.libelulamapinspector.storage.StorageTestSupport.readAllEntries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStoreServiceUndoTest {

    @TempDir
    Path tempDir;

    @Test
    void rewriteActorHistoryInScopeRemovesOnlyMatchingEntriesInsideTheSelectedScopeAndCutoff() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockStoreService service = createBlockStoreService(dataFolder, configuration -> {
        });
        UUID worldUuid = UUID.randomUUID();
        UUID targetActor = UUID.randomUUID();
        UUID otherActor = UUID.randomUUID();

        BlockPositionKey persistedMixedBlock = new BlockPositionKey(worldUuid, 10, 64, 10);
        BlockPositionKey persistedOutsideBlock = new BlockPositionKey(worldUuid, 200, 64, 200);
        BlockPositionKey bufferedRemovedBlock = new BlockPositionKey(worldUuid, 11, 64, 10);
        BlockPositionKey bufferedNewerBlock = new BlockPositionKey(worldUuid, 12, 64, 10);

        service.append("world", persistedMixedBlock, new BlockHistoryEntry(
                targetActor,
                100L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:dirt",
                null,
                null,
                null
        ));
        service.append("world", persistedMixedBlock, new BlockHistoryEntry(
                otherActor,
                200L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:stone",
                "minecraft:dirt",
                null,
                null
        ));
        service.append("world", persistedOutsideBlock, new BlockHistoryEntry(
                targetActor,
                150L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:glass",
                null,
                null,
                null
        ));
        persistAll(service, 500L);

        service.append("world", bufferedRemovedBlock, new BlockHistoryEntry(
                targetActor,
                250L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:sand",
                null,
                null,
                null
        ));
        service.append("world", bufferedNewerBlock, new BlockHistoryEntry(
                targetActor,
                600L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:oak_planks",
                null,
                null,
                null
        ));

        UndoScope undoScope = UndoScope.radius(worldUuid, "world", 10, 64, 10, 10);
        service.rewriteActorHistoryInScope(targetActor, undoScope, 400L);

        Map<BlockPositionKey, List<BlockHistoryEntry>> bufferedHistories = service.copyBufferedHistoriesInScope(undoScope);
        Map<BlockPositionKey, List<BlockHistoryEntry>> persistedHistories = service.readPersistedHistoriesInScope(UndoScope.world(worldUuid, "world"));

        assertFalse(bufferedHistories.containsKey(bufferedRemovedBlock));
        assertEquals(1, bufferedHistories.get(bufferedNewerBlock).size());
        assertEquals(600L, bufferedHistories.get(bufferedNewerBlock).get(0).timestampEpochMillisUtc());

        assertEquals(1, persistedHistories.get(persistedMixedBlock).size());
        assertEquals(otherActor, persistedHistories.get(persistedMixedBlock).get(0).actorUuid());
        assertEquals(1, persistedHistories.get(persistedOutsideBlock).size());
        assertEquals(targetActor, persistedHistories.get(persistedOutsideBlock).get(0).actorUuid());

        List<StorageTestSupport.DecodedEntry> persistedEntries = readAllEntries(dataFolder);
        assertEquals(2, persistedEntries.size());
        assertTrue(persistedEntries.stream().anyMatch(entry -> entry.positionKey().equals(persistedMixedBlock) && entry.actorUuid().equals(otherActor)));
        assertTrue(persistedEntries.stream().anyMatch(entry -> entry.positionKey().equals(persistedOutsideBlock) && entry.actorUuid().equals(targetActor)));
    }
}
