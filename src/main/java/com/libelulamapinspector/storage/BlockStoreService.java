package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.undo.UndoScope;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Manages the buffered block history store that will later feed chunk-backed lookups and restorations.
 */
public final class BlockStoreService {

    private static final int WORLD_METADATA_MAGIC = 0x4C4D4957;
    private static final int WORLD_METADATA_FORMAT_VERSION = 1;
    private static final int STORE_METADATA_MAGIC = 0x4C4D4953;
    private static final int STORE_METADATA_FORMAT_VERSION = 1;
    private static final int REGION_FILE_MAGIC = 0x4C4D4952;
    private static final int REGION_FILE_FORMAT_VERSION = 2;
    private static final int REGION_BATCH_MAGIC = 0x4C4D4942;
    private static final int DEFAULT_BUFFER_MEGABYTES = 50;
    private static final int DEFAULT_MAX_TOTAL_SIZE_MEGABYTES = 1024;
    private static final int DEFAULT_MAX_RECORD_AGE_DAYS = 365;

    private final JavaPlugin plugin;
    private final Path chunksDirectory;
    private final Path storeMetadataFile;
    private final Object bufferLock = new Object();

    private int bufferMegabytes;
    private int maxTotalSizeMegabytes;
    private int maxRecordAgeDays;
    private long totalRecordedEvents;
    private long usedDiskBytes;
    private boolean startupCleanupRequired;

    private BlockHistoryBuffer activeBuffer = new BlockHistoryBuffer();
    private Map<UUID, String> activeWorldNames = new HashMap<>();
    private TreeMap<Long, BlockStoreFlushSnapshot> pendingSnapshots = new TreeMap<>();
    private Map<UUID, String> worldDirectoryNames = new HashMap<>();
    private Set<String> usedWorldDirectoryNames = new HashSet<>();
    private long nextSnapshotId = 1L;

