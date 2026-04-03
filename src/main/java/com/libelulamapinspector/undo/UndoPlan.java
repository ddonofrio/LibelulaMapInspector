package com.libelulamapinspector.undo;

import java.util.List;

/**
 * Result of planning one undo request before world mutation and history rewrite.
 */
public record UndoPlan(
        List<UndoWorldChange> worldChanges,
        long removedHistoryEntryCount,
        int affectedBlockCount
) {

    public UndoPlan {
        worldChanges = List.copyOf(worldChanges);
    }

    public boolean isEmpty() {
        return removedHistoryEntryCount <= 0;
    }
}
