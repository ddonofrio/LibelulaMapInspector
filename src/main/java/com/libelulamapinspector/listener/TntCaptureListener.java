package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.BlockPositionKeys;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.TntAttributionService;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Optional;
import java.util.UUID;

/**
 * Captures TNT grief and attributes destroyed blocks to the igniting player.
 */
public final class TntCaptureListener implements Listener {

    private final CaptureConfiguration captureConfiguration;
    private final BlockHistoryCaptureService blockHistoryCaptureService;
    private final TntAttributionService tntAttributionService;

    public TntCaptureListener(
            CaptureConfiguration captureConfiguration,
            BlockHistoryCaptureService blockHistoryCaptureService,
            TntAttributionService tntAttributionService
    ) {
        this.captureConfiguration = captureConfiguration;
        this.blockHistoryCaptureService = blockHistoryCaptureService;
        this.tntAttributionService = tntAttributionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld())) {
            return;
        }

        UUID actorUuid = resolvePlayerActor(event.getPrimingEntity());
        if (actorUuid == null && event.getPrimingBlock() != null) {
            actorUuid = tntAttributionService.findRecentExplosionActor(BlockPositionKeys.from(event.getPrimingBlock())).orElse(null);
            if (actorUuid == null) {
                actorUuid = tntAttributionService.findRecentPrimingBlockActor(BlockPositionKeys.from(event.getPrimingBlock())).orElse(null);
            }
        }

        if (actorUuid != null) {
            tntAttributionService.trackPrimedBlock(BlockPositionKeys.from(event.getBlock()), actorUuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tntPrimed)) {
            return;
        }
        if (captureConfiguration.isWorldExcluded(tntPrimed.getWorld())) {
            return;
        }

        UUID fallbackActorUuid = resolvePlayerActor(tntPrimed.getSource());
        tntAttributionService.attachSpawnedTnt(BlockPositionKeys.from(tntPrimed.getLocation()), tntPrimed.getUniqueId(), fallbackActorUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tntPrimed)) {
            return;
        }
        if (captureConfiguration.isWorldExcluded(tntPrimed.getWorld())) {
            tntAttributionService.forgetEntity(tntPrimed.getUniqueId());
            return;
        }

        Optional<UUID> actorUuid = tntAttributionService.resolveExplodingActor(tntPrimed.getUniqueId());
        if (actorUuid.isEmpty()) {
            UUID fallbackActorUuid = resolvePlayerActor(tntPrimed.getSource());
            actorUuid = Optional.ofNullable(fallbackActorUuid);
        }

        if (actorUuid.isEmpty()) {
            tntAttributionService.forgetEntity(tntPrimed.getUniqueId());
            return;
        }

        blockHistoryCaptureService.recordExplosionRemovals(actorUuid.get(), event.blockList());
        tntAttributionService.recordExplosion(BlockPositionKeys.from(tntPrimed.getLocation()), actorUuid.get());
        tntAttributionService.forgetEntity(tntPrimed.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof TNTPrimed tntPrimed) {
            tntAttributionService.forgetEntity(tntPrimed.getUniqueId());
        }
    }

    private UUID resolvePlayerActor(Entity entity) {
        if (entity instanceof Player player) {
            return player.getUniqueId();
        }

        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player.getUniqueId();
            }
        }

        return null;
    }
}
