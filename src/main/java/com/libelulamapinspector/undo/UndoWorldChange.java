package com.libelulamapinspector.undo;

import com.libelulamapinspector.index.BlockPositionKey;

import java.util.Objects;

/**
 * One world mutation that should be applied during an undo operation.
 */
public record UndoWorldChange(
        BlockPositionKey positionKey,
        UndoResolvedState desiredState
) {

    public UndoWorldChange {
        Objects.requireNonNull(positionKey, "positionKey");
        Objects.requireNonNull(desiredState, "desiredState");
    }
}
