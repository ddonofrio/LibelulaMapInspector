package com.libelulamapinspector.undo;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoPlannerTest {

    private final UndoPlanner undoPlanner = new UndoPlanner();

    @Test
    void removesALatestPlacementAndFallsBackToAirWhenNoEarlierHistoryExists() {
        UUID actorUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);
        BlockHistoryEntry placement = new BlockHistoryEntry(
                actorUuid,
                100L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:stone",
                null,
                null,
                null
        );

        UndoPlan undoPlan = undoPlanner.createPlan(actorUuid, 100L, Map.of(positionKey, List.of(placement)));

        assertEquals(1L, undoPlan.removedHistoryEntryCount());
        assertEquals(1, undoPlan.affectedBlockCount());
        assertEquals(1, undoPlan.worldChanges().size());
        assertEquals(UndoResolvedState.Kind.AIR, undoPlan.worldChanges().get(0).desiredState().kind());
    }

    @Test
    void removesALatestRemovalAndLeavesTheWorldStateUntouchedWhenNoEarlierHistoryExists() {
        UUID actorUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);
        BlockHistoryEntry removal = new BlockHistoryEntry(
                actorUuid,
                100L,
                BlockHistoryAction.REMOVE,
                "minecraft:air",
                "minecraft:stone",
                null,
                null
        );

        UndoPlan undoPlan = undoPlanner.createPlan(actorUuid, 100L, Map.of(positionKey, List.of(removal)));

        assertEquals(1L, undoPlan.removedHistoryEntryCount());
        assertEquals(1, undoPlan.affectedBlockCount());
        assertTrue(undoPlan.worldChanges().isEmpty());
    }

    @Test
    void preservesLaterEditsFromOtherPlayersAndAvoidsAWorldMutation() {
        UUID targetActorUuid = UUID.randomUUID();
        UUID repairingActorUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);
        BlockHistoryEntry griefPlacement = new BlockHistoryEntry(
                targetActorUuid,
                100L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:dirt",
                null,
                null,
                null
        );
        BlockHistoryEntry repairedPlacement = new BlockHistoryEntry(
                repairingActorUuid,
                200L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:stone",
                "minecraft:dirt",
                null,
                null
        );

        UndoPlan undoPlan = undoPlanner.createPlan(targetActorUuid, 150L, Map.of(positionKey, List.of(griefPlacement, repairedPlacement)));

        assertEquals(1L, undoPlan.removedHistoryEntryCount());
        assertEquals(1, undoPlan.affectedBlockCount());
        assertTrue(undoPlan.worldChanges().isEmpty());
    }

    @Test
    void restoresThePreviousVisibleStateWhenTargetRemovalWasTheLatestChange() {
        UUID placingActorUuid = UUID.randomUUID();
        UUID removingActorUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);
        BlockHistoryEntry originalPlacement = new BlockHistoryEntry(
                placingActorUuid,
                100L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:stone",
                null,
                null,
                null
        );
        BlockHistoryEntry targetRemoval = new BlockHistoryEntry(
                removingActorUuid,
                200L,
                BlockHistoryAction.REMOVE,
                "minecraft:air",
                "minecraft:stone",
                null,
                null
        );

        UndoPlan undoPlan = undoPlanner.createPlan(removingActorUuid, 200L, Map.of(positionKey, List.of(originalPlacement, targetRemoval)));

        assertEquals(1, undoPlan.worldChanges().size());
        assertEquals("minecraft:stone", undoPlan.worldChanges().get(0).desiredState().blockDataString());
    }
}
