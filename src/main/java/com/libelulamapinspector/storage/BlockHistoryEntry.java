package com.libelulamapinspector.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents one stored block mutation attributed to a player.
 */
public final class BlockHistoryEntry {

    private static final int BASE_ESTIMATED_BYTES = 96;

    private final UUID actorUuid;
    private final long timestampEpochMillisUtc;
    private final BlockHistoryAction action;
    private final String afterBlockDataString;
    private final String removedBlockDataString;
    private final SpecialBlockSnapshot resultSnapshot;
    private final SpecialBlockSnapshot removedSnapshot;
    private final int estimatedBytes;

    public BlockHistoryEntry(
            UUID actorUuid,
            long timestampEpochMillisUtc,
            BlockHistoryAction action,
            String afterBlockDataString,
            String removedBlockDataString,
            SpecialBlockSnapshot resultSnapshot,
            SpecialBlockSnapshot removedSnapshot
    ) {
        this.actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        this.timestampEpochMillisUtc = timestampEpochMillisUtc;
        this.action = Objects.requireNonNull(action, "action");
        this.afterBlockDataString = Objects.requireNonNull(afterBlockDataString, "afterBlockDataString");
        this.removedBlockDataString = removedBlockDataString;
        this.resultSnapshot = resultSnapshot;
        this.removedSnapshot = removedSnapshot;
        this.estimatedBytes = estimateBytes();
    }

    public UUID actorUuid() {
        return actorUuid;
    }

    public long timestampEpochMillisUtc() {
        return timestampEpochMillisUtc;
    }

    public BlockHistoryAction action() {
        return action;
    }

    public String afterBlockDataString() {
        return afterBlockDataString;
    }

    public String removedBlockDataString() {
        return removedBlockDataString;
    }

    public SpecialBlockSnapshot resultSnapshot() {
        return resultSnapshot;
    }

    public SpecialBlockSnapshot removedSnapshot() {
        return removedSnapshot;
    }

    public int estimatedBytes() {
        return estimatedBytes;
    }

    public boolean canCompactWith(BlockHistoryEntry previousEntry) {
        return actorUuid.equals(previousEntry.actorUuid);
    }

    private int estimateBytes() {
        int size = BASE_ESTIMATED_BYTES;
        size += 8 + (afterBlockDataString.length() * 2);
        size += removedBlockDataString != null ? 8 + (removedBlockDataString.length() * 2) : 0;
        size += resultSnapshot != null ? resultSnapshot.estimatedBytes() : 0;
        size += removedSnapshot != null ? removedSnapshot.estimatedBytes() : 0;
        return size;
    }
}
