package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Active or pending in-memory block history buffer.
 */
final class BlockHistoryBuffer {

    private static final int BUFFER_OVERHEAD = 128;
    private static final int WORLD_CHUNK_MAP_ENTRY_OVERHEAD = 56;

    private final Map<WorldChunkKey, ChunkHistoryBuffer> worldChunks = new HashMap<>();
    private long estimatedBytes = BUFFER_OVERHEAD;

    long append(BlockPositionKey positionKey, BlockHistoryEntry entry) {
        WorldChunkKey chunkKey = WorldChunkKey.from(positionKey);
        ChunkHistoryBuffer chunkHistoryBuffer = worldChunks.get(chunkKey);
        long delta = 0L;
        if (chunkHistoryBuffer == null) {
            chunkHistoryBuffer = new ChunkHistoryBuffer();
            worldChunks.put(chunkKey, chunkHistoryBuffer);
            delta += WORLD_CHUNK_MAP_ENTRY_OVERHEAD + chunkHistoryBuffer.estimatedBytes();
        }

        delta += chunkHistoryBuffer.append(ChunkLocalBlockKey.from(positionKey), entry);
        estimatedBytes += delta;
        return delta;
    }

    boolean isEmpty() {
        return worldChunks.isEmpty();
    }

    long estimatedBytes() {
        return estimatedBytes;
    }

    List<BlockHistoryEntry> copyTimeline(BlockPositionKey positionKey) {
        ChunkHistoryBuffer chunkHistoryBuffer = worldChunks.get(WorldChunkKey.from(positionKey));
        return chunkHistoryBuffer == null ? List.of() : chunkHistoryBuffer.copyTimeline(ChunkLocalBlockKey.from(positionKey));
    }

    Set<UUID> collectActorsInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Set<UUID> actors = new LinkedHashSet<>();
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        for (Map.Entry<WorldChunkKey, ChunkHistoryBuffer> entry : worldChunks.entrySet()) {
            WorldChunkKey chunkKey = entry.getKey();
            if (!chunkKey.worldUuid().equals(worldUuid)) {
                continue;
            }

            if (chunkKey.chunkX() < minChunkX || chunkKey.chunkX() > maxChunkX || chunkKey.chunkZ() < minChunkZ || chunkKey.chunkZ() > maxChunkZ) {
                continue;
            }

            actors.addAll(entry.getValue().collectActorsInBox(chunkKey.chunkX(), chunkKey.chunkZ(), minX, minY, minZ, maxX, maxY, maxZ));
        }

        return actors;
    }

    Map<WorldChunkKey, ChunkHistoryBuffer> worldChunks() {
        return worldChunks;
    }
}
