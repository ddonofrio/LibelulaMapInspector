package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;

/**
 * Identifies a block within a specific chunk.
 */
public record ChunkLocalBlockKey(int localX, int blockY, int localZ) {

    public static ChunkLocalBlockKey from(BlockPositionKey blockPositionKey) {
        return new ChunkLocalBlockKey(Math.floorMod(blockPositionKey.x(), 16), blockPositionKey.y(), Math.floorMod(blockPositionKey.z(), 16));
    }

    public int absoluteX(int chunkX) {
        return (chunkX << 4) + localX;
    }

    public int absoluteZ(int chunkZ) {
        return (chunkZ << 4) + localZ;
    }
}
