package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.index.BloomIndexService;
import com.libelulamapinspector.undo.UndoScope;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Coordinates the index and the chunk-backed block history store.
 */
public final class StorageService {

    private final JavaPlugin plugin;
    private final Object storageLock = new Object();
    private final Object persistenceLock = new Object();
    private final AtomicBoolean resetInProgress = new AtomicBoolean(false);
    private final AtomicBoolean startupMaintenanceRunning = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<BlockMutationObserver> blockMutationObservers = new CopyOnWriteArrayList<>();

    private BloomIndexService bloomIndexService;
    private BlockStoreService blockStoreService;
    private ScheduledExecutorService writeExecutor;
    private ExecutorService readExecutor;
    private ExecutorService maintenanceExecutor;
    private ScheduledFuture<?> periodicPersistenceTask;

    public StorageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() throws IOException {
        synchronized (persistenceLock) {
            initializeServicesLocked();
            ensureExecutorsLocked();
            scheduleStartupMaintenanceIfNeededLocked();
            schedulePeriodicPersistenceLocked();
        }
    }

    public void shutdown() throws IOException {
        synchronized (persistenceLock) {
            cancelPeriodicPersistenceLocked();
            persistNowLocked(false);
        }
        shutdownExecutors();
    }

    public boolean trackBlockMutation(String worldName, BlockPositionKey positionKey, BlockHistoryEntry entry) {
        boolean shouldPersistNow;

        synchronized (storageLock) {
            bloomIndexService.put(positionKey);
            shouldPersistNow = blockStoreService.append(worldName, positionKey, entry);
        }

        notifyMutationObservers(positionKey, entry);

        if (shouldPersistNow) {
            requestAsyncPersistence();
        }

        return shouldPersistNow;
    }

    public List<BlockHistoryEntry> getBufferedTimeline(BlockPositionKey positionKey) {
        return blockStoreService.copyBufferedTimeline(positionKey);
    }

    public Set<UUID> getBufferedActorsInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return blockStoreService.collectBufferedActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean mightContainHistory(BlockPositionKey positionKey) {
        return bloomIndexService.mightContain(positionKey);
    }

    public List<BlockHistoryEntry> getBlockHistory(Location location) throws IOException {
        return getBlockHistory(toBlockPositionKey(location));
    }

    public List<BlockHistoryEntry> getBlockHistory(BlockPositionKey positionKey) throws IOException {
        synchronized (persistenceLock) {
            boolean shouldReadPersistedHistory;
            List<BlockHistoryEntry> combinedEntries;

            synchronized (storageLock) {
                combinedEntries = new ArrayList<>(blockStoreService.copyBufferedTimeline(positionKey));
                shouldReadPersistedHistory = !combinedEntries.isEmpty() || bloomIndexService.mightContain(positionKey);
            }

            if (shouldReadPersistedHistory) {
                combinedEntries.addAll(blockStoreService.readPersistedTimeline(positionKey));
            }

            combinedEntries.sort(Comparator
                    .comparingLong(BlockHistoryEntry::timestampEpochMillisUtc)
                    .thenComparing(entry -> entry.actorUuid().toString())
                    .thenComparing(BlockHistoryEntry::afterBlockDataString));
            return List.copyOf(combinedEntries);
        }
    }

    public Map<BlockPositionKey, List<BlockHistoryEntry>> getBlockHistoriesInScope(UndoScope undoScope) throws IOException {
        synchronized (persistenceLock) {
            Map<BlockPositionKey, List<BlockHistoryEntry>> combinedEntries = new HashMap<>();

            synchronized (storageLock) {
                mergeHistoriesInto(combinedEntries, blockStoreService.copyBufferedHistoriesInScope(undoScope));
            }

            mergeHistoriesInto(combinedEntries, blockStoreService.readPersistedHistoriesInScope(undoScope));

            Map<BlockPositionKey, List<BlockHistoryEntry>> normalized = new HashMap<>();
            for (Map.Entry<BlockPositionKey, List<BlockHistoryEntry>> entry : combinedEntries.entrySet()) {
                List<BlockHistoryEntry> sortedEntries = new ArrayList<>(entry.getValue());
                sortedEntries.sort(Comparator
                        .comparingLong(BlockHistoryEntry::timestampEpochMillisUtc)
                        .thenComparing(historyEntry -> historyEntry.actorUuid().toString())
                        .thenComparing(BlockHistoryEntry::afterBlockDataString));
                normalized.put(entry.getKey(), List.copyOf(sortedEntries));
            }

            return Map.copyOf(normalized);
        }
    }

