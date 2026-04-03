package com.libelulamapinspector.undo;

import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.index.BlockPositionKey;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Orchestrates the full undo workflow.
 */
public final class UndoService {

    private static final int DEFAULT_UNDO_BLOCKS_PER_TICK = 10;

    private final JavaPlugin plugin;
    private final StorageService storageService;
    private final UndoPlanner undoPlanner;
    private final UndoBlockApplier undoBlockApplier;
    private final AtomicBoolean undoInProgress = new AtomicBoolean(false);

    public UndoService(JavaPlugin plugin, StorageService storageService) {
        this(plugin, storageService, new UndoPlanner(), new UndoBlockApplier(plugin.getLogger()));
    }

    UndoService(JavaPlugin plugin, StorageService storageService, UndoPlanner undoPlanner, UndoBlockApplier undoBlockApplier) {
        this.plugin = plugin;
        this.storageService = storageService;
        this.undoPlanner = undoPlanner;
        this.undoBlockApplier = undoBlockApplier;
    }

    public boolean isUndoInProgress() {
        return undoInProgress.get();
    }

    public int getConfiguredUndoBlocksPerTick() {
        int configured = plugin.getConfig().getInt("commands.undo-blocks-per-tick", DEFAULT_UNDO_BLOCKS_PER_TICK);
        return configured > 0 ? configured : DEFAULT_UNDO_BLOCKS_PER_TICK;
    }

    public void startUndo(CommandSender sender, UndoRequest undoRequest) {
        if (!undoInProgress.compareAndSet(false, true)) {
            sender.sendMessage(ChatColor.RED + "Another undo operation is already running.");
            return;
        }

        long cutoffTimestampEpochMillisUtc = System.currentTimeMillis();
        ActiveUndoContext undoContext = new ActiveUndoContext(undoRequest.scope(), cutoffTimestampEpochMillisUtc);
        storageService.addBlockMutationObserver(undoContext);

        sender.sendMessage(ChatColor.YELLOW + "Preparing the undo for " + undoRequest.targetPlayerName() + "...");
        storageService.submitReadTask(() -> prepareUndoAsync(sender, undoRequest, undoContext));
    }

    private void prepareUndoAsync(CommandSender sender, UndoRequest undoRequest, ActiveUndoContext undoContext) {
        try {
            Map<BlockPositionKey, List<BlockHistoryEntry>> histories = storageService.getBlockHistoriesInScope(undoRequest.scope());
            UndoPlan undoPlan = undoPlanner.createPlan(undoRequest.targetPlayerUuid(), undoContext.cutoffTimestampEpochMillisUtc(), histories);

            if (undoPlan.isEmpty()) {
                finishWithoutChanges(sender, undoContext, undoRequest);
                return;
            }

            World world = resolveWorld(undoRequest.scope().worldUuid());
            if (world == null) {
                finishWithFailure(sender, undoContext, "The selected world could not be resolved for undo.");
                return;
            }

            List<UndoWorldChange> structureChanges = undoPlan.worldChanges();
            List<UndoWorldChange> snapshotChanges = structureChanges.stream()
                    .filter(change -> change.desiredState().snapshot() != null)
                    .toList();

            scheduleStructureBatch(sender, undoRequest, undoContext, undoPlan, world, structureChanges, snapshotChanges, 0);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to prepare the undo operation.", exception);
            finishWithFailure(sender, undoContext, "The undo could not be prepared. Check the console for details.");
        }
    }

    private void scheduleStructureBatch(
            CommandSender sender,
            UndoRequest undoRequest,
            ActiveUndoContext undoContext,
            UndoPlan undoPlan,
            World world,
            List<UndoWorldChange> structureChanges,
            List<UndoWorldChange> snapshotChanges,
            int startIndex
    ) {
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTask(plugin, () -> {
            int processed = 0;
            int index = startIndex;
            while (index < structureChanges.size() && processed < undoRequest.maxBlocksPerTick()) {
                UndoWorldChange worldChange = structureChanges.get(index++);
                if (undoContext.isInvalidated(worldChange.positionKey())) {
                    continue;
                }

                undoBlockApplier.applyBlockState(world, worldChange);
                processed++;
            }

            if (index < structureChanges.size()) {
                int nextIndex = index;
                scheduler.runTaskLater(plugin, () ->
                        scheduleStructureBatch(sender, undoRequest, undoContext, undoPlan, world, structureChanges, snapshotChanges, nextIndex), 1L);
                return;
            }

            scheduleSnapshotBatch(sender, undoRequest, undoContext, undoPlan, world, snapshotChanges, 0);
        });
    }

