package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.FluidAttributionService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.libelulamapinspector.capture.CaptureTestSupport.block;
import static com.libelulamapinspector.capture.CaptureTestSupport.blockState;
import static com.libelulamapinspector.capture.CaptureTestSupport.player;
import static com.libelulamapinspector.capture.CaptureTestSupport.world;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlockCaptureListenerTest {

    @Test
    void ignoresMultiPlaceEventsInTheSinglePlaceHandler() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        BlockCaptureListener listener = new BlockCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, null);
        BlockMultiPlaceEvent event = mock(BlockMultiPlaceEvent.class);

        listener.onBlockPlace(event);

        verify(captureService, never()).recordBlockPlace(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delegatesSinglePlaceCaptureWhenEnabled() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        BlockCaptureListener listener = new BlockCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, null);
        Player player = player(UUID.randomUUID());
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Block block = block(world("world", UUID.randomUUID()), 1, 64, 1, Material.STONE);
        BlockState replacedState = blockState(block, Material.AIR);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(block);
        when(event.getBlockReplacedState()).thenReturn(replacedState);

        listener.onBlockPlace(event);

        verify(captureService).recordBlockPlace(player, block, replacedState);
    }

    @Test
    void ignoresSinglePlaceCaptureInExcludedWorlds() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("capture.excluded-worlds", java.util.List.of("world_creative"));
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        BlockCaptureListener listener = new BlockCaptureListener(configuration, captureService, null);
        Player player = player(UUID.randomUUID());
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Block block = block(world("world_creative", UUID.randomUUID()), 1, 64, 1, Material.STONE);
        BlockState replacedState = blockState(block, Material.AIR);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlockPlaced()).thenReturn(block);
        when(event.getBlockReplacedState()).thenReturn(replacedState);

        listener.onBlockPlace(event);

        verify(captureService, never()).recordBlockPlace(player, block, replacedState);
    }

    @Test
    void seedsFluidTrackingOnBucketEmptyWhenEnabled() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("capture.fluids.fluid-grief-tracking", true);
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        FluidAttributionService fluidService = mock(FluidAttributionService.class);
        BlockCaptureListener listener = new BlockCaptureListener(configuration, captureService, fluidService);

        PlayerBucketEmptyEvent event = mock(PlayerBucketEmptyEvent.class);
        UUID playerUuid = UUID.randomUUID();
        Player player = player(playerUuid);
        Block block = block(world("world", UUID.randomUUID()), 2, 65, 2, Material.AIR);
        BlockState replacedState = blockState(block, Material.AIR);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getBucket()).thenReturn(Material.WATER_BUCKET);

        listener.onBucketEmpty(event);

        verify(captureService).recordBucketEmpty(player, block, Material.WATER_BUCKET, replacedState);
        verify(fluidService).trackPlacedFluid(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Material.WATER), org.mockito.ArgumentMatchers.eq(playerUuid));
    }

    @Test
    void ignoresBucketCaptureInExcludedWorlds() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("capture.fluids.fluid-grief-tracking", true);
        yaml.set("capture.excluded-worlds", java.util.List.of("world_creative"));
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        FluidAttributionService fluidService = mock(FluidAttributionService.class);
        BlockCaptureListener listener = new BlockCaptureListener(configuration, captureService, fluidService);

        PlayerBucketEmptyEvent event = mock(PlayerBucketEmptyEvent.class);
        UUID playerUuid = UUID.randomUUID();
        Player player = player(playerUuid);
        Block block = block(world("world_creative", UUID.randomUUID()), 2, 65, 2, Material.AIR);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(event.getBucket()).thenReturn(Material.WATER_BUCKET);

        listener.onBucketEmpty(event);

        verify(captureService, never()).recordBucketEmpty(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fluidService, never()).trackPlacedFluid(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
