package com.libelulamapinspector.storage;

import java.util.UUID;

/**
 * Identifies a Minecraft-style region file inside a world.
 */
public record WorldRegionKey(UUID worldUuid, int regionX, int regionZ) {

    public static WorldRegionKey from(WorldChunkKey chunkKey) {
        return new WorldRegionKey(chunkKey.worldUuid(), Math.floorDiv(chunkKey.chunkX(), 32), Math.floorDiv(chunkKey.chunkZ(), 32));
    }
}
