package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;

import java.util.UUID;

/**
 * Identifies a Minecraft chunk inside a world.
 */
public record WorldChunkKey(UUID worldUuid, int chunkX, int chunkZ) {

    public static WorldChunkKey from(BlockPositionKey blockPositionKey) {
        return new WorldChunkKey(blockPositionKey.worldUuid(), Math.floorDiv(blockPositionKey.x(), 16), Math.floorDiv(blockPositionKey.z(), 16));
    }
}
