package com.libelulamapinspector.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Holds the compacted per-block history tracked in memory.
 */
final class BlockHistoryTimeline {

    private static final int TIMELINE_OVERHEAD = 32;
    private static final int ENTRY_REFERENCE_OVERHEAD = 12;

    private final List<BlockHistoryEntry> entries = new ArrayList<>();
    private long estimatedBytes = TIMELINE_OVERHEAD;

    long appendCompacted(BlockHistoryEntry entry) {
        if (!entries.isEmpty()) {
            BlockHistoryEntry lastEntry = entries.get(entries.size() - 1);
            if (entry.canCompactWith(lastEntry)) {
                entries.set(entries.size() - 1, entry);
                long delta = entry.estimatedBytes() - lastEntry.estimatedBytes();
                estimatedBytes += delta;
                return delta;
            }
        }

        entries.add(entry);
        long delta = entry.estimatedBytes() + ENTRY_REFERENCE_OVERHEAD;
        estimatedBytes += delta;
        return delta;
    }

    long estimatedBytes() {
        return estimatedBytes;
    }

    List<BlockHistoryEntry> entries() {
        return Collections.unmodifiableList(entries);
    }
}
