package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FluidAttributionServiceTest {

    @Test
    void propagatesTrackedFluidsAndResolvesTheResponsibleActor() {
        AtomicLong now = new AtomicLong(1000L);
        FluidAttributionService service = new FluidAttributionService(now::get, 10_000L);
        UUID worldUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        BlockPositionKey source = new BlockPositionKey(worldUuid, 1, 64, 1);
        BlockPositionKey destination = new BlockPositionKey(worldUuid, 1, 64, 2);
        BlockPositionKey formedBlock = new BlockPositionKey(worldUuid, 1, 64, 3);

        service.trackPlacedFluid(source, Material.WATER, actorUuid);
        service.propagate(source, destination);

        assertTrue(service.isTracked(destination));
        assertEquals(Optional.of(actorUuid), service.resolveResponsibleActor(formedBlock));
    }

    @Test
    void prefersTheMostRecentFluidAndUsesWaterAsTieBreaker() {
        AtomicLong now = new AtomicLong(1000L);
        FluidAttributionService service = new FluidAttributionService(now::get, 10_000L);
        UUID worldUuid = UUID.randomUUID();
        UUID lavaActor = UUID.randomUUID();
        UUID waterActor = UUID.randomUUID();
        BlockPositionKey formedBlock = new BlockPositionKey(worldUuid, 10, 70, 10);

        service.trackPlacedFluid(new BlockPositionKey(worldUuid, 9, 70, 10), Material.LAVA, lavaActor);
        now.set(2000L);
        service.trackPlacedFluid(new BlockPositionKey(worldUuid, 11, 70, 10), Material.WATER, waterActor);

        assertEquals(Optional.of(waterActor), service.resolveResponsibleActor(formedBlock));
    }

    @Test
    void expiresOldFluidTrackingAndSupportsExplicitRemoval() {
        AtomicLong now = new AtomicLong(1000L);
        FluidAttributionService service = new FluidAttributionService(now::get, 100L);
        UUID worldUuid = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(worldUuid, 1, 64, 1);

        service.trackPlacedFluid(positionKey, Material.WATER, UUID.randomUUID());
        assertTrue(service.isTracked(positionKey));

        now.set(1201L);
        assertFalse(service.isTracked(positionKey));

        service.trackPlacedFluid(positionKey, Material.LAVA, UUID.randomUUID());
        service.removeTrackedFluid(positionKey);
        assertFalse(service.isTracked(positionKey));
    }
}
