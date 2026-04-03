package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks player-attributed fluid propagation for optional grief capture.
 */
public final class FluidAttributionService {

    private static final long DEFAULT_TTL_MILLIS = 5L * 60L * 1000L;
    private static final int[][] NEIGHBOR_OFFSETS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

    private final Map<BlockPositionKey, TrackedFluidAttribution> trackedFluids = new HashMap<>();
    private final LongSupplier clock;
    private final long ttlMillis;

    public FluidAttributionService() {
        this(System::currentTimeMillis, DEFAULT_TTL_MILLIS);
    }

    FluidAttributionService(LongSupplier clock, long ttlMillis) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public void trackPlacedFluid(BlockPositionKey positionKey, Material fluidMaterial, UUID actorUuid) {
        if (positionKey == null || actorUuid == null || !CaptureMaterials.isTrackableFluid(fluidMaterial)) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        trackedFluids.put(positionKey, new TrackedFluidAttribution(fluidMaterial, actorUuid, now, now));
    }

    public void propagate(BlockPositionKey sourceKey, BlockPositionKey destinationKey) {
        if (sourceKey == null || destinationKey == null) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        TrackedFluidAttribution sourceAttribution = trackedFluids.get(sourceKey);
        if (sourceAttribution == null) {
            return;
        }

        trackedFluids.put(
                destinationKey,
                new TrackedFluidAttribution(
                        sourceAttribution.fluidMaterial(),
                        sourceAttribution.actorUuid(),
                        sourceAttribution.originTimestampEpochMillisUtc(),
                        now
                )
        );
    }

    public Optional<UUID> resolveResponsibleActor(BlockPositionKey formedBlockKey) {
        if (formedBlockKey == null) {
            return Optional.empty();
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        TrackedFluidAttribution selected = null;
        for (int[] offset : NEIGHBOR_OFFSETS) {
            BlockPositionKey neighborKey = new BlockPositionKey(
                    formedBlockKey.worldUuid(),
                    formedBlockKey.x() + offset[0],
                    formedBlockKey.y() + offset[1],
                    formedBlockKey.z() + offset[2]
            );
            TrackedFluidAttribution attribution = trackedFluids.get(neighborKey);
            if (attribution == null) {
                continue;
            }

            if (selected == null || attribution.isPreferredOver(selected)) {
                selected = attribution;
            }
        }

        return selected != null ? Optional.of(selected.actorUuid()) : Optional.empty();
    }

    public void removeTrackedFluid(BlockPositionKey positionKey) {
        if (positionKey == null) {
            return;
        }

        cleanupExpired(clock.getAsLong());
        trackedFluids.remove(positionKey);
    }

    int trackedFluidCount() {
        cleanupExpired(clock.getAsLong());
        return trackedFluids.size();
    }

    boolean isTracked(BlockPositionKey positionKey) {
        cleanupExpired(clock.getAsLong());
        return trackedFluids.containsKey(positionKey);
    }

    private void cleanupExpired(long now) {
        trackedFluids.entrySet().removeIf(entry -> now - entry.getValue().lastTouchedEpochMillisUtc() > ttlMillis);
    }

    private record TrackedFluidAttribution(
            Material fluidMaterial,
            UUID actorUuid,
            long originTimestampEpochMillisUtc,
            long lastTouchedEpochMillisUtc
    ) {
        private boolean isPreferredOver(TrackedFluidAttribution other) {
            int timestampComparison = Long.compare(originTimestampEpochMillisUtc, other.originTimestampEpochMillisUtc);
            if (timestampComparison != 0) {
                return timestampComparison > 0;
            }

            return CaptureMaterials.fluidPriority(fluidMaterial) > CaptureMaterials.fluidPriority(other.fluidMaterial);
        }
    }
}
