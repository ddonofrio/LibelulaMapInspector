package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.StorageService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks TNT ownership from priming to explosion, including simple chain reactions.
 */
public final class TntAttributionService implements StorageService.BlockMutationObserver {

    private static final long DEFAULT_TTL_MILLIS = 30_000L;

    private final Map<BlockPositionKey, TimedActor> pendingPrimedBlocks = new HashMap<>();
    private final Map<UUID, TimedActor> activeTntEntities = new HashMap<>();
    private final Map<BlockPositionKey, TimedActor> recentExplosionOrigins = new HashMap<>();
    private final Map<BlockPositionKey, TimedActor> recentBlockActors = new HashMap<>();
    private final LongSupplier clock;
    private final long ttlMillis;

    public TntAttributionService() {
        this(System::currentTimeMillis, DEFAULT_TTL_MILLIS);
    }

    TntAttributionService(LongSupplier clock, long ttlMillis) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
    }

    public void trackPrimedBlock(BlockPositionKey primedBlockKey, UUID actorUuid) {
        if (primedBlockKey == null || actorUuid == null) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        pendingPrimedBlocks.put(primedBlockKey, new TimedActor(actorUuid, now));
    }

    public Optional<UUID> findRecentExplosionActor(BlockPositionKey primingBlockKey) {
        if (primingBlockKey == null) {
            return Optional.empty();
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        TimedActor timedActor = recentExplosionOrigins.get(primingBlockKey);
        return timedActor != null ? Optional.of(timedActor.actorUuid()) : Optional.empty();
    }

    public Optional<UUID> findRecentPrimingBlockActor(BlockPositionKey primingBlockKey) {
        if (primingBlockKey == null) {
            return Optional.empty();
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        TimedActor timedActor = recentBlockActors.get(primingBlockKey);
        return timedActor != null ? Optional.of(timedActor.actorUuid()) : Optional.empty();
    }

    public void attachSpawnedTnt(BlockPositionKey primedBlockKey, UUID tntEntityUuid, UUID fallbackActorUuid) {
        if (primedBlockKey == null || tntEntityUuid == null) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        TimedActor pendingActor = pendingPrimedBlocks.remove(primedBlockKey);
        if (pendingActor != null) {
            activeTntEntities.put(tntEntityUuid, new TimedActor(pendingActor.actorUuid(), now));
            return;
        }

        if (fallbackActorUuid != null) {
            activeTntEntities.put(tntEntityUuid, new TimedActor(fallbackActorUuid, now));
        }
    }

    public Optional<UUID> resolveExplodingActor(UUID tntEntityUuid) {
        if (tntEntityUuid == null) {
            return Optional.empty();
        }

        cleanupExpired(clock.getAsLong());
        TimedActor timedActor = activeTntEntities.get(tntEntityUuid);
        return timedActor != null ? Optional.of(timedActor.actorUuid()) : Optional.empty();
    }

    public void recordExplosion(BlockPositionKey explosionOriginKey, UUID actorUuid) {
        if (explosionOriginKey == null || actorUuid == null) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        recentExplosionOrigins.put(explosionOriginKey, new TimedActor(actorUuid, now));
    }

    public void forgetEntity(UUID tntEntityUuid) {
        if (tntEntityUuid == null) {
            return;
        }

        cleanupExpired(clock.getAsLong());
        activeTntEntities.remove(tntEntityUuid);
    }

    @Override
    public void onTrackedMutation(BlockPositionKey positionKey, BlockHistoryEntry entry) {
        if (positionKey == null || entry == null) {
            return;
        }

        long now = clock.getAsLong();
        cleanupExpired(now);
        recentBlockActors.put(positionKey, new TimedActor(entry.actorUuid(), now));
    }

    int pendingPrimeCount() {
        cleanupExpired(clock.getAsLong());
        return pendingPrimedBlocks.size();
    }

    int activeEntityCount() {
        cleanupExpired(clock.getAsLong());
        return activeTntEntities.size();
    }

    private void cleanupExpired(long now) {
        pendingPrimedBlocks.entrySet().removeIf(entry -> now - entry.getValue().timestampEpochMillisUtc() > ttlMillis);
        activeTntEntities.entrySet().removeIf(entry -> now - entry.getValue().timestampEpochMillisUtc() > ttlMillis);
        recentExplosionOrigins.entrySet().removeIf(entry -> now - entry.getValue().timestampEpochMillisUtc() > ttlMillis);
        recentBlockActors.entrySet().removeIf(entry -> now - entry.getValue().timestampEpochMillisUtc() > ttlMillis);
    }

    private record TimedActor(UUID actorUuid, long timestampEpochMillisUtc) {
    }
}