    public BlockStoreService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.chunksDirectory = plugin.getDataFolder().toPath().resolve("chunks");
        this.storeMetadataFile = chunksDirectory.resolve("store.meta.bin");
    }

    public void initialize() throws IOException {
        Files.createDirectories(chunksDirectory);

        int configuredBufferMegabytes = loadBufferMegabytes(plugin.getConfig());
        ChunkLimitsConfiguration limitsConfiguration = loadChunkLimits(plugin.getConfig());
        Map<UUID, String> discoveredWorldDirectories = discoverWorldDirectories();
        long retentionCutoff = System.currentTimeMillis() - Duration.ofDays(limitsConfiguration.maxRecordAgeDays()).toMillis();
        ChunkInventory inventory = scanChunkInventory(retentionCutoff);
        long configuredMaxBytes = limitsConfiguration.maxTotalSizeMegabytes() * 1024L * 1024L;
        boolean cleanupNeeded = inventory.containsExpiredRecords() || inventory.diskBytes() > configuredMaxBytes;

        synchronized (bufferLock) {
            bufferMegabytes = configuredBufferMegabytes;
            maxTotalSizeMegabytes = limitsConfiguration.maxTotalSizeMegabytes();
            maxRecordAgeDays = limitsConfiguration.maxRecordAgeDays();
            totalRecordedEvents = inventory.totalEventCount();
            usedDiskBytes = inventory.diskBytes();
            startupCleanupRequired = cleanupNeeded;
            activeBuffer = new BlockHistoryBuffer();
            activeWorldNames = new HashMap<>();
            pendingSnapshots = new TreeMap<>();
            worldDirectoryNames = new HashMap<>(discoveredWorldDirectories);
            usedWorldDirectoryNames = new HashSet<>(discoveredWorldDirectories.values());
            nextSnapshotId = Math.max(1L, System.currentTimeMillis());
        }

        writeStoreMetadata();

        synchronized (bufferLock) {
            usedDiskBytes = calculateChunkStorageBytes();
        }

        plugin.getLogger().info("Chunk write buffer ready: "
                + bufferMegabytes + " MiB, "
                + getBufferBytesLimit() + " bytes available for pending block history.");
    }

    public boolean append(String worldName, BlockPositionKey positionKey, BlockHistoryEntry entry) {
        synchronized (bufferLock) {
            activeWorldNames.put(positionKey.worldUuid(), sanitizeWorldDisplayName(worldName));
            activeBuffer.append(positionKey, entry);
            return activeBuffer.estimatedBytes() >= getBufferBytesLimit();
        }
    }

    public List<BlockHistoryEntry> copyBufferedTimeline(BlockPositionKey positionKey) {
        synchronized (bufferLock) {
            List<BlockHistoryEntry> mergedEntries = new ArrayList<>();
            for (BlockStoreFlushSnapshot snapshot : pendingSnapshots.values()) {
                mergedEntries.addAll(snapshot.buffer().copyTimeline(positionKey));
            }
            mergedEntries.addAll(activeBuffer.copyTimeline(positionKey));
            return List.copyOf(mergedEntries);
        }
    }

    public Set<UUID> collectBufferedActorsInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        synchronized (bufferLock) {
            Set<UUID> actors = new LinkedHashSet<>();
            for (BlockStoreFlushSnapshot snapshot : pendingSnapshots.values()) {
                actors.addAll(snapshot.buffer().collectActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ));
            }
            actors.addAll(activeBuffer.collectActorsInBox(worldUuid, minX, minY, minZ, maxX, maxY, maxZ));
            return Set.copyOf(actors);
        }
    }

    public Map<BlockPositionKey, List<BlockHistoryEntry>> copyBufferedHistoriesInScope(UndoScope undoScope) {
        synchronized (bufferLock) {
            Map<BlockPositionKey, List<BlockHistoryEntry>> histories = new HashMap<>();
            for (BlockStoreFlushSnapshot snapshot : pendingSnapshots.values()) {
                appendBufferHistories(histories, snapshot.buffer(), undoScope);
            }
            appendBufferHistories(histories, activeBuffer, undoScope);
            return immutableHistories(histories);
        }
    }

    List<BlockHistoryEntry> readPersistedTimeline(BlockPositionKey positionKey) throws IOException {
        Path regionFile = resolveRegionFile(positionKey.worldUuid(), WorldRegionKey.from(WorldChunkKey.from(positionKey)));
        if (regionFile == null || !Files.exists(regionFile)) {
            return List.of();
        }

        WorldChunkKey targetChunk = WorldChunkKey.from(positionKey);
        ChunkLocalBlockKey targetBlock = ChunkLocalBlockKey.from(positionKey);
        List<BlockHistoryEntry> entries = new ArrayList<>();

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            readRegionFileHeader(input, regionFile);
            while (input.available() > 0) {
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                readPersistedTimelineBatch(input, batchHeader.chunkCount(), targetChunk, targetBlock, entries);
            }
        }

        return List.copyOf(entries);
    }

    Set<UUID> collectPersistedActorsInBox(UUID worldUuid, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws IOException {
        int normalizedMinX = Math.min(minX, maxX);
        int normalizedMaxX = Math.max(minX, maxX);
        int normalizedMinY = Math.min(minY, maxY);
        int normalizedMaxY = Math.max(minY, maxY);
        int normalizedMinZ = Math.min(minZ, maxZ);
        int normalizedMaxZ = Math.max(minZ, maxZ);

        int minChunkX = Math.floorDiv(normalizedMinX, 16);
        int maxChunkX = Math.floorDiv(normalizedMaxX, 16);
        int minChunkZ = Math.floorDiv(normalizedMinZ, 16);
        int maxChunkZ = Math.floorDiv(normalizedMaxZ, 16);
        int minRegionX = Math.floorDiv(minChunkX, 32);
        int maxRegionX = Math.floorDiv(maxChunkX, 32);
        int minRegionZ = Math.floorDiv(minChunkZ, 32);
        int maxRegionZ = Math.floorDiv(maxChunkZ, 32);

        Set<UUID> actors = new LinkedHashSet<>();
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                Path regionFile = resolveRegionFile(worldUuid, new WorldRegionKey(worldUuid, regionX, regionZ));
                if (regionFile == null || !Files.exists(regionFile)) {
                    continue;
                }

                collectPersistedActorsInRegion(regionFile, minChunkX, maxChunkX, minChunkZ, maxChunkZ, normalizedMinX, normalizedMinY, normalizedMinZ, normalizedMaxX, normalizedMaxY, normalizedMaxZ, actors);
            }
        }

        return Set.copyOf(actors);
    }

    Map<BlockPositionKey, List<BlockHistoryEntry>> readPersistedHistoriesInScope(UndoScope undoScope) throws IOException {
        Map<BlockPositionKey, List<BlockHistoryEntry>> histories = new HashMap<>();
        for (Path regionFile : regionFilesForScope(undoScope)) {
            readPersistedHistoriesFromRegion(regionFile, undoScope, histories);
        }
        return immutableHistories(histories);
    }

    public List<BlockStoreFlushSnapshot> prepareSnapshotsForPersistence(long createdAtEpochMillisUtc) {
        synchronized (bufferLock) {
            if (!activeBuffer.isEmpty()) {
                BlockStoreFlushSnapshot snapshot = new BlockStoreFlushSnapshot(
                        nextSnapshotId++,
                        createdAtEpochMillisUtc,
                        Map.copyOf(activeWorldNames),
                        activeBuffer
                );
                pendingSnapshots.put(snapshot.snapshotId(), snapshot);
                activeBuffer = new BlockHistoryBuffer();
                activeWorldNames = new HashMap<>();
            }

            return List.copyOf(pendingSnapshots.values());
        }
    }

    public void persistSnapshot(BlockStoreFlushSnapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.buffer().isEmpty()) {
            return;
        }

        Files.createDirectories(chunksDirectory);

        Map<UUID, String> directoryNamesByWorld = rememberWorldDirectories(snapshot.worldNames());
        for (Map.Entry<UUID, String> entry : snapshot.worldNames().entrySet()) {
            String directoryName = directoryNamesByWorld.get(entry.getKey());
            writeWorldMetadata(entry.getKey(), entry.getValue(), directoryName);
        }

        long appendedEvents = 0L;
        for (RegionBatch regionBatch : groupByRegion(snapshot, directoryNamesByWorld)) {
            appendedEvents += appendRegionBatch(regionBatch, snapshot).eventCount();
        }

        synchronized (bufferLock) {
            totalRecordedEvents += appendedEvents;
        }

        writeStoreMetadata();
        synchronized (bufferLock) {
            usedDiskBytes = calculateChunkStorageBytes();
        }
    }

    public void markSnapshotPersisted(long snapshotId) {
        synchronized (bufferLock) {
            pendingSnapshots.remove(snapshotId);
        }
    }

    public CleanupReport performStartupCleanupIfNeeded() throws IOException {
        synchronized (bufferLock) {
            if (!startupCleanupRequired) {
                return new CleanupReport(false, totalRecordedEvents, usedDiskBytes, totalRecordedEvents, usedDiskBytes);
            }
        }

        long beforeEvents;
        long beforeBytes;
        synchronized (bufferLock) {
            beforeEvents = totalRecordedEvents;
            beforeBytes = usedDiskBytes;
        }

        long retentionCutoff = calculateRetentionCutoff();
        ChunkInventory inventory = scanChunkInventory(calculateRetentionCutoff());

        if (inventory.containsExpiredRecords()) {
            rewriteForAge(retentionCutoff, inventory.batchReferences());
            inventory = scanChunkInventory(calculateRetentionCutoff());
        }

        long maxTotalBytes = getMaxTotalSizeBytes();
        if (inventory.diskBytes() > maxTotalBytes) {
            rewriteForSize(inventory, maxTotalBytes);
            inventory = scanChunkInventory(calculateRetentionCutoff());
        }

        synchronized (bufferLock) {
            totalRecordedEvents = inventory.totalEventCount();
            startupCleanupRequired = false;
        }
        writeStoreMetadata();
        synchronized (bufferLock) {
            usedDiskBytes = calculateChunkStorageBytes();
        }

        return new CleanupReport(
                true,
                beforeEvents,
                beforeBytes,
                inventory.totalEventCount(),
                getUsedDiskBytes()
        );
    }

    public void rewriteActorHistoryInScope(UUID actorUuid, UndoScope undoScope, long cutoffTimestampEpochMillisUtc) throws IOException {
        synchronized (bufferLock) {
            activeBuffer = rewriteBufferForUndo(activeBuffer, undoScope, actorUuid, cutoffTimestampEpochMillisUtc);

            TreeMap<Long, BlockStoreFlushSnapshot> rewrittenSnapshots = new TreeMap<>();
            for (BlockStoreFlushSnapshot snapshot : pendingSnapshots.values()) {
                BlockHistoryBuffer rewrittenBuffer = rewriteBufferForUndo(snapshot.buffer(), undoScope, actorUuid, cutoffTimestampEpochMillisUtc);
                if (rewrittenBuffer.isEmpty()) {
                    continue;
                }

                rewrittenSnapshots.put(snapshot.snapshotId(), new BlockStoreFlushSnapshot(
                        snapshot.snapshotId(),
                        snapshot.createdAtEpochMillisUtc(),
                        snapshot.worldNames(),
                        rewrittenBuffer
                ));
            }
            pendingSnapshots = rewrittenSnapshots;
        }

        for (Path regionFile : regionFilesForScope(undoScope)) {
            rewriteRegionFileForUndo(regionFile, undoScope, actorUuid, cutoffTimestampEpochMillisUtc);
        }

        ChunkInventory inventory = scanChunkInventory(calculateRetentionCutoff());
        synchronized (bufferLock) {
            totalRecordedEvents = inventory.totalEventCount();
            usedDiskBytes = inventory.diskBytes();
        }
        writeStoreMetadata();
    }

    public void deleteStorageFiles() throws IOException {
        synchronized (bufferLock) {
            activeBuffer = new BlockHistoryBuffer();
            activeWorldNames = new HashMap<>();
            pendingSnapshots = new TreeMap<>();
            worldDirectoryNames = new HashMap<>();
            usedWorldDirectoryNames = new HashSet<>();
            totalRecordedEvents = 0L;
            usedDiskBytes = 0L;
            startupCleanupRequired = false;
            nextSnapshotId = 1L;
        }

        StorageBinaryIO.deleteRecursively(chunksDirectory);
    }

    public boolean hasPersistedHistory() {
        synchronized (bufferLock) {
            return totalRecordedEvents > 0L;
        }
    }

    public boolean requiresStartupCleanup() {
        synchronized (bufferLock) {
            return startupCleanupRequired;
        }
    }

    public boolean hasUnpersistedData() {
        synchronized (bufferLock) {
            return !activeBuffer.isEmpty() || !pendingSnapshots.isEmpty();
        }
    }

    public int getBufferMegabytes() {
        synchronized (bufferLock) {
            return bufferMegabytes;
        }
    }

    public long getBufferBytesLimit() {
        synchronized (bufferLock) {
            return bufferMegabytes * 1024L * 1024L;
        }
    }

    public long getEstimatedBufferedBytes() {
        synchronized (bufferLock) {
            long bytes = activeBuffer.estimatedBytes();
            for (BlockStoreFlushSnapshot snapshot : pendingSnapshots.values()) {
                bytes += snapshot.buffer().estimatedBytes();
            }
            return bytes;
        }
    }

    public int getPendingSnapshotCount() {
        synchronized (bufferLock) {
            return pendingSnapshots.size();
        }
    }

    public long getTotalRecordedEvents() {
        synchronized (bufferLock) {
            return totalRecordedEvents;
        }
    }

    public long getUsedDiskBytes() {
        synchronized (bufferLock) {
            return usedDiskBytes;
        }
    }

    public int getMaxTotalSizeMegabytes() {
        synchronized (bufferLock) {
            return maxTotalSizeMegabytes;
        }
    }

    public int getMaxRecordAgeDays() {
        synchronized (bufferLock) {
            return maxRecordAgeDays;
        }
    }

    private int loadBufferMegabytes(FileConfiguration configuration) {
        int configuredBufferMegabytes = configuration.getInt("index.buffer-megabytes", DEFAULT_BUFFER_MEGABYTES);
        if (configuredBufferMegabytes < 1) {
            plugin.getLogger().warning("index.buffer-megabytes must be greater than zero. Falling back to " + DEFAULT_BUFFER_MEGABYTES + ".");
            return DEFAULT_BUFFER_MEGABYTES;
        }
        return configuredBufferMegabytes;
    }

    private ChunkLimitsConfiguration loadChunkLimits(FileConfiguration configuration) {
        int configuredMaxTotalSizeMegabytes = configuration.getInt("chunks.limits.max-total-size-megabytes", DEFAULT_MAX_TOTAL_SIZE_MEGABYTES);
        if (configuredMaxTotalSizeMegabytes < 1) {
            plugin.getLogger().warning("chunks.limits.max-total-size-megabytes must be greater than zero. Falling back to " + DEFAULT_MAX_TOTAL_SIZE_MEGABYTES + ".");
            configuredMaxTotalSizeMegabytes = DEFAULT_MAX_TOTAL_SIZE_MEGABYTES;
        }

        int configuredMaxRecordAgeDays = configuration.getInt("chunks.limits.max-record-age-days", DEFAULT_MAX_RECORD_AGE_DAYS);
        if (configuredMaxRecordAgeDays < 1) {
            plugin.getLogger().warning("chunks.limits.max-record-age-days must be greater than zero. Falling back to " + DEFAULT_MAX_RECORD_AGE_DAYS + ".");
            configuredMaxRecordAgeDays = DEFAULT_MAX_RECORD_AGE_DAYS;
        }

        return new ChunkLimitsConfiguration(configuredMaxTotalSizeMegabytes, configuredMaxRecordAgeDays);
    }

    private Map<UUID, String> discoverWorldDirectories() throws IOException {
        Map<UUID, String> discoveredWorldDirectories = new HashMap<>();
        if (!Files.exists(chunksDirectory)) {
            return discoveredWorldDirectories;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(chunksDirectory)) {
            for (Path worldDirectory : stream.filter(Files::isDirectory).toList()) {
                Path metadataFile = worldDirectory.resolve("world.meta.bin");
                if (!Files.exists(metadataFile)) {
                    continue;
                }

                try {
                    UUID worldUuid = readWorldMetadata(metadataFile);
                    discoveredWorldDirectories.put(worldUuid, worldDirectory.getFileName().toString());
                } catch (IOException exception) {
                    plugin.getLogger().warning("Could not read world metadata from " + metadataFile + ". The directory will be ignored for now.");
                }
            }
        }

        return discoveredWorldDirectories;
    }

    private UUID readWorldMetadata(Path metadataFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(metadataFile)))) {
            int magic = input.readInt();
            int formatVersion = input.readInt();
            if (magic != WORLD_METADATA_MAGIC || formatVersion != WORLD_METADATA_FORMAT_VERSION) {
                throw new IOException("Unsupported world metadata format.");
            }

            return StorageBinaryIO.readUuid(input);
        }
    }

    private Map<UUID, String> rememberWorldDirectories(Map<UUID, String> worldNames) {
        synchronized (bufferLock) {
            Map<UUID, String> resolvedDirectories = new HashMap<>();
            for (Map.Entry<UUID, String> entry : worldNames.entrySet()) {
                String directoryName = worldDirectoryNames.get(entry.getKey());
                if (directoryName == null) {
                    directoryName = reserveWorldDirectoryName(entry.getValue());
                    worldDirectoryNames.put(entry.getKey(), directoryName);
                    usedWorldDirectoryNames.add(directoryName);
                }
                resolvedDirectories.put(entry.getKey(), directoryName);
            }
            return resolvedDirectories;
        }
    }

    private String reserveWorldDirectoryName(String worldName) {
        String baseName = sanitizeWorldDirectoryName(worldName);
        String candidate = baseName;
        int suffix = 1;
        while (usedWorldDirectoryNames.contains(candidate)) {
            candidate = baseName + "-" + suffix++;
        }
        return candidate;
    }

    private void writeWorldMetadata(UUID worldUuid, String worldName, String directoryName) throws IOException {
        Path worldDirectory = chunksDirectory.resolve(directoryName);
        Files.createDirectories(worldDirectory);

        Path metadataFile = worldDirectory.resolve("world.meta.bin");
        Path temporaryFile = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)))) {
            output.writeInt(WORLD_METADATA_MAGIC);
            output.writeInt(WORLD_METADATA_FORMAT_VERSION);
            StorageBinaryIO.writeUuid(output, worldUuid);
            StorageBinaryIO.writeString(output, worldName);
        }

        StorageBinaryIO.moveReplacing(temporaryFile, metadataFile);
    }

    private void writeStoreMetadata() throws IOException {
        Files.createDirectories(chunksDirectory);

        long eventCount;
        synchronized (bufferLock) {
            eventCount = totalRecordedEvents;
        }

        Path temporaryFile = storeMetadataFile.resolveSibling(storeMetadataFile.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)))) {
            output.writeInt(STORE_METADATA_MAGIC);
            output.writeInt(STORE_METADATA_FORMAT_VERSION);
            output.writeLong(eventCount);
        }

        StorageBinaryIO.moveReplacing(temporaryFile, storeMetadataFile);
    }

    private List<RegionBatch> groupByRegion(BlockStoreFlushSnapshot snapshot, Map<UUID, String> directoryNamesByWorld) {
        Map<RegionBatchKey, RegionBatchBuilder> builders = new HashMap<>();

        for (Map.Entry<WorldChunkKey, ChunkHistoryBuffer> entry : snapshot.buffer().worldChunks().entrySet()) {
            WorldChunkKey chunkKey = entry.getKey();
            WorldRegionKey regionKey = WorldRegionKey.from(chunkKey);
            String directoryName = directoryNamesByWorld.get(chunkKey.worldUuid());
            RegionBatchKey batchKey = new RegionBatchKey(chunkKey.worldUuid(), directoryName, regionKey.regionX(), regionKey.regionZ());
            builders.computeIfAbsent(batchKey, ignored -> new RegionBatchBuilder(batchKey))
                    .chunks()
                    .put(chunkKey, entry.getValue());
        }

        List<RegionBatch> batches = new ArrayList<>();
        for (RegionBatchBuilder builder : builders.values()) {
            batches.add(builder.build());
        }
        batches.sort((left, right) -> {
            int compareWorld = left.directoryName().compareTo(right.directoryName());
            if (compareWorld != 0) {
                return compareWorld;
            }

            int compareRegionX = Integer.compare(left.regionX(), right.regionX());
            if (compareRegionX != 0) {
                return compareRegionX;
            }

            return Integer.compare(left.regionZ(), right.regionZ());
        });
        return batches;
    }

    private BatchWriteResult appendRegionBatch(RegionBatch regionBatch, BlockStoreFlushSnapshot snapshot) throws IOException {
        Path regionDirectory = chunksDirectory.resolve(regionBatch.directoryName()).resolve("region");
        Files.createDirectories(regionDirectory);

        Path regionFile = regionDirectory.resolve("r." + regionBatch.regionX() + "." + regionBatch.regionZ() + ".lmi");
        boolean newFile = !Files.exists(regionFile);
        SerializedBatch serializedBatch = serializeBatch(regionBatch.worldUuid(), regionBatch.regionX(), regionBatch.regionZ(), regionBatch.chunks(), snapshot.snapshotId(), snapshot.createdAtEpochMillisUtc());

        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                regionFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE)))) {
            if (newFile) {
                writeRegionFileHeader(output, regionBatch.worldUuid(), regionBatch.regionX(), regionBatch.regionZ());
            }

            writeSerializedBatch(output, serializedBatch);
        }

        return new BatchWriteResult(serializedBatch.eventCount());
    }

    private Path resolveRegionFile(UUID worldUuid, WorldRegionKey regionKey) {
        String worldDirectoryName;
        synchronized (bufferLock) {
            worldDirectoryName = worldDirectoryNames.get(worldUuid);
        }

        if (worldDirectoryName == null) {
            return null;
        }

        return chunksDirectory
                .resolve(worldDirectoryName)
                .resolve("region")
                .resolve("r." + regionKey.regionX() + "." + regionKey.regionZ() + ".lmi");
    }

    private void readPersistedTimelineBatch(
            DataInputStream input,
            int chunkCount,
            WorldChunkKey targetChunk,
            ChunkLocalBlockKey targetBlock,
            List<BlockHistoryEntry> entries
    ) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int timelineCount = input.readInt();
            boolean matchingChunk = chunkX == targetChunk.chunkX() && chunkZ == targetChunk.chunkZ();

            for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                int localX = input.readUnsignedByte();
                int blockY = input.readInt();
                int localZ = input.readUnsignedByte();
                int entryCount = input.readInt();
                boolean matchingBlock = matchingChunk
                        && localX == targetBlock.localX()
                        && blockY == targetBlock.blockY()
                        && localZ == targetBlock.localZ();

                if (matchingBlock) {
                    for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                        entries.add(readHistoryEntry(input));
                    }
                } else {
                    skipHistoryEntries(input, entryCount);
                }
            }
        }
    }

    private void collectPersistedActorsInRegion(
            Path regionFile,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            Set<UUID> actors
    ) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            readRegionFileHeader(input, regionFile);
            while (input.available() > 0) {
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                readActorBatch(input, batchHeader.chunkCount(), minChunkX, maxChunkX, minChunkZ, maxChunkZ, minX, minY, minZ, maxX, maxY, maxZ, actors);
            }
        }
    }

    private void readActorBatch(
            DataInputStream input,
            int chunkCount,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            Set<UUID> actors
    ) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int timelineCount = input.readInt();
            boolean chunkInsideBox = chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;

            for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                int localX = input.readUnsignedByte();
                int blockY = input.readInt();
                int localZ = input.readUnsignedByte();
                int entryCount = input.readInt();
                int absoluteX = (chunkX << 4) + localX;
                int absoluteZ = (chunkZ << 4) + localZ;
                boolean blockInsideBox = chunkInsideBox
                        && absoluteX >= minX && absoluteX <= maxX
                        && blockY >= minY && blockY <= maxY
                        && absoluteZ >= minZ && absoluteZ <= maxZ;

                if (blockInsideBox) {
                    for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                        actors.add(readHistoryEntry(input).actorUuid());
                    }
                } else {
                    skipHistoryEntries(input, entryCount);
                }
            }
        }
    }

    private void appendBufferHistories(
            Map<BlockPositionKey, List<BlockHistoryEntry>> histories,
            BlockHistoryBuffer buffer,
            UndoScope undoScope
    ) {
        for (Map.Entry<WorldChunkKey, ChunkHistoryBuffer> chunkEntry : buffer.worldChunks().entrySet()) {
            WorldChunkKey chunkKey = chunkEntry.getKey();
            if (!chunkKey.worldUuid().equals(undoScope.worldUuid())) {
                continue;
            }

            for (Map.Entry<ChunkLocalBlockKey, BlockHistoryTimeline> timelineEntry : chunkEntry.getValue().timelines().entrySet()) {
                ChunkLocalBlockKey blockKey = timelineEntry.getKey();
                BlockPositionKey positionKey = new BlockPositionKey(
                        chunkKey.worldUuid(),
                        blockKey.absoluteX(chunkKey.chunkX()),
                        blockKey.blockY(),
                        blockKey.absoluteZ(chunkKey.chunkZ())
                );
                if (!undoScope.contains(positionKey)) {
                    continue;
                }

                histories.computeIfAbsent(positionKey, ignored -> new ArrayList<>()).addAll(timelineEntry.getValue().entries());
            }
        }
    }

    private Map<BlockPositionKey, List<BlockHistoryEntry>> immutableHistories(Map<BlockPositionKey, List<BlockHistoryEntry>> histories) {
        Map<BlockPositionKey, List<BlockHistoryEntry>> immutable = new HashMap<>();
        for (Map.Entry<BlockPositionKey, List<BlockHistoryEntry>> entry : histories.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
    }

    private List<Path> regionFilesForScope(UndoScope undoScope) throws IOException {
        String worldDirectoryName;
        synchronized (bufferLock) {
            worldDirectoryName = worldDirectoryNames.get(undoScope.worldUuid());
        }

        if (worldDirectoryName == null) {
            return List.of();
        }

        Path regionDirectory = chunksDirectory.resolve(worldDirectoryName).resolve("region");
        if (!Files.exists(regionDirectory)) {
            return List.of();
        }

        try (java.util.stream.Stream<Path> regionFiles = Files.list(regionDirectory)) {
            List<Path> files = regionFiles
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".lmi"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            if (undoScope.isWorldScope()) {
                return files;
            }

            int minRegionX = Math.floorDiv(Math.floorDiv(undoScope.minX(), 16), 32);
            int maxRegionX = Math.floorDiv(Math.floorDiv(undoScope.maxX(), 16), 32);
            int minRegionZ = Math.floorDiv(Math.floorDiv(undoScope.minZ(), 16), 32);
            int maxRegionZ = Math.floorDiv(Math.floorDiv(undoScope.maxZ(), 16), 32);
            return files.stream()
                    .filter(path -> regionFileMatchesBounds(path, minRegionX, maxRegionX, minRegionZ, maxRegionZ))
                    .toList();
        }
    }

    private boolean regionFileMatchesBounds(Path regionFile, int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ) {
        String fileName = regionFile.getFileName().toString();
        String[] parts = fileName.substring(0, fileName.length() - 4).split("\\.");
        if (parts.length != 3) {
            return false;
        }

        try {
            int regionX = Integer.parseInt(parts[1]);
            int regionZ = Integer.parseInt(parts[2]);
            return regionX >= minRegionX && regionX <= maxRegionX && regionZ >= minRegionZ && regionZ <= maxRegionZ;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void readPersistedHistoriesFromRegion(
            Path regionFile,
            UndoScope undoScope,
            Map<BlockPositionKey, List<BlockHistoryEntry>> histories
    ) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            readRegionFileHeader(input, regionFile);
            while (input.available() > 0) {
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                readHistoryBatch(input, batchHeader.chunkCount(), undoScope, histories);
            }
        }
    }

    private void readHistoryBatch(
            DataInputStream input,
            int chunkCount,
            UndoScope undoScope,
            Map<BlockPositionKey, List<BlockHistoryEntry>> histories
    ) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int timelineCount = input.readInt();

            for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                int localX = input.readUnsignedByte();
                int blockY = input.readInt();
                int localZ = input.readUnsignedByte();
                int entryCount = input.readInt();

                BlockPositionKey positionKey = new BlockPositionKey(
                        undoScope.worldUuid(),
                        (chunkX << 4) + localX,
                        blockY,
                        (chunkZ << 4) + localZ
                );

                if (!undoScope.contains(positionKey)) {
                    skipHistoryEntries(input, entryCount);
                    continue;
                }

                List<BlockHistoryEntry> entries = histories.computeIfAbsent(positionKey, ignored -> new ArrayList<>());
                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    entries.add(readHistoryEntry(input));
                }
            }
        }
    }

    private BlockHistoryBuffer rewriteBufferForUndo(
            BlockHistoryBuffer source,
            UndoScope undoScope,
            UUID actorUuid,
            long cutoffTimestampEpochMillisUtc
    ) {
        BlockHistoryBuffer rewritten = new BlockHistoryBuffer();
        for (Map.Entry<WorldChunkKey, ChunkHistoryBuffer> chunkEntry : source.worldChunks().entrySet()) {
            WorldChunkKey chunkKey = chunkEntry.getKey();
            for (Map.Entry<ChunkLocalBlockKey, BlockHistoryTimeline> timelineEntry : chunkEntry.getValue().timelines().entrySet()) {
                ChunkLocalBlockKey blockKey = timelineEntry.getKey();
                BlockPositionKey positionKey = new BlockPositionKey(
                        chunkKey.worldUuid(),
                        blockKey.absoluteX(chunkKey.chunkX()),
                        blockKey.blockY(),
                        blockKey.absoluteZ(chunkKey.chunkZ())
                );

                List<BlockHistoryEntry> filteredEntries = filterEntriesForUndo(
                        timelineEntry.getValue().entries(),
                        undoScope.contains(positionKey),
                        actorUuid,
                        cutoffTimestampEpochMillisUtc
                );

                for (BlockHistoryEntry filteredEntry : filteredEntries) {
                    rewritten.append(positionKey, filteredEntry);
                }
            }
        }
        return rewritten;
    }

    private List<BlockHistoryEntry> filterEntriesForUndo(
            List<BlockHistoryEntry> entries,
            boolean inUndoScope,
            UUID actorUuid,
            long cutoffTimestampEpochMillisUtc
    ) {
        if (!inUndoScope) {
            return entries;
        }

        List<BlockHistoryEntry> filteredEntries = new ArrayList<>(entries.size());
        for (BlockHistoryEntry entry : entries) {
            if (entry.actorUuid().equals(actorUuid) && entry.timestampEpochMillisUtc() <= cutoffTimestampEpochMillisUtc) {
                continue;
            }
            filteredEntries.add(entry);
        }
        return filteredEntries;
    }

    private void rewriteRegionFileForUndo(Path regionFile, UndoScope undoScope, UUID actorUuid, long cutoffTimestampEpochMillisUtc) throws IOException {
        RegionRewriteAccumulator accumulator;
        RegionFileHeader header;

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            header = readRegionFileHeader(input, regionFile);
            accumulator = new RegionRewriteAccumulator(header.worldUuid(), header.regionX(), header.regionZ());

            while (input.available() > 0) {
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                byte[] payload = new byte[batchHeader.payloadLength()];
                input.readFully(payload);

                try (DataInputStream payloadInput = new DataInputStream(new ByteArrayInputStream(payload))) {
                    readUndoBatchIntoAccumulator(payloadInput, batchHeader.chunkCount(), accumulator, undoScope, actorUuid, cutoffTimestampEpochMillisUtc);
                }
            }
        }

        if (accumulator.isEmpty()) {
            Files.deleteIfExists(regionFile);
            return;
        }

        writeRewrittenRegionFile(regionFile, accumulator);
    }

    private void readUndoBatchIntoAccumulator(
            DataInputStream input,
            int chunkCount,
            RegionRewriteAccumulator accumulator,
            UndoScope undoScope,
            UUID actorUuid,
            long cutoffTimestampEpochMillisUtc
    ) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int timelineCount = input.readInt();
            WorldChunkKey chunkKey = new WorldChunkKey(accumulator.worldUuid(), chunkX, chunkZ);

            for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                int localX = input.readUnsignedByte();
                int blockY = input.readInt();
                int localZ = input.readUnsignedByte();
                int entryCount = input.readInt();
                ChunkLocalBlockKey blockKey = new ChunkLocalBlockKey(localX, blockY, localZ);
                BlockPositionKey positionKey = new BlockPositionKey(
                        accumulator.worldUuid(),
                        (chunkX << 4) + localX,
                        blockY,
                        (chunkZ << 4) + localZ
                );

                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    BlockHistoryEntry historyEntry = readHistoryEntry(input);
                    if (undoScope.contains(positionKey)
                            && historyEntry.actorUuid().equals(actorUuid)
                            && historyEntry.timestampEpochMillisUtc() <= cutoffTimestampEpochMillisUtc) {
                        continue;
                    }

                    accumulator.append(chunkKey, blockKey, historyEntry);
                }
            }
        }
    }

    private void rewriteForAge(long retentionCutoff, List<RegionBatchReference> batchReferences) throws IOException {
        Map<Path, List<RegionBatchReference>> byFile = groupBatchReferencesByFile(batchReferences);
        for (Map.Entry<Path, List<RegionBatchReference>> entry : byFile.entrySet()) {
            boolean requiresRewrite = entry.getValue().stream().anyMatch(reference -> reference.minTimestamp() < retentionCutoff);
            if (!requiresRewrite) {
                continue;
            }

            rewriteRegionFile(entry.getKey(), reference -> {
                if (reference.maxTimestamp() < retentionCutoff) {
                    return RewriteDecision.dropBatch();
                }
                if (reference.minTimestamp() >= retentionCutoff) {
                    return RewriteDecision.keepWholeBatch();
                }
                return RewriteDecision.filterByCutoff(retentionCutoff);
            });
        }
    }

    private void rewriteForSize(ChunkInventory inventory, long maxTotalBytes) throws IOException {
        List<RegionBatchReference> sortedReferences = new ArrayList<>(inventory.batchReferences());
        sortedReferences.sort(Comparator
                .comparingLong(RegionBatchReference::maxTimestamp)
                .thenComparingLong(RegionBatchReference::minTimestamp)
                .thenComparing(reference -> reference.path().toString())
                .thenComparingLong(RegionBatchReference::batchOffset));

        long bytesToFree = inventory.diskBytes() - maxTotalBytes;
        long scheduledBytes = 0L;
        Set<BatchIdentity> batchesToDrop = new HashSet<>();
        for (RegionBatchReference reference : sortedReferences) {
            if (scheduledBytes >= bytesToFree) {
                break;
            }

            batchesToDrop.add(reference.identity());
            scheduledBytes += reference.totalLength();
        }

        if (batchesToDrop.isEmpty()) {
            return;
        }

        Map<Path, List<RegionBatchReference>> byFile = groupBatchReferencesByFile(inventory.batchReferences());
        for (Map.Entry<Path, List<RegionBatchReference>> entry : byFile.entrySet()) {
            boolean requiresRewrite = entry.getValue().stream().anyMatch(reference -> batchesToDrop.contains(reference.identity()));
            if (!requiresRewrite) {
                continue;
            }

            rewriteRegionFile(entry.getKey(), reference -> batchesToDrop.contains(reference.identity())
                    ? RewriteDecision.dropBatch()
                    : RewriteDecision.keepWholeBatch());
        }
    }

    private Map<Path, List<RegionBatchReference>> groupBatchReferencesByFile(List<RegionBatchReference> batchReferences) {
        Map<Path, List<RegionBatchReference>> byFile = new HashMap<>();
        for (RegionBatchReference reference : batchReferences) {
            byFile.computeIfAbsent(reference.path(), ignored -> new ArrayList<>()).add(reference);
        }

        for (List<RegionBatchReference> references : byFile.values()) {
            references.sort(Comparator.comparingLong(RegionBatchReference::batchOffset));
        }
        return byFile;
    }

    private void rewriteRegionFile(Path regionFile, RewritePlanner rewritePlanner) throws IOException {
        RegionRewriteAccumulator accumulator;
        RegionFileHeader header;
        long batchOffset = Integer.BYTES + Integer.BYTES + (2L * Long.BYTES) + Integer.BYTES + Integer.BYTES;

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            header = readRegionFileHeader(input, regionFile);
            accumulator = new RegionRewriteAccumulator(header.worldUuid(), header.regionX(), header.regionZ());

            while (input.available() > 0) {
                long currentBatchOffset = batchOffset;
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                byte[] payload = new byte[batchHeader.payloadLength()];
                input.readFully(payload);
                batchOffset += batchHeader.totalLength();

                RewriteDecision decision = rewritePlanner.plan(new RegionBatchReference(
                        regionFile,
                        header.worldUuid(),
                        header.regionX(),
                        header.regionZ(),
                        currentBatchOffset,
                        batchHeader.payloadLength(),
                        batchHeader.snapshotId(),
                        batchHeader.createdAtEpochMillisUtc(),
                        batchHeader.eventCount(),
                        batchHeader.minTimestamp(),
                        batchHeader.maxTimestamp()
                ));

                if (decision.dropCurrentBatch()) {
                    continue;
                }

                try (DataInputStream payloadInput = new DataInputStream(new ByteArrayInputStream(payload))) {
                    readBatchIntoAccumulator(payloadInput, batchHeader.chunkCount(), accumulator, decision.retentionCutoff());
                }
            }
        }

        if (accumulator.isEmpty()) {
            Files.deleteIfExists(regionFile);
            return;
        }

        writeRewrittenRegionFile(regionFile, accumulator);
    }

    private void readBatchIntoAccumulator(
            DataInputStream input,
            int chunkCount,
            RegionRewriteAccumulator accumulator,
            long retentionCutoff
    ) throws IOException {
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int timelineCount = input.readInt();
            WorldChunkKey chunkKey = new WorldChunkKey(accumulator.worldUuid(), chunkX, chunkZ);

            for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                int localX = input.readUnsignedByte();
                int blockY = input.readInt();
                int localZ = input.readUnsignedByte();
                int entryCount = input.readInt();
                ChunkLocalBlockKey blockKey = new ChunkLocalBlockKey(localX, blockY, localZ);

                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    BlockHistoryEntry historyEntry = readHistoryEntry(input);
                    if (retentionCutoff > Long.MIN_VALUE && historyEntry.timestampEpochMillisUtc() < retentionCutoff) {
                        continue;
                    }

                    accumulator.append(chunkKey, blockKey, historyEntry);
                }
            }
        }
    }

    private void writeRewrittenRegionFile(Path regionFile, RegionRewriteAccumulator accumulator) throws IOException {
        Path temporaryFile = regionFile.resolveSibling(regionFile.getFileName() + ".tmp");
        long now = System.currentTimeMillis();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)))) {
            writeRegionFileHeader(output, accumulator.worldUuid(), accumulator.regionX(), accumulator.regionZ());
            SerializedBatch serializedBatch = serializeBatch(
                    accumulator.worldUuid(),
                    accumulator.regionX(),
                    accumulator.regionZ(),
                    accumulator.chunks(),
                    now,
                    now
            );
            writeSerializedBatch(output, serializedBatch);
        }

        StorageBinaryIO.moveReplacing(temporaryFile, regionFile);
    }

    private ChunkInventory scanChunkInventory(long retentionCutoff) throws IOException {
        if (!Files.exists(chunksDirectory)) {
            return new ChunkInventory(List.of(), 0L, 0L, false);
        }

        List<RegionBatchReference> batchReferences = new ArrayList<>();
        long totalEvents = 0L;

        try (java.util.stream.Stream<Path> worlds = Files.list(chunksDirectory)) {
            for (Path worldDirectory : worlds.filter(Files::isDirectory).toList()) {
                Path regionDirectory = worldDirectory.resolve("region");
                if (!Files.exists(regionDirectory)) {
                    continue;
                }

                try (java.util.stream.Stream<Path> regionFiles = Files.list(regionDirectory)) {
                    for (Path regionFile : regionFiles
                            .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".lmi"))
                            .toList()) {
                        totalEvents += scanRegionFile(regionFile, batchReferences);
                    }
                }
            }
        }

        long diskBytes = calculateChunkStorageBytes();
        boolean containsExpiredRecords = batchReferences.stream().anyMatch(reference -> reference.minTimestamp() < retentionCutoff);
        return new ChunkInventory(List.copyOf(batchReferences), totalEvents, diskBytes, containsExpiredRecords);
    }

    private long scanRegionFile(Path regionFile, List<RegionBatchReference> batchReferences) throws IOException {
        long totalEvents = 0L;
        long batchOffset = Integer.BYTES + Integer.BYTES + (2L * Long.BYTES) + Integer.BYTES + Integer.BYTES;

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            RegionFileHeader header = readRegionFileHeader(input, regionFile);
            while (input.available() > 0) {
                long currentBatchOffset = batchOffset;
                RegionBatchHeader batchHeader = readRegionBatchHeader(input);
                input.skipNBytes(batchHeader.payloadLength());

                RegionBatchReference reference = new RegionBatchReference(
                        regionFile,
                        header.worldUuid(),
                        header.regionX(),
                        header.regionZ(),
                        currentBatchOffset,
                        batchHeader.payloadLength(),
                        batchHeader.snapshotId(),
                        batchHeader.createdAtEpochMillisUtc(),
                        batchHeader.eventCount(),
                        batchHeader.minTimestamp(),
                        batchHeader.maxTimestamp()
                );
                batchReferences.add(reference);
                totalEvents += batchHeader.eventCount();
                batchOffset += batchHeader.totalLength();
            }
        }

        return totalEvents;
    }

    private SerializedBatch serializeBatch(
            UUID worldUuid,
            int regionX,
            int regionZ,
            Map<WorldChunkKey, ChunkHistoryBuffer> chunks,
            long snapshotId,
            long createdAtEpochMillisUtc
    ) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        DataOutputStream payloadOutput = new DataOutputStream(payloadBytes);

        long eventCount = 0L;
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;

        List<Map.Entry<WorldChunkKey, ChunkHistoryBuffer>> chunkEntries = new ArrayList<>(chunks.entrySet());
        chunkEntries.sort((left, right) -> {
            int compareChunkX = Integer.compare(left.getKey().chunkX(), right.getKey().chunkX());
            if (compareChunkX != 0) {
                return compareChunkX;
            }

            return Integer.compare(left.getKey().chunkZ(), right.getKey().chunkZ());
        });

        for (Map.Entry<WorldChunkKey, ChunkHistoryBuffer> chunkEntry : chunkEntries) {
            WorldChunkKey chunkKey = chunkEntry.getKey();
            ChunkHistoryBuffer chunkHistoryBuffer = chunkEntry.getValue();

            payloadOutput.writeInt(chunkKey.chunkX());
            payloadOutput.writeInt(chunkKey.chunkZ());
            payloadOutput.writeInt(chunkHistoryBuffer.timelines().size());

            List<Map.Entry<ChunkLocalBlockKey, BlockHistoryTimeline>> timelines = new ArrayList<>(chunkHistoryBuffer.timelines().entrySet());
            timelines.sort((left, right) -> {
                ChunkLocalBlockKey leftKey = left.getKey();
                ChunkLocalBlockKey rightKey = right.getKey();

                int compareX = Integer.compare(leftKey.localX(), rightKey.localX());
                if (compareX != 0) {
                    return compareX;
                }

                int compareY = Integer.compare(leftKey.blockY(), rightKey.blockY());
                if (compareY != 0) {
                    return compareY;
                }

                return Integer.compare(leftKey.localZ(), rightKey.localZ());
            });

            for (Map.Entry<ChunkLocalBlockKey, BlockHistoryTimeline> timelineEntry : timelines) {
                ChunkLocalBlockKey blockKey = timelineEntry.getKey();
                BlockHistoryTimeline timeline = timelineEntry.getValue();
                payloadOutput.writeByte(blockKey.localX());
                payloadOutput.writeInt(blockKey.blockY());
                payloadOutput.writeByte(blockKey.localZ());
                payloadOutput.writeInt(timeline.entries().size());

                for (BlockHistoryEntry historyEntry : timeline.entries()) {
                    writeHistoryEntry(payloadOutput, historyEntry);
                    eventCount++;
                    minTimestamp = Math.min(minTimestamp, historyEntry.timestampEpochMillisUtc());
                    maxTimestamp = Math.max(maxTimestamp, historyEntry.timestampEpochMillisUtc());
                }
            }
        }

        payloadOutput.flush();
        if (eventCount == 0L) {
            minTimestamp = createdAtEpochMillisUtc;
            maxTimestamp = createdAtEpochMillisUtc;
        }

        return new SerializedBatch(
                worldUuid,
                regionX,
                regionZ,
                snapshotId,
                createdAtEpochMillisUtc,
                chunkEntries.size(),
                eventCount,
                minTimestamp,
                maxTimestamp,
                payloadBytes.toByteArray()
        );
    }

    private void writeSerializedBatch(DataOutputStream output, SerializedBatch serializedBatch) throws IOException {
        output.writeInt(REGION_BATCH_MAGIC);
        output.writeInt(serializedBatch.payload().length);
        output.writeLong(serializedBatch.snapshotId());
        output.writeLong(serializedBatch.createdAtEpochMillisUtc());
        output.writeLong(serializedBatch.eventCount());
        output.writeLong(serializedBatch.minTimestamp());
        output.writeLong(serializedBatch.maxTimestamp());
        output.writeInt(serializedBatch.chunkCount());
        output.write(serializedBatch.payload());
    }

    private void writeRegionFileHeader(DataOutputStream output, UUID worldUuid, int regionX, int regionZ) throws IOException {
        output.writeInt(REGION_FILE_MAGIC);
        output.writeInt(REGION_FILE_FORMAT_VERSION);
        StorageBinaryIO.writeUuid(output, worldUuid);
        output.writeInt(regionX);
        output.writeInt(regionZ);
    }

    private RegionFileHeader readRegionFileHeader(DataInputStream input, Path regionFile) throws IOException {
        int magic = input.readInt();
        int formatVersion = input.readInt();
        if (magic != REGION_FILE_MAGIC || formatVersion != REGION_FILE_FORMAT_VERSION) {
            throw new IOException("Unsupported region file format in " + regionFile + ".");
        }

        UUID worldUuid = StorageBinaryIO.readUuid(input);
        int regionX = input.readInt();
        int regionZ = input.readInt();
        return new RegionFileHeader(worldUuid, regionX, regionZ);
    }

    private RegionBatchHeader readRegionBatchHeader(DataInputStream input) throws IOException {
        int magic = input.readInt();
        if (magic != REGION_BATCH_MAGIC) {
            throw new IOException("Corrupted region batch header.");
        }

        int payloadLength = input.readInt();
        long snapshotId = input.readLong();
        long createdAtEpochMillisUtc = input.readLong();
        long eventCount = input.readLong();
        long minTimestamp = input.readLong();
        long maxTimestamp = input.readLong();
        int chunkCount = input.readInt();
        return new RegionBatchHeader(payloadLength, snapshotId, createdAtEpochMillisUtc, eventCount, minTimestamp, maxTimestamp, chunkCount);
    }

    private BlockHistoryEntry readHistoryEntry(DataInputStream input) throws IOException {
        long timestampEpochMillisUtc = input.readLong();
        UUID actorUuid = StorageBinaryIO.readUuid(input);
        BlockHistoryAction action = readAction(input.readUnsignedByte());
        String afterBlockDataString = StorageBinaryIO.readString(input);
        String removedBlockDataString = StorageBinaryIO.readNullableString(input);
        SpecialBlockSnapshot resultSnapshot = readSpecialSnapshot(input);
        SpecialBlockSnapshot removedSnapshot = readSpecialSnapshot(input);
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                action,
                afterBlockDataString,
                removedBlockDataString,
                resultSnapshot,
                removedSnapshot
        );
    }

    private void skipHistoryEntries(DataInputStream input, int entryCount) throws IOException {
        for (int index = 0; index < entryCount; index++) {
            skipHistoryEntry(input);
        }
    }

    private void skipHistoryEntry(DataInputStream input) throws IOException {
        input.readLong();
        StorageBinaryIO.readUuid(input);
        input.readUnsignedByte();
        StorageBinaryIO.readString(input);
        StorageBinaryIO.readNullableString(input);
        skipSpecialSnapshot(input);
        skipSpecialSnapshot(input);
    }

    private void writeHistoryEntry(DataOutputStream output, BlockHistoryEntry entry) throws IOException {
        output.writeLong(entry.timestampEpochMillisUtc());
        StorageBinaryIO.writeUuid(output, entry.actorUuid());
        output.writeByte(entry.action().id());
        StorageBinaryIO.writeString(output, entry.afterBlockDataString());
        StorageBinaryIO.writeNullableString(output, entry.removedBlockDataString());
        writeSpecialSnapshot(output, entry.resultSnapshot());
        writeSpecialSnapshot(output, entry.removedSnapshot());
    }

    private BlockHistoryAction readAction(int actionId) throws IOException {
        for (BlockHistoryAction action : BlockHistoryAction.values()) {
            if (action.id() == actionId) {
                return action;
            }
        }
        throw new IOException("Unsupported block history action id: " + actionId);
    }

    private void writeSpecialSnapshot(DataOutputStream output, SpecialBlockSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            output.writeByte(SpecialBlockSnapshot.NONE_TYPE);
            return;
        }

        output.writeByte(snapshot.typeId());
        snapshot.writeTo(output);
    }

    private SpecialBlockSnapshot readSpecialSnapshot(DataInputStream input) throws IOException {
        int typeId = input.readUnsignedByte();
        return switch (typeId) {
            case SpecialBlockSnapshot.NONE_TYPE -> null;
            case SpecialBlockSnapshot.SIGN_TYPE -> readSignSnapshot(input);
            case SpecialBlockSnapshot.CONTAINER_TYPE -> readContainerSnapshot(input);
            default -> throw new IOException("Unsupported special snapshot type id: " + typeId);
        };
    }

    private void skipSpecialSnapshot(DataInputStream input) throws IOException {
        int typeId = input.readUnsignedByte();
        switch (typeId) {
            case SpecialBlockSnapshot.NONE_TYPE -> {
            }
            case SpecialBlockSnapshot.SIGN_TYPE -> readSignSnapshot(input);
            case SpecialBlockSnapshot.CONTAINER_TYPE -> readContainerSnapshot(input);
            default -> throw new IOException("Unsupported special snapshot type id: " + typeId);
        }
    }

    private SignBlockSnapshot readSignSnapshot(DataInputStream input) throws IOException {
        boolean frontGlowingText = input.readBoolean();
        boolean backGlowingText = input.readBoolean();
        boolean waxed = input.readBoolean();
        String frontColorName = StorageBinaryIO.readString(input);
        String backColorName = StorageBinaryIO.readString(input);

        String[] frontLines = new String[4];
        String[] backLines = new String[4];
        for (int index = 0; index < frontLines.length; index++) {
            frontLines[index] = StorageBinaryIO.readString(input);
        }
        for (int index = 0; index < backLines.length; index++) {
            backLines[index] = StorageBinaryIO.readString(input);
        }

        return new SignBlockSnapshot(frontLines, backLines, frontColorName, backColorName, frontGlowingText, backGlowingText, waxed);
    }

    private ContainerBlockSnapshot readContainerSnapshot(DataInputStream input) throws IOException {
        boolean doubleChest = input.readBoolean();
        int partnerOffsetX = input.readInt();
        int partnerOffsetY = input.readInt();
        int partnerOffsetZ = input.readInt();
        int inventorySize = input.readInt();
        int slotCount = input.readInt();

        List<ContainerSlotSnapshot> slots = new ArrayList<>(slotCount);
        for (int index = 0; index < slotCount; index++) {
            int slotIndex = input.readInt();
            byte[] serializedItemBytes = StorageBinaryIO.readByteArray(input);
            slots.add(new ContainerSlotSnapshot(slotIndex, serializedItemBytes));
        }

        return new ContainerBlockSnapshot(doubleChest, partnerOffsetX, partnerOffsetY, partnerOffsetZ, inventorySize, slots);
    }

    private long calculateChunkStorageBytes() throws IOException {
        if (!Files.exists(chunksDirectory)) {
            return 0L;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(chunksDirectory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .sum();
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private long calculateRetentionCutoff() {
        return System.currentTimeMillis() - Duration.ofDays(getMaxRecordAgeDays()).toMillis();
    }

    private long getMaxTotalSizeBytes() {
        synchronized (bufferLock) {
            return maxTotalSizeMegabytes * 1024L * 1024L;
        }
    }

    private String sanitizeWorldDisplayName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "unknown-world";
        }
        return worldName;
    }

    private String sanitizeWorldDirectoryName(String worldName) {
        String sanitized = sanitizeWorldDisplayName(worldName)
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .trim();

        return sanitized.isEmpty() ? "world" : sanitized;
    }

    public record CleanupReport(
            boolean cleanupPerformed,
            long beforeEventCount,
            long beforeDiskBytes,
            long afterEventCount,
            long afterDiskBytes
    ) {
    }

    private record ChunkLimitsConfiguration(int maxTotalSizeMegabytes, int maxRecordAgeDays) {
    }

    private record BatchWriteResult(long eventCount) {
    }

    private record RegionBatch(UUID worldUuid, String directoryName, int regionX, int regionZ, Map<WorldChunkKey, ChunkHistoryBuffer> chunks) {
    }

    private record RegionBatchKey(UUID worldUuid, String directoryName, int regionX, int regionZ) {
    }

    private record RegionBatchBuilder(RegionBatchKey key, Map<WorldChunkKey, ChunkHistoryBuffer> chunks) {

        private RegionBatchBuilder(RegionBatchKey key) {
            this(key, new HashMap<>());
        }

        private RegionBatch build() {
            return new RegionBatch(key.worldUuid(), key.directoryName(), key.regionX(), key.regionZ(), Map.copyOf(chunks));
        }
    }

    private record SerializedBatch(
            UUID worldUuid,
            int regionX,
            int regionZ,
            long snapshotId,
            long createdAtEpochMillisUtc,
            int chunkCount,
            long eventCount,
            long minTimestamp,
            long maxTimestamp,
            byte[] payload
    ) {
    }

    private record RegionFileHeader(UUID worldUuid, int regionX, int regionZ) {
    }

    private record RegionBatchHeader(
            int payloadLength,
            long snapshotId,
            long createdAtEpochMillisUtc,
            long eventCount,
            long minTimestamp,
            long maxTimestamp,
            int chunkCount
    ) {
        private long totalLength() {
            return Integer.BYTES + Integer.BYTES + (5L * Long.BYTES) + Integer.BYTES + payloadLength;
        }
    }

    private record BatchIdentity(Path path, long batchOffset) {
    }

    private record RegionBatchReference(
            Path path,
            UUID worldUuid,
            int regionX,
            int regionZ,
            long batchOffset,
            int payloadLength,
            long snapshotId,
            long createdAtEpochMillisUtc,
            long eventCount,
            long minTimestamp,
            long maxTimestamp
    ) {
        private long totalLength() {
            return Integer.BYTES + Integer.BYTES + (5L * Long.BYTES) + Integer.BYTES + payloadLength;
        }

        private BatchIdentity identity() {
            return new BatchIdentity(path, batchOffset);
        }
    }

    private record ChunkInventory(List<RegionBatchReference> batchReferences, long totalEventCount, long diskBytes, boolean containsExpiredRecords) {
    }

    private record RewriteDecision(boolean dropCurrentBatch, long retentionCutoff) {

        private static RewriteDecision dropBatch() {
            return new RewriteDecision(true, Long.MIN_VALUE);
        }

        private static RewriteDecision keepWholeBatch() {
            return new RewriteDecision(false, Long.MIN_VALUE);
        }

        private static RewriteDecision filterByCutoff(long retentionCutoff) {
            return new RewriteDecision(false, retentionCutoff);
        }
    }

    @FunctionalInterface
    private interface RewritePlanner {
        RewriteDecision plan(RegionBatchReference reference);
    }

    private static final class RegionRewriteAccumulator {

        private final UUID worldUuid;
        private final int regionX;
        private final int regionZ;
        private final Map<WorldChunkKey, ChunkHistoryBuffer> chunks = new HashMap<>();

        private RegionRewriteAccumulator(UUID worldUuid, int regionX, int regionZ) {
            this.worldUuid = worldUuid;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        private UUID worldUuid() {
            return worldUuid;
        }

        private int regionX() {
            return regionX;
        }

        private int regionZ() {
            return regionZ;
        }

        private void append(WorldChunkKey chunkKey, ChunkLocalBlockKey blockKey, BlockHistoryEntry entry) {
            chunks.computeIfAbsent(chunkKey, ignored -> new ChunkHistoryBuffer()).append(blockKey, entry);
        }

        private boolean isEmpty() {
            return chunks.isEmpty();
        }

        private Map<WorldChunkKey, ChunkHistoryBuffer> chunks() {
            return Map.copyOf(chunks);
        }
    }
}
