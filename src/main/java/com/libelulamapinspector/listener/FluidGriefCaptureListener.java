package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.BlockPositionKeys;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.CaptureMaterials;
import com.libelulamapinspector.capture.FluidAttributionService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Tracks optional water/lava grief propagation.
 */
public final class FluidGriefCaptureListener implements Listener {

    private final CaptureConfiguration captureConfiguration;
    private final BlockHistoryCaptureService blockHistoryCaptureService;
    private final FluidAttributionService fluidAttributionService;

    public FluidGriefCaptureListener(
            CaptureConfiguration captureConfiguration,
            BlockHistoryCaptureService blockHistoryCaptureService,
            FluidAttributionService fluidAttributionService
    ) {
        this.captureConfiguration = captureConfiguration;
        this.blockHistoryCaptureService = blockHistoryCaptureService;
        this.fluidAttributionService = fluidAttributionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block sourceBlock = event.getBlock();
        if (captureConfiguration.isWorldExcluded(sourceBlock.getWorld())) {
            return;
        }

        Material sourceType = sourceBlock.getType();
        if (!CaptureMaterials.isTrackableFluid(sourceType)) {
            return;
        }

        fluidAttributionService.propagate(BlockPositionKeys.from(sourceBlock), BlockPositionKeys.from(event.getToBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld())) {
            return;
        }

        Optional<UUID> actorUuid = fluidAttributionService.resolveResponsibleActor(BlockPositionKeys.from(event.getBlock()));
        if (actorUuid.isEmpty()) {
            return;
        }

        blockHistoryCaptureService.recordFormedBlock(actorUuid.get(), event.getNewState());
    }
}
