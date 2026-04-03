package com.libelulamapinspector.storage;

/**
 * Describes the final state transition recorded for a block.
 */
public enum BlockHistoryAction {
    PLACE_OR_REPLACE(1),
    REMOVE(2),
    STATE_UPDATE(3);

    private final int id;

    BlockHistoryAction(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
