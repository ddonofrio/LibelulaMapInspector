package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.FluidAttributionService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static com.libelulamapinspector.capture.CaptureTestSupport.block;
import static com.libelulamapinspector.capture.CaptureTestSupport.blockState;
import static com.libelulamapinspector.capture.CaptureTestSupport.world;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FluidGriefCaptureListenerTest {

    @Test
    void propagatesTrackedFluidOwnershipOnFlow() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        FluidAttributionService fluidService = mock(FluidAttributionService.class);
        FluidGriefCaptureListener listener = new FluidGriefCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, fluidService);
        BlockFromToEvent event = mock(BlockFromToEvent.class);
        Block source = block(world("world", UUID.randomUUID()), 1, 64, 1, Material.WATER);
        Block destination = block(source.getWorld(), 1, 64, 2, Material.AIR);
        when(event.getBlock()).thenReturn(source);
        when(event.getToBlock()).thenReturn(destination);

        listener.onBlockFromTo(event);

        verify(fluidService).propagate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsFormedBlocksWhenATrackedFluidActorExists() {
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        FluidAttributionService fluidService = mock(FluidAttributionService.class);
        FluidGriefCaptureListener listener = new FluidGriefCaptureListener(CaptureConfiguration.from(new org.bukkit.configuration.file.YamlConfiguration()), captureService, fluidService);
        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = block(world("world", UUID.randomUUID()), 5, 70, 5, Material.COBBLESTONE);
        BlockState newState = blockState(block, Material.COBBLESTONE);
        UUID actorUuid = UUID.randomUUID();
        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(fluidService.resolveResponsibleActor(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(actorUuid));

        listener.onBlockForm(event);

        verify(captureService).recordFormedBlock(actorUuid, newState);
    }

    @Test
    void ignoresFluidGriefTrackingInExcludedWorlds() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("capture.excluded-worlds", java.util.List.of("world_creative"));
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        BlockHistoryCaptureService captureService = mock(BlockHistoryCaptureService.class);
        FluidAttributionService fluidService = mock(FluidAttributionService.class);
        FluidGriefCaptureListener listener = new FluidGriefCaptureListener(configuration, captureService, fluidService);
        BlockFromToEvent fromToEvent = mock(BlockFromToEvent.class);
        Block source = block(world("world_creative", UUID.randomUUID()), 1, 64, 1, Material.WATER);
        Block destination = block(source.getWorld(), 1, 64, 2, Material.AIR);
        when(fromToEvent.getBlock()).thenReturn(source);
        when(fromToEvent.getToBlock()).thenReturn(destination);

        listener.onBlockFromTo(fromToEvent);

        verify(fluidService, org.mockito.Mockito.never()).propagate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
