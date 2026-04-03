package com.libelulamapinspector.index;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.util.logging.Level;

/**
 * Manages the Bloom filter used as the persisted index for block history lookups.
 */
public final class BloomIndexService {

    private static final int METADATA_FORMAT_VERSION = 1;
    private static final int DEFAULT_MEGABYTES = 100;
    private static final double DEFAULT_FALSE_POSITIVE_RATE = 0.01D;
    private static final int DEFAULT_PERSIST_INTERVAL_MINUTES = 60;
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.############%");
    private static final Funnel<BlockPositionKey> BLOCK_POSITION_FUNNEL = BlockPositionFunnel.INSTANCE;

    private final JavaPlugin plugin;
    private final Path indexDirectory;
    private final Path bloomFile;
    private final Path metadataFile;
    private final Object persistenceLock = new Object();

    private volatile BloomFilter<BlockPositionKey> bloomFilter;
    private volatile IndexShapeConfiguration activeShapeConfiguration;
    private volatile int persistIntervalMinutes;
    public BloomIndexService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.indexDirectory = plugin.getDataFolder().toPath().resolve("index");
        this.bloomFile = indexDirectory.resolve("bloom-filter.bin");
        this.metadataFile = indexDirectory.resolve("bloom-filter.meta.bin");
    }

    public void initialize(boolean persistedHistoryExists) throws IOException {
        Files.createDirectories(indexDirectory);

        UserIndexConfiguration userConfiguration = loadUserConfiguration(plugin.getConfig());
        persistIntervalMinutes = userConfiguration.persistIntervalMinutes();

        IndexShapeConfiguration storedShape = readStoredShape();
        activeShapeConfiguration = selectActiveShape(userConfiguration.shapeConfiguration(), storedShape);
        bloomFilter = loadOrCreateBloomFilter(activeShapeConfiguration, storedShape != null, persistedHistoryExists);

        if (!persistedHistoryExists && (storedShape == null || !Files.exists(bloomFile))) {
            persistSnapshot(createPersistedSnapshot());
        }

        plugin.getLogger().info("Index ready: "
                + activeShapeConfiguration.megabytes() + " MiB, "
                + formatFalsePositiveRate(activeShapeConfiguration.falsePositiveRate()) + " false positive disk access rate, "
                + activeShapeConfiguration.expectedInsertions() + " expected positions.");
    }

    public boolean put(BlockPositionKey key) {
        return bloomFilter.put(key);
    }

    public boolean mightContain(BlockPositionKey key) {
        return bloomFilter.mightContain(key);
    }

    public PersistedBloomSnapshot createPersistedSnapshot() {
        synchronized (persistenceLock) {
            return new PersistedBloomSnapshot(bloomFilter.copy(), activeShapeConfiguration);
        }
    }

    public void persistSnapshot(PersistedBloomSnapshot snapshot) throws IOException {
        synchronized (persistenceLock) {
            writeSnapshotLocked(snapshot.bloomFilter(), snapshot.shapeConfiguration());
        }
    }

    public void deleteStorageFiles() throws IOException {
        synchronized (persistenceLock) {
            Files.deleteIfExists(bloomFile);
            Files.deleteIfExists(metadataFile);
            deleteDirectoryIfEmpty(indexDirectory);
        }
    }

    public int getActiveMegabytes() {
        return activeShapeConfiguration.megabytes();
    }

    public String getFormattedFalsePositiveRate() {
        return formatFalsePositiveRate(activeShapeConfiguration.falsePositiveRate());
    }

    public long getExpectedInsertions() {
        return activeShapeConfiguration.expectedInsertions();
    }

    public int getPersistIntervalMinutes() {
        return persistIntervalMinutes;
    }

    private UserIndexConfiguration loadUserConfiguration(FileConfiguration configuration) {
        int configuredMegabytes = configuration.getInt("index.megabytes", DEFAULT_MEGABYTES);
        if (configuredMegabytes < 1) {
            plugin.getLogger().warning("index.megabytes must be greater than zero. Falling back to " + DEFAULT_MEGABYTES + ".");
            configuredMegabytes = DEFAULT_MEGABYTES;
        }

        double configuredFalsePositiveRate = configuration.getDouble("index.false-positive-rate", DEFAULT_FALSE_POSITIVE_RATE);
        if (configuredFalsePositiveRate <= 0.0D || configuredFalsePositiveRate >= 1.0D) {
            plugin.getLogger().warning("index.false-positive-rate must be greater than 0 and lower than 1. Falling back to " + DEFAULT_FALSE_POSITIVE_RATE + ".");
            configuredFalsePositiveRate = DEFAULT_FALSE_POSITIVE_RATE;
        }

        int configuredPersistInterval = configuration.getInt("index.persist-interval-minutes", DEFAULT_PERSIST_INTERVAL_MINUTES);
        if (configuredPersistInterval < 1) {
            plugin.getLogger().warning("index.persist-interval-minutes must be greater than zero. Falling back to " + DEFAULT_PERSIST_INTERVAL_MINUTES + ".");
            configuredPersistInterval = DEFAULT_PERSIST_INTERVAL_MINUTES;
        }

        return new UserIndexConfiguration(
                new IndexShapeConfiguration(configuredMegabytes, configuredFalsePositiveRate),
                configuredPersistInterval
        );
    }

    private IndexShapeConfiguration readStoredShape() throws IOException {
        if (!Files.exists(metadataFile)) {
            return null;
        }

        try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(Files.newInputStream(metadataFile)))) {
            int formatVersion = inputStream.readInt();
            if (formatVersion != METADATA_FORMAT_VERSION) {
                plugin.getLogger().severe("The persisted index metadata format is not supported.");
                return null;
            }

            int megabytes = inputStream.readInt();
            double falsePositiveRate = inputStream.readDouble();
            return new IndexShapeConfiguration(megabytes, falsePositiveRate);
        }
    }

    private IndexShapeConfiguration selectActiveShape(IndexShapeConfiguration userShape, IndexShapeConfiguration storedShape) {
        if (storedShape == null) {
            return userShape;
        }

        if (storedShape.matches(userShape)) {
            return storedShape;
        }

        plugin.getLogger().severe("The index configuration has changed, but the database was already created. "
                + "The plugin will keep using the stored index configuration. "
                + "If you want to change it, run /lmi clear-db. This will permanently delete all stored history.");
        return storedShape;
    }

    private BloomFilter<BlockPositionKey> loadOrCreateBloomFilter(
            IndexShapeConfiguration shapeConfiguration,
            boolean shouldLoadExistingBloom,
            boolean persistedHistoryExists
    ) throws IOException {
        if (!shouldLoadExistingBloom) {
            if (persistedHistoryExists) {
                throw new IOException("Chunk history already exists, but the persisted index metadata file is missing. "
                        + "The index can only be created on the first startup or after /lmi clear-db.");
            }

            plugin.getLogger().info("No index metadata file was found. Creating a new index with the configured settings.");
            return createBloomFilter(shapeConfiguration);
        }

        if (!Files.exists(bloomFile)) {
            if (persistedHistoryExists) {
                throw new IOException("Chunk history already exists, but the persisted index file is missing. "
                        + "The index can only be created on the first startup or after /lmi clear-db.");
            }

            plugin.getLogger().info("No index file was found. Creating a new one with the active configuration.");
            return createBloomFilter(shapeConfiguration);
        }

        try (InputStream inputStream = new BufferedInputStream(Files.newInputStream(bloomFile))) {
            return BloomFilter.readFrom(inputStream, BLOCK_POSITION_FUNNEL);
        } catch (IOException exception) {
            if (persistedHistoryExists) {
                throw new IOException("The persisted index could not be loaded while chunk history already exists. "
                        + "If the storage is no longer consistent, clear it with /lmi clear-db.", exception);
            }

            plugin.getLogger().log(Level.SEVERE, "The index could not be loaded. A new empty index will be created.", exception);
            return createBloomFilter(shapeConfiguration);
        }
    }

    private BloomFilter<BlockPositionKey> createBloomFilter(IndexShapeConfiguration shapeConfiguration) {
        return BloomFilter.create(
                BLOCK_POSITION_FUNNEL,
                shapeConfiguration.expectedInsertions(),
                shapeConfiguration.falsePositiveRate()
        );
    }

    private void writeSnapshotLocked(BloomFilter<BlockPositionKey> snapshot, IndexShapeConfiguration shapeConfiguration) throws IOException {
        Files.createDirectories(indexDirectory);

        Path bloomTemporaryFile = bloomFile.resolveSibling(bloomFile.getFileName() + ".tmp");
        Path metadataTemporaryFile = metadataFile.resolveSibling(metadataFile.getFileName() + ".tmp");

        try (OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(
                bloomTemporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {
            snapshot.writeTo(outputStream);
        }

        try (DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                metadataTemporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)))) {
            outputStream.writeInt(METADATA_FORMAT_VERSION);
            outputStream.writeInt(shapeConfiguration.megabytes());
            outputStream.writeDouble(shapeConfiguration.falsePositiveRate());
        }

        moveTemporaryFile(bloomTemporaryFile, bloomFile);
        moveTemporaryFile(metadataTemporaryFile, metadataFile);
    }

    private void moveTemporaryFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteDirectoryIfEmpty(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            if (paths.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    private String formatFalsePositiveRate(double falsePositiveRate) {
        synchronized (PERCENT_FORMAT) {
            return PERCENT_FORMAT.format(falsePositiveRate);
        }
    }

    public record PersistedBloomSnapshot(BloomFilter<BlockPositionKey> bloomFilter, IndexShapeConfiguration shapeConfiguration) {
    }

    private record UserIndexConfiguration(IndexShapeConfiguration shapeConfiguration, int persistIntervalMinutes) {
    }
    private enum BlockPositionFunnel implements Funnel<BlockPositionKey> {
        INSTANCE;

        @Override
        public void funnel(BlockPositionKey from, PrimitiveSink into) {
            into.putLong(from.worldUuid().getMostSignificantBits());
            into.putLong(from.worldUuid().getLeastSignificantBits());
            into.putInt(from.x());
            into.putInt(from.y());
            into.putInt(from.z());
        }
    }
}
