package com.libelulamapinspector.listener;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.BlockPositionKeys;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.CaptureMaterials;
import com.libelulamapinspector.capture.FluidAttributionService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Captures direct player block changes.
 */
public final class BlockCaptureListener implements Listener {

    private final CaptureConfiguration captureConfiguration;
    private final BlockHistoryCaptureService blockHistoryCaptureService;
    private final FluidAttributionService fluidAttributionService;

    public BlockCaptureListener(
            CaptureConfiguration captureConfiguration,
            BlockHistoryCaptureService blockHistoryCaptureService,
            FluidAttributionService fluidAttributionService
    ) {
        this.captureConfiguration = captureConfiguration;
        this.blockHistoryCaptureService = blockHistoryCaptureService;
        this.fluidAttributionService = fluidAttributionService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld()) || !captureConfiguration.blocks().blockBreak()) {
            return;
        }

        blockHistoryCaptureService.recordBlockBreak(event.getPlayer(), event.getBlock().getState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent
                || !captureConfiguration.blocks().blockPlace()
                || captureConfiguration.isWorldExcluded(event.getBlockPlaced().getWorld())) {
            return;
        }

        blockHistoryCaptureService.recordBlockPlace(event.getPlayer(), event.getBlockPlaced(), event.getBlockReplacedState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlockPlaced().getWorld()) || !captureConfiguration.blocks().blockMultiPlace()) {
            return;
        }

        blockHistoryCaptureService.recordMultiPlace(event.getPlayer(), event.getReplacedBlockStates());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld()) || !captureConfiguration.blocks().signChange()) {
            return;
        }

        blockHistoryCaptureService.recordSignChange(event.getPlayer(), event.getBlock(), event.getSide(), event.getLines());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld()) || !captureConfiguration.fluids().bucketEmpty()) {
            return;
        }

        Block targetBlock = event.getBlock();
        BlockState replacedState = targetBlock.getState();
        Material fluidMaterial = CaptureMaterials.fluidMaterialFromBucket(event.getBucket());
        blockHistoryCaptureService.recordBucketEmpty(event.getPlayer(), targetBlock, event.getBucket(), replacedState);

        if (captureConfiguration.fluids().fluidGriefTracking() && fluidAttributionService != null && fluidMaterial != null) {
            fluidAttributionService.trackPlacedFluid(BlockPositionKeys.from(targetBlock), fluidMaterial, event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (captureConfiguration.isWorldExcluded(event.getBlock().getWorld()) || !captureConfiguration.fluids().bucketFill()) {
            return;
        }

        BlockState removedFluidState = event.getBlock().getState();
        blockHistoryCaptureService.recordBucketFill(event.getPlayer(), removedFluidState);

        if (captureConfiguration.fluids().fluidGriefTracking() && fluidAttributionService != null && CaptureMaterials.isTrackableFluid(removedFluidState.getType())) {
            fluidAttributionService.removeTrackedFluid(BlockPositionKeys.from(removedFluidState));
        }
    }
}
