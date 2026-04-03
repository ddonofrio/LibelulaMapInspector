package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import com.libelulamapinspector.storage.StorageService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Converts captured Bukkit events into persisted block history entries.
 */
public final class BlockHistoryCaptureService {

    private final StorageService storageService;
    private final BlockSnapshotFactory snapshotFactory;
    private final LongSupplier clock;

    public BlockHistoryCaptureService(StorageService storageService) {
        this(storageService, new BlockSnapshotFactory(), System::currentTimeMillis);
    }

    BlockHistoryCaptureService(StorageService storageService, BlockSnapshotFactory snapshotFactory, LongSupplier clock) {
        this.storageService = storageService;
        this.snapshotFactory = snapshotFactory;
        this.clock = clock;
    }

    public void recordBlockBreak(Player player, BlockState removedState) {
        if (player == null || removedState == null || removedState.getType() == Material.AIR) {
            return;
        }

        persist(removedState.getBlock(), snapshotFactory.createBreakEntry(player.getUniqueId(), clock.getAsLong(), removedState));
    }

    public void recordBlockPlace(Player player, Block placedBlock, BlockState replacedState) {
        if (player == null || placedBlock == null || placedBlock.getType() == Material.AIR) {
            return;
        }

        persist(placedBlock, snapshotFactory.createPlaceEntry(player.getUniqueId(), clock.getAsLong(), placedBlock, replacedState));
    }

    public void recordMultiPlace(Player player, List<BlockState> replacedStates) {
        if (player == null || replacedStates == null) {
            return;
        }

        for (BlockState replacedState : replacedStates) {
            if (replacedState == null) {
                continue;
            }

            recordBlockPlace(player, replacedState.getBlock(), replacedState);
        }
    }

    public void recordSignChange(Player player, Block block, Side changedSide, String[] changedLines) {
        if (player == null || block == null) {
            return;
        }

        BlockState blockState = block.getState();
        if (!(blockState instanceof Sign sign)) {
            return;
        }

        SignBlockSnapshot snapshot = snapshotFactory.captureSignSnapshot(sign, changedSide, changedLines);
        persist(block, snapshotFactory.createStateUpdateEntry(player.getUniqueId(), clock.getAsLong(), block, snapshot));
    }

    public void recordBucketEmpty(Player player, Block targetBlock, Material bucketMaterial, BlockState replacedState) {
        if (player == null || targetBlock == null) {
            return;
        }

        Material fluidMaterial = CaptureMaterials.fluidMaterialFromBucket(bucketMaterial);
        if (fluidMaterial == null) {
            return;
        }

        persist(targetBlock, snapshotFactory.createBucketEmptyEntry(player.getUniqueId(), clock.getAsLong(), targetBlock, fluidMaterial, replacedState));
    }

    public void recordBucketFill(Player player, BlockState removedFluidState) {
        if (player == null || removedFluidState == null || !CaptureMaterials.isTrackableFluid(removedFluidState.getType())) {
            return;
        }

        persist(removedFluidState.getBlock(), snapshotFactory.createBucketFillEntry(player.getUniqueId(), clock.getAsLong(), removedFluidState));
    }

    public void recordExplosionRemovals(UUID actorUuid, List<Block> destroyedBlocks) {
        if (actorUuid == null || destroyedBlocks == null) {
            return;
        }

        long timestamp = clock.getAsLong();
        for (Block block : destroyedBlocks) {
            if (block == null || block.getType() == Material.AIR) {
                continue;
            }

            BlockState removedState = block.getState();
            persist(block, snapshotFactory.createBreakEntry(actorUuid, timestamp, removedState));
        }
    }

    public void recordFormedBlock(UUID actorUuid, BlockState newState) {
        if (actorUuid == null || newState == null || newState.getType() == Material.AIR) {
            return;
        }

        persist(newState.getBlock(), snapshotFactory.createFormedBlockEntry(actorUuid, clock.getAsLong(), newState));
    }

    private void persist(Block block, BlockHistoryEntry entry) {
        BlockPositionKey positionKey = BlockPositionKeys.from(block);
        storageService.trackBlockMutation(block.getWorld().getName(), positionKey, entry);
    }
}
