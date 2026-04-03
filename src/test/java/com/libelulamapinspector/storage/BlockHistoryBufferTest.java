package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockHistoryBufferTest {

    @Test
    void timelineCompactsConsecutiveEntriesFromTheSameActor() {
        BlockHistoryTimeline timeline = new BlockHistoryTimeline();
        UUID actor = UUID.randomUUID();

        timeline.appendCompacted(entry(actor, 10L, "minecraft:stone"));
        timeline.appendCompacted(entry(actor, 20L, "minecraft:dirt"));
        timeline.appendCompacted(entry(UUID.randomUUID(), 30L, "minecraft:glass"));

        assertEquals(2, timeline.entries().size());
        assertEquals("minecraft:dirt", timeline.entries().get(0).afterBlockDataString());
        assertEquals("minecraft:glass", timeline.entries().get(1).afterBlockDataString());
    }

    @Test
    void collectsActorsAcrossPendingChunksAndWorldsInsideTheRequestedBox() {
        BlockHistoryBuffer buffer = new BlockHistoryBuffer();
        UUID worldOne = UUID.randomUUID();
        UUID worldTwo = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        UUID actorThree = UUID.randomUUID();

        buffer.append(new BlockPositionKey(worldOne, 1, 64, 1), entry(actorOne, 10L, "minecraft:stone"));
        buffer.append(new BlockPositionKey(worldOne, 17, 70, 17), entry(actorTwo, 20L, "minecraft:dirt"));
        buffer.append(new BlockPositionKey(worldTwo, 1, 64, 1), entry(actorThree, 30L, "minecraft:glass"));

        List<BlockHistoryEntry> timeline = buffer.copyTimeline(new BlockPositionKey(worldOne, 1, 64, 1));
        Set<UUID> actors = buffer.collectActorsInBox(worldOne, 0, 0, 0, 32, 255, 32);

        assertEquals(1, timeline.size());
        assertEquals(Set.of(actorOne, actorTwo), actors);
    }

    private BlockHistoryEntry entry(UUID actorUuid, long timestamp, String afterState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestamp,
                BlockHistoryAction.PLACE_OR_REPLACE,
                afterState,
                null,
                null,
                null
        );
    }
}
