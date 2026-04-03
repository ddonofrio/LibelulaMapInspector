package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.TntAttributionService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.libelulamapinspector.capture.CaptureTestSupport.block;
import static com.libelulamapinspector.capture.CaptureTestSupport.player;
import static com.libelulamapinspector.capture.CaptureTestSupport.world;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TntCaptureListenerTest {

    @Test
    void tracksPlayerPrimedTntAndAttachesTheSpawnedEntity() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        TntAttributionService tntService = mock(TntAttributionService.class);
        TntCaptureListener listener = new TntCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, tntService);
        TNTPrimeEvent primeEvent = mock(TNTPrimeEvent.class);
        UUID playerUuid = UUID.randomUUID();
        Player player = player(playerUuid);
        Block tntBlock = block(world("world", UUID.randomUUID()), 1, 64, 1, org.bukkit.Material.TNT);
        when(primeEvent.getPrimingEntity()).thenReturn(player);
        when(primeEvent.getBlock()).thenReturn(tntBlock);

        listener.onTntPrime(primeEvent);

        verify(tntService).trackPrimedBlock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(playerUuid));

        EntitySpawnEvent spawnEvent = mock(EntitySpawnEvent.class);
        TNTPrimed tntPrimed = mock(TNTPrimed.class);
        UUID tntEntityUuid = UUID.randomUUID();
        World world = tntBlock.getWorld();
        when(tntPrimed.getUniqueId()).thenReturn(tntEntityUuid);
        when(tntPrimed.getLocation()).thenReturn(new Location(world, 1.5, 64, 1.5));
        when(spawnEvent.getEntity()).thenReturn(tntPrimed);

        listener.onEntitySpawn(spawnEvent);

        verify(tntService).attachSpawnedTnt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(tntEntityUuid), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void recordsExplosionRemovalsWhenAnActorWasResolved() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        TntAttributionService tntService = mock(TntAttributionService.class);
        TntCaptureListener listener = new TntCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, tntService);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        World world = world("world", UUID.randomUUID());
        TNTPrimed tntPrimed = mock(TNTPrimed.class);
        UUID tntEntityUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();
        Block destroyedBlock = block(world, 5, 70, 5, org.bukkit.Material.STONE);
        when(tntPrimed.getUniqueId()).thenReturn(tntEntityUuid);
        when(tntPrimed.getLocation()).thenReturn(new Location(world, 5.5, 70, 5.5));
        when(event.getEntity()).thenReturn(tntPrimed);
        when(event.blockList()).thenReturn(List.of(destroyedBlock));
        when(tntService.resolveExplodingActor(tntEntityUuid)).thenReturn(Optional.of(actorUuid));

        listener.onEntityExplode(event);

        verify(captureService).recordExplosionRemovals(actorUuid, List.of(destroyedBlock));
        verify(tntService).recordExplosion(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(actorUuid));
        verify(tntService).forgetEntity(tntEntityUuid);
    }

    @Test
    void fallsBackToRecentPrimingBlockActorWhenNoPrimingEntityExists() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        TntAttributionService tntService = mock(TntAttributionService.class);
        TntCaptureListener listener = new TntCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, tntService);
        TNTPrimeEvent primeEvent = mock(TNTPrimeEvent.class);
        UUID actorUuid = UUID.randomUUID();
        World world = world("world", UUID.randomUUID());
        Block tntBlock = block(world, 1, 64, 1, org.bukkit.Material.TNT);
        Block primingBlock = block(world, 1, 65, 1, org.bukkit.Material.REDSTONE_BLOCK);
        when(primeEvent.getBlock()).thenReturn(tntBlock);
        when(primeEvent.getPrimingBlock()).thenReturn(primingBlock);
        when(tntService.findRecentExplosionActor(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(tntService.findRecentPrimingBlockActor(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(actorUuid));

        listener.onTntPrime(primeEvent);

        verify(tntService).trackPrimedBlock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(actorUuid));
    }

    @Test
    void ignoresTntCaptureInExcludedWorlds() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("capture.excluded-worlds", java.util.List.of("world_creative"));
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        TntAttributionService tntService = mock(TntAttributionService.class);
        TntCaptureListener listener = new TntCaptureListener(configuration, captureService, tntService);
        TNTPrimeEvent primeEvent = mock(TNTPrimeEvent.class);
        UUID playerUuid = UUID.randomUUID();
        Player player = player(playerUuid);
        Block tntBlock = block(world("world_creative", UUID.randomUUID()), 1, 64, 1, org.bukkit.Material.TNT);
        when(primeEvent.getPrimingEntity()).thenReturn(player);
        when(primeEvent.getBlock()).thenReturn(tntBlock);

        listener.onTntPrime(primeEvent);

        verify(tntService, org.mockito.Mockito.never()).trackPrimedBlock(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
