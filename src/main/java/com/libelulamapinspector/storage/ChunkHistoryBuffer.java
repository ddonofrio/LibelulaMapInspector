package com.libelulamapinspector.storage;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores all buffered block timelines for a single chunk.
 */
final class ChunkHistoryBuffer {

    private static final int CHUNK_OVERHEAD = 96;
    private static final int MAP_ENTRY_OVERHEAD = 48;
    private static final int BLOCK_KEY_OVERHEAD = 24;

    private final Map<ChunkLocalBlockKey, BlockHistoryTimeline> timelines = new HashMap<>();
    private long estimatedBytes = CHUNK_OVERHEAD;

    long append(ChunkLocalBlockKey key, BlockHistoryEntry entry) {
        BlockHistoryTimeline timeline = timelines.get(key);
        long delta = 0L;
        if (timeline == null) {
            timeline = new BlockHistoryTimeline();
            timelines.put(key, timeline);
            delta += MAP_ENTRY_OVERHEAD + BLOCK_KEY_OVERHEAD + timeline.estimatedBytes();
        }

        delta += timeline.appendCompacted(entry);
        estimatedBytes += delta;
        return delta;
    }

    List<BlockHistoryEntry> copyTimeline(ChunkLocalBlockKey key) {
        BlockHistoryTimeline timeline = timelines.get(key);
        return timeline == null ? List.of() : List.copyOf(timeline.entries());
    }

    Set<UUID> collectActorsInBox(int chunkX, int chunkZ, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Set<UUID> actors = new LinkedHashSet<>();
        for (Map.Entry<ChunkLocalBlockKey, BlockHistoryTimeline> entry : timelines.entrySet()) {
            ChunkLocalBlockKey key = entry.getKey();
            int absoluteX = key.absoluteX(chunkX);
            int absoluteZ = key.absoluteZ(chunkZ);
            if (absoluteX < minX || absoluteX > maxX || key.blockY() < minY || key.blockY() > maxY || absoluteZ < minZ || absoluteZ > maxZ) {
                continue;
            }

            for (BlockHistoryEntry historyEntry : entry.getValue().entries()) {
                actors.add(historyEntry.actorUuid());
            }
        }
        return actors;
    }

    long estimatedBytes() {
        return estimatedBytes;
    }

    Map<ChunkLocalBlockKey, BlockHistoryTimeline> timelines() {
        return timelines;
    }
}
