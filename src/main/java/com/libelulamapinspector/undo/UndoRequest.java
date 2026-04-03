package com.libelulamapinspector.undo;

import java.util.Objects;
import java.util.UUID;

/**
 * Captures one pending undo request awaiting confirmation or execution.
 */
public record UndoRequest(
        UUID targetPlayerUuid,
        String targetPlayerName,
        UndoScope scope,
        int maxBlocksPerTick
) {

    public UndoRequest {
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid");
        Objects.requireNonNull(targetPlayerName, "targetPlayerName");
        Objects.requireNonNull(scope, "scope");
    }
}
