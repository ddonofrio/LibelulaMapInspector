package com.libelulamapinspector.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockHistoryEntryTest {

    @Test
    void compactsOnlyWhenTheSameActorTouchedTheBlock() {
        UUID actor = UUID.randomUUID();
        BlockHistoryEntry first = entry(actor, "minecraft:stone", null, null, null);
        BlockHistoryEntry sameActor = entry(actor, "minecraft:dirt", null, null, null);
        BlockHistoryEntry differentActor = entry(UUID.randomUUID(), "minecraft:grass_block", null, null, null);

        assertTrue(sameActor.canCompactWith(first));
        assertFalse(differentActor.canCompactWith(first));
    }

    @Test
    void estimatesMoreMemoryWhenSnapshotsAndRemovedStateExist() {
        BlockHistoryEntry plain = entry(UUID.randomUUID(), "minecraft:stone", null, null, null);
        BlockHistoryEntry rich = entry(
                UUID.randomUUID(),
                "minecraft:oak_sign[facing=north]",
                "minecraft:air",
                new SignBlockSnapshot(
                        new String[]{"front-1", "front-2", "", ""},
                        new String[]{"back-1", "", "", ""},
                        "BLACK",
                        "BLACK",
                        true,
                        false,
                        true
                ),
                new ContainerBlockSnapshot(false, 0, 0, 0, 27, List.of(new ContainerSlotSnapshot(3, new byte[]{1, 2, 3, 4})))
        );

        assertTrue(rich.estimatedBytes() > plain.estimatedBytes());
    }

    private BlockHistoryEntry entry(
            UUID actorUuid,
            String afterState,
            String removedState,
            SpecialBlockSnapshot resultSnapshot,
            SpecialBlockSnapshot removedSnapshot
    ) {
        return new BlockHistoryEntry(
                actorUuid,
                System.currentTimeMillis(),
                BlockHistoryAction.PLACE_OR_REPLACE,
                afterState,
                removedState,
                resultSnapshot,
                removedSnapshot
        );
    }
}
