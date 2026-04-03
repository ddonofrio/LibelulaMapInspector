package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TntAttributionServiceTest {

    @Test
    void tracksPrimedTntFromSpawnToExplosion() {
        AtomicLong now = new AtomicLong(1000L);
        TntAttributionService service = new TntAttributionService(now::get, 10_000L);
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        UUID tntEntityUuid = UUID.randomUUID();
        BlockPositionKey primedBlock = new BlockPositionKey(worldUuid, 1, 64, 1);

        service.trackPrimedBlock(primedBlock, actorUuid);
        service.attachSpawnedTnt(primedBlock, tntEntityUuid, null);

        assertEquals(Optional.of(actorUuid), service.resolveExplodingActor(tntEntityUuid));
        service.recordExplosion(primedBlock, actorUuid);
        service.forgetEntity(tntEntityUuid);
        assertFalse(service.resolveExplodingActor(tntEntityUuid).isPresent());
        assertEquals(Optional.of(actorUuid), service.findRecentExplosionActor(primedBlock));
    }

    @Test
    void fallsBackToRecentExplosionOwnershipForChainPriming() {
        AtomicLong now = new AtomicLong(1000L);
        TntAttributionService service = new TntAttributionService(now::get, 10_000L);
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        BlockPositionKey explosionOrigin = new BlockPositionKey(worldUuid, 5, 70, 5);

        service.recordExplosion(explosionOrigin, actorUuid);

        assertEquals(Optional.of(actorUuid), service.findRecentExplosionActor(explosionOrigin));
    }

    @Test
    void tracksRecentPrimingBlockActorsFromTrackedMutations() {
        AtomicLong now = new AtomicLong(1000L);
        TntAttributionService service = new TntAttributionService(now::get, 10_000L);
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        BlockPositionKey primingBlock = new BlockPositionKey(worldUuid, 2, 65, 2);
        BlockHistoryEntry entry = new BlockHistoryEntry(
                actorUuid,
                now.get(),
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:redstone_block",
                null,
                null,
                null
        );

        service.onTrackedMutation(primingBlock, entry);

        assertEquals(Optional.of(actorUuid), service.findRecentPrimingBlockActor(primingBlock));
    }

    @Test
    void expiresOldPendingAndEntityTracking() {
        AtomicLong now = new AtomicLong(1000L);
        TntAttributionService service = new TntAttributionService(now::get, 100L);
        UUID actorUuid = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        BlockPositionKey primedBlock = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);
        BlockHistoryEntry entry = new BlockHistoryEntry(
                actorUuid,
                now.get(),
                BlockHistoryAction.PLACE_OR_REPLACE,
                "minecraft:redstone_block",
                null,
                null,
                null
        );

        service.trackPrimedBlock(primedBlock, actorUuid);
        service.onTrackedMutation(primedBlock, entry);
        now.set(1201L);
        service.attachSpawnedTnt(primedBlock, entityUuid, null);

        assertFalse(service.resolveExplodingActor(entityUuid).isPresent());
        assertEquals(0, service.pendingPrimeCount());
        assertEquals(0, service.activeEntityCount());
        assertFalse(service.findRecentPrimingBlockActor(primedBlock).isPresent());
    }
}
