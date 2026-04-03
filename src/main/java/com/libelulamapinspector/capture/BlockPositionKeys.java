package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/**
 * Converts Bukkit block objects into persisted position keys.
 */
public final class BlockPositionKeys {

    private BlockPositionKeys() {
    }

    public static BlockPositionKey from(Block block) {
        return new BlockPositionKey(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }

    public static BlockPositionKey from(BlockState blockState) {
        return from(blockState.getBlock());
    }

    public static BlockPositionKey from(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("location world cannot be null");
        }

        return new BlockPositionKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }
}
