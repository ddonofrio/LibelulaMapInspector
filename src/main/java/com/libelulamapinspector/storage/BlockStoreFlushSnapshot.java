package com.libelulamapinspector.storage;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of one block history flush window.
 */
public record BlockStoreFlushSnapshot(
        long snapshotId,
        long createdAtEpochMillisUtc,
        Map<UUID, String> worldNames,
        BlockHistoryBuffer buffer
) {
}