    private void scheduleSnapshotBatch(
            CommandSender sender,
            UndoRequest undoRequest,
            ActiveUndoContext undoContext,
            UndoPlan undoPlan,
            World world,
            List<UndoWorldChange> snapshotChanges,
            int startIndex
    ) {
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTask(plugin, () -> {
            int processed = 0;
            int index = startIndex;
            while (index < snapshotChanges.size() && processed < undoRequest.maxBlocksPerTick()) {
                UndoWorldChange worldChange = snapshotChanges.get(index++);
                if (undoContext.isInvalidated(worldChange.positionKey())) {
                    continue;
                }

                undoBlockApplier.applyDeferredSnapshot(world, worldChange);
                processed++;
            }

            if (index < snapshotChanges.size()) {
                int nextIndex = index;
                scheduler.runTaskLater(plugin, () ->
                        scheduleSnapshotBatch(sender, undoRequest, undoContext, undoPlan, world, snapshotChanges, nextIndex), 1L);
                return;
            }

            storageService.submitWriteTask(() -> commitUndoAsync(sender, undoRequest, undoContext, undoPlan));
        });
    }

    private void commitUndoAsync(CommandSender sender, UndoRequest undoRequest, ActiveUndoContext undoContext, UndoPlan undoPlan) {
        try {
            storageService.rewriteActorHistoryInScope(undoRequest.targetPlayerUuid(), undoRequest.scope(), undoContext.cutoffTimestampEpochMillisUtc());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.GREEN + "Undo completed for " + undoRequest.targetPlayerName() + ".");
                sender.sendMessage(ChatColor.YELLOW + "Affected blocks: " + ChatColor.WHITE + undoPlan.affectedBlockCount());
                sender.sendMessage(ChatColor.YELLOW + "Removed history entries: " + ChatColor.WHITE + undoPlan.removedHistoryEntryCount());
                cleanupUndoContext(undoContext);
            });
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to rewrite history during undo.", exception);
            finishWithFailure(sender, undoContext, "The undo world changes were applied, but the history rewrite failed. Check the console for details.");
        }
    }

    private void finishWithoutChanges(CommandSender sender, ActiveUndoContext undoContext, UndoRequest undoRequest) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            sender.sendMessage(ChatColor.YELLOW + "No matching changes from " + undoRequest.targetPlayerName() + " were found in " + undoRequest.scope().confirmationDescription() + ".");
            cleanupUndoContext(undoContext);
        });
    }

    private void finishWithFailure(CommandSender sender, ActiveUndoContext undoContext, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            sender.sendMessage(ChatColor.RED + message);
            cleanupUndoContext(undoContext);
        });
    }

    private void cleanupUndoContext(ActiveUndoContext undoContext) {
        storageService.removeBlockMutationObserver(undoContext);
        undoInProgress.set(false);
    }

    private World resolveWorld(UUID worldUuid) {
        return plugin.getServer().getWorld(worldUuid);
    }

    private static final class ActiveUndoContext implements StorageService.BlockMutationObserver {

        private final UndoScope undoScope;
        private final long cutoffTimestampEpochMillisUtc;
        private final Set<BlockPositionKey> invalidatedPositions = ConcurrentHashMap.newKeySet();

        private ActiveUndoContext(UndoScope undoScope, long cutoffTimestampEpochMillisUtc) {
            this.undoScope = undoScope;
            this.cutoffTimestampEpochMillisUtc = cutoffTimestampEpochMillisUtc;
        }

        private long cutoffTimestampEpochMillisUtc() {
            return cutoffTimestampEpochMillisUtc;
        }

        private boolean isInvalidated(BlockPositionKey positionKey) {
            return invalidatedPositions.contains(positionKey);
        }

        @Override
        public void onTrackedMutation(BlockPositionKey positionKey, BlockHistoryEntry entry) {
            if (entry.timestampEpochMillisUtc() > cutoffTimestampEpochMillisUtc && undoScope.contains(positionKey)) {
                invalidatedPositions.add(positionKey);
            }
        }
    }
}