    public Set<UUID> getActorsInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
        synchronized (persistenceLock) {
            Set<UUID> actors = new LinkedHashSet<>();
            synchronized (storageLock) {
                actors.addAll(blockStoreService.collectBufferedActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ));
            }
            actors.addAll(blockStoreService.collectPersistedActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ));
            return Set.copyOf(actors);
        }
    }

    public List<String> getActorNamesInBox(World world, BoundingBox boundingBox) throws IOException {
        if (world == null) {
            throw new IllegalArgumentException("world cannot be null");
        }
        if (boundingBox == null) {
            throw new IllegalArgumentException("boundingBox cannot be null");
        }

        return getActorNamesInBox(
                world.getUID(),
                (int) Math.floor(boundingBox.getMinX()),
                (int) Math.floor(boundingBox.getMinY()),
                (int) Math.floor(boundingBox.getMinZ()),
                (int) Math.floor(boundingBox.getMaxX()),
                (int) Math.floor(boundingBox.getMaxY()),
                (int) Math.floor(boundingBox.getMaxZ())
        );
    }

    public List<String> getActorNamesInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
        List<String> names = getActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ).stream()
                .map(this::resolveActorName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return List.copyOf(names);
    }

    public void clearStorageAndReinitialize() throws IOException {
        synchronized (persistenceLock) {
            if (!resetInProgress.compareAndSet(false, true)) {
                throw new IllegalStateException("A storage reset is already in progress.");
            }

            try {
                cancelPeriodicPersistenceLocked();
                synchronized (storageLock) {
                    bloomIndexService.deleteStorageFiles();
                    blockStoreService.deleteStorageFiles();
                }

                plugin.reloadConfig();
                initializeServicesLocked();
                ensureMaintenanceExecutorLocked();
                scheduleStartupMaintenanceIfNeededLocked();
                schedulePeriodicPersistenceLocked();
            } finally {
                resetInProgress.set(false);
            }
        }
    }

    public boolean isResetInProgress() {
        return resetInProgress.get();
    }

    public int getActiveMegabytes() {
        return bloomIndexService.getActiveMegabytes();
    }

    public String getFormattedFalsePositiveRate() {
        return bloomIndexService.getFormattedFalsePositiveRate();
    }

    public long getExpectedInsertions() {
        return bloomIndexService.getExpectedInsertions();
    }

    public int getPersistIntervalMinutes() {
        return bloomIndexService.getPersistIntervalMinutes();
    }

    public int getBufferMegabytes() {
        return blockStoreService.getBufferMegabytes();
    }

    public long getBufferedBytes() {
        return blockStoreService.getEstimatedBufferedBytes();
    }

    public int getPendingSnapshotCount() {
        return blockStoreService.getPendingSnapshotCount();
    }

    public long getRecordedEventCount() {
        return blockStoreService.getTotalRecordedEvents();
    }

    public long getChunkDiskBytes() {
        return blockStoreService.getUsedDiskBytes();
    }

    public int getMaxChunkDiskMegabytes() {
        return blockStoreService.getMaxTotalSizeMegabytes();
    }

    public int getMaxChunkRecordAgeDays() {
        return blockStoreService.getMaxRecordAgeDays();
    }

    public boolean isStartupMaintenanceRunning() {
        return startupMaintenanceRunning.get();
    }

    public void submitReadTask(Runnable task) {
        executeReadTask(ensureExecutorAvailable(readExecutor, "read executor"), task);
    }

    public void submitWriteTask(Runnable task) {
        executeWriteTask(ensureExecutorAvailable(writeExecutor, "write executor"), task);
    }

    public void submitMaintenanceTask(Runnable task) {
        executeMaintenanceTask(ensureExecutorAvailable(maintenanceExecutor, "maintenance executor"), task);
    }

    public void addBlockMutationObserver(BlockMutationObserver observer) {
        blockMutationObservers.add(observer);
    }

    public void removeBlockMutationObserver(BlockMutationObserver observer) {
        blockMutationObservers.remove(observer);
    }

    public void rewriteActorHistoryInScope(UUID actorUuid, UndoScope undoScope, long cutoffTimestampEpochMillisUtc) throws IOException {
        synchronized (persistenceLock) {
            synchronized (storageLock) {
                blockStoreService.rewriteActorHistoryInScope(actorUuid, undoScope, cutoffTimestampEpochMillisUtc);
            }
        }
    }

    private void initializeServicesLocked() throws IOException {
        blockStoreService = new BlockStoreService(plugin);
        blockStoreService.initialize();

        bloomIndexService = new BloomIndexService(plugin);
        bloomIndexService.initialize(blockStoreService.hasPersistedHistory());
    }

    private void ensureExecutorsLocked() {
        ensureWriteExecutorLocked();
        ensureReadExecutorLocked();
        ensureMaintenanceExecutorLocked();
    }

    private void ensureWriteExecutorLocked() {
        if (writeExecutor != null && !writeExecutor.isShutdown()) {
            return;
        }

        writeExecutor = Executors.newSingleThreadScheduledExecutor(new StorageThreadFactory("LibelulaMapInspector-StorageWrite"));
    }

    private void ensureReadExecutorLocked() {
        if (readExecutor != null && !readExecutor.isShutdown()) {
            return;
        }

        readExecutor = Executors.newSingleThreadExecutor(new StorageThreadFactory("LibelulaMapInspector-StorageRead"));
    }

    private void ensureMaintenanceExecutorLocked() {
        if (maintenanceExecutor != null && !maintenanceExecutor.isShutdown()) {
            return;
        }

        maintenanceExecutor = Executors.newSingleThreadExecutor(new StorageThreadFactory("LibelulaMapInspector-StorageMaintenance"));
    }

    private void scheduleStartupMaintenanceIfNeededLocked() {
        boolean cleanupRequired = blockStoreService.requiresStartupCleanup();
        if (!cleanupRequired) {
            return;
        }

        if (!startupMaintenanceRunning.compareAndSet(false, true)) {
            return;
        }

        maintenanceExecutor.submit(this::runStartupMaintenance);
    }

    private void schedulePeriodicPersistenceLocked() {
        cancelPeriodicPersistenceLocked();
        long periodMinutes = Math.max(1L, bloomIndexService.getPersistIntervalMinutes());
        periodicPersistenceTask = writeExecutor.scheduleAtFixedRate(
                this::persistSafelyFromWriteExecutor,
                periodMinutes,
                periodMinutes,
                TimeUnit.MINUTES
        );
    }

    private void cancelPeriodicPersistenceLocked() {
        if (periodicPersistenceTask != null) {
            periodicPersistenceTask.cancel(false);
            periodicPersistenceTask = null;
        }
    }

    private void requestAsyncPersistence() {
        executeWriteTask(ensureExecutorAvailable(writeExecutor, "write executor"), this::persistSafelyFromWriteExecutor);
    }

    private void runStartupMaintenance() {
        try {
            plugin.getLogger().info("Chunk maintenance started in the background. Old history will be cleaned without blocking startup.");

            BlockStoreService.CleanupReport cleanupReport = new BlockStoreService.CleanupReport(
                    false,
                    blockStoreService.getTotalRecordedEvents(),
                    blockStoreService.getUsedDiskBytes(),
                    blockStoreService.getTotalRecordedEvents(),
                    blockStoreService.getUsedDiskBytes()
            );

            synchronized (persistenceLock) {
                cleanupReport = blockStoreService.performStartupCleanupIfNeeded();
            }

            if (cleanupReport.cleanupPerformed()) {
                long removedEvents = cleanupReport.beforeEventCount() - cleanupReport.afterEventCount();
                long freedBytes = cleanupReport.beforeDiskBytes() - cleanupReport.afterDiskBytes();
                plugin.getLogger().info("Chunk maintenance completed. Removed "
                        + removedEvents + " events and freed " + freedBytes + " bytes.");
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Startup chunk maintenance failed.", exception);
        } finally {
            startupMaintenanceRunning.set(false);
        }
    }

    private void persistSafelyFromWriteExecutor() {
        try {
            synchronized (persistenceLock) {
                persistNowLocked(true);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist LibelulaMapInspector in-memory data.", exception);
        }
    }

    private void shutdownExecutors() {
        ScheduledExecutorService writeExecutorToClose = writeExecutor;
        ExecutorService readExecutorToClose = readExecutor;
        ExecutorService maintenanceExecutorToClose = maintenanceExecutor;
        writeExecutor = null;
        readExecutor = null;
        maintenanceExecutor = null;
        shutdownExecutor(writeExecutorToClose);
        shutdownExecutor(readExecutorToClose);
        shutdownExecutor(maintenanceExecutorToClose);
    }

    private void persistNowLocked(boolean logMessage) throws IOException {
        if (bloomIndexService == null || blockStoreService == null || !blockStoreService.hasUnpersistedData()) {
            return;
        }

        BloomIndexService.PersistedBloomSnapshot bloomSnapshot;
        List<BlockStoreFlushSnapshot> storeSnapshots;
        synchronized (storageLock) {
            bloomSnapshot = bloomIndexService.createPersistedSnapshot();
            storeSnapshots = blockStoreService.prepareSnapshotsForPersistence(System.currentTimeMillis());
        }

        if (storeSnapshots.isEmpty()) {
            return;
        }

        if (logMessage) {
            plugin.getLogger().info("Persisting in-memory changes...");
        }

        for (BlockStoreFlushSnapshot snapshot : storeSnapshots) {
            blockStoreService.persistSnapshot(snapshot);
            blockStoreService.markSnapshotPersisted(snapshot.snapshotId());
        }

        bloomIndexService.persistSnapshot(bloomSnapshot);
    }

    private BlockPositionKey toBlockPositionKey(Location location) {
        if (location == null) {
            throw new IllegalArgumentException("location cannot be null");
        }
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("location world cannot be null");
        }

        return new BlockPositionKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private String resolveActorName(UUID actorUuid) {
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(actorUuid);
        String playerName = offlinePlayer != null ? offlinePlayer.getName() : null;
        return playerName != null ? playerName : actorUuid.toString().toLowerCase(Locale.ROOT);
    }

    private void executeWriteTask(ScheduledExecutorService executor, Runnable task) {
        executor.execute(task);
    }

    private void executeReadTask(ExecutorService executor, Runnable task) {
        executor.execute(task);
    }

    private void executeMaintenanceTask(ExecutorService executor, Runnable task) {
        executor.execute(task);
    }

    private void notifyMutationObservers(BlockPositionKey positionKey, BlockHistoryEntry entry) {
        for (BlockMutationObserver observer : blockMutationObservers) {
            try {
                observer.onTrackedMutation(positionKey, entry);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "A block mutation observer failed while processing a tracked mutation.", exception);
            }
        }
    }

    private void mergeHistoriesInto(Map<BlockPositionKey, List<BlockHistoryEntry>> combinedEntries, Map<BlockPositionKey, List<BlockHistoryEntry>> source) {
        for (Map.Entry<BlockPositionKey, List<BlockHistoryEntry>> entry : source.entrySet()) {
            combinedEntries.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
        }
    }

    private <T extends ExecutorService> T ensureExecutorAvailable(T executor, String name) {
        if (executor == null || executor.isShutdown()) {
            throw new IllegalStateException("The " + name + " is not available.");
        }

        return executor;
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("A LibelulaMapInspector storage executor did not stop cleanly during shutdown.");
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StorageThreadFactory implements ThreadFactory {

        private final String threadName;

        private StorageThreadFactory(String threadName) {
            this.threadName = threadName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        }
    }

    @FunctionalInterface
    public interface BlockMutationObserver {
        void onTrackedMutation(BlockPositionKey positionKey, BlockHistoryEntry entry);
    }
}
