package com.libelulamapinspector.undo;

import com.libelulamapinspector.storage.SpecialBlockSnapshot;

/**
 * Describes the desired visible state for a block after undo planning.
 */
public record UndoResolvedState(
        Kind kind,
        String blockDataString,
        SpecialBlockSnapshot snapshot
) {

    public enum Kind {
        AIR,
        BLOCK_STATE
    }

    public static UndoResolvedState air() {
        return new UndoResolvedState(Kind.AIR, "minecraft:air", null);
    }

    public static UndoResolvedState blockState(String blockDataString, SpecialBlockSnapshot snapshot) {
        return new UndoResolvedState(Kind.BLOCK_STATE, blockDataString, snapshot);
    }
}
