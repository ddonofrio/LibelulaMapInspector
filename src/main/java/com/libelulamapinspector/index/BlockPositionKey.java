package com.libelulamapinspector.index;

import java.util.UUID;

/**
 * Identifies a block position across every monitored world.
 */
public record BlockPositionKey(UUID worldUuid, int x, int y, int z) {
}
