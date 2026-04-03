package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.support.PluginTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class StorageTestSupport {

    private StorageTestSupport() {
    }

    static JavaPlugin plugin(Path dataFolder, Consumer<YamlConfiguration> configurer) throws IOException {
        return PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configurer);
    }

    static BlockStoreService createBlockStoreService(Path dataFolder, Consumer<YamlConfiguration> configurer) throws IOException {
        JavaPlugin plugin = plugin(dataFolder, configurer);
        BlockStoreService service = new BlockStoreService(plugin);
        service.initialize();
        return service;
    }

    static BlockHistoryEntry entry(UUID actorUuid, long timestamp, String afterState, String removedState) {
        return entry(actorUuid, timestamp, afterState, removedState, null, null);
    }

    static BlockHistoryEntry entry(
            UUID actorUuid,
            long timestamp,
            String afterState,
            String removedState,
            SpecialBlockSnapshot resultSnapshot,
            SpecialBlockSnapshot removedSnapshot
    ) {
        return new BlockHistoryEntry(
                actorUuid,
                timestamp,
                BlockHistoryAction.PLACE_OR_REPLACE,
                afterState,
                removedState,
                resultSnapshot,
                removedSnapshot
        );
    }

    static void persistAll(BlockStoreService service, long createdAt) throws IOException {
        List<BlockStoreFlushSnapshot> snapshots = service.prepareSnapshotsForPersistence(createdAt);
        for (BlockStoreFlushSnapshot snapshot : snapshots) {
            service.persistSnapshot(snapshot);
            service.markSnapshotPersisted(snapshot.snapshotId());
        }
    }

    static long readStoreEventCount(Path dataFolder) throws IOException {
        Path metadataFile = dataFolder.resolve("chunks").resolve("store.meta.bin");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(metadataFile)))) {
            input.readInt();
            input.readInt();
            return input.readLong();
        }
    }

    static List<Path> regionFiles(Path dataFolder) throws IOException {
        Path chunksDirectory = dataFolder.resolve("chunks");
        if (!Files.exists(chunksDirectory)) {
            return List.of();
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(chunksDirectory)) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".lmi"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    static List<DecodedEntry> readAllEntries(Path dataFolder) throws IOException {
        List<DecodedEntry> entries = new ArrayList<>();
        for (Path regionFile : regionFiles(dataFolder)) {
            entries.addAll(readRegionEntries(regionFile));
        }
        entries.sort(Comparator
                .comparing(DecodedEntry::timestampEpochMillisUtc)
                .thenComparing(entry -> entry.actorUuid().toString()));
        return entries;
    }

    private static List<DecodedEntry> readRegionEntries(Path regionFile) throws IOException {
        List<DecodedEntry> entries = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(regionFile)))) {
            input.readInt();
            input.readInt();
            UUID worldUuid = StorageBinaryIO.readUuid(input);
            input.readInt();
            input.readInt();

            while (input.available() > 0) {
                input.readInt();
                int payloadLength = input.readInt();
                input.readLong();
                input.readLong();
                input.readLong();
                input.readLong();
                input.readLong();
                int chunkCount = input.readInt();
                byte[] payload = input.readNBytes(payloadLength);

                try (DataInputStream payloadInput = new DataInputStream(new ByteArrayInputStream(payload))) {
                    for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                        int chunkX = payloadInput.readInt();
                        int chunkZ = payloadInput.readInt();
                        int timelineCount = payloadInput.readInt();

                        for (int timelineIndex = 0; timelineIndex < timelineCount; timelineIndex++) {
                            int localX = payloadInput.readUnsignedByte();
                            int blockY = payloadInput.readInt();
                            int localZ = payloadInput.readUnsignedByte();
                            int entryCount = payloadInput.readInt();

                            for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                                entries.add(readDecodedEntry(payloadInput, worldUuid, chunkX, chunkZ, localX, blockY, localZ));
                            }
                        }
                    }
                }
            }
        }
        return entries;
    }

    private static DecodedEntry readDecodedEntry(
            DataInputStream input,
            UUID worldUuid,
            int chunkX,
            int chunkZ,
            int localX,
            int blockY,
            int localZ
    ) throws IOException {
        long timestampEpochMillisUtc = input.readLong();
        UUID actorUuid = StorageBinaryIO.readUuid(input);
        BlockHistoryAction action = readAction(input.readUnsignedByte());
        String afterBlockDataString = StorageBinaryIO.readString(input);
        String removedBlockDataString = StorageBinaryIO.readNullableString(input);
        DecodedSpecialSnapshot resultSnapshot = readDecodedSpecialSnapshot(input);
        DecodedSpecialSnapshot removedSnapshot = readDecodedSpecialSnapshot(input);
        return new DecodedEntry(
                worldUuid,
                chunkX,
                chunkZ,
                localX,
                blockY,
                localZ,
                timestampEpochMillisUtc,
                actorUuid,
                action,
                afterBlockDataString,
                removedBlockDataString,
                resultSnapshot,
                removedSnapshot
        );
    }

    private static DecodedSpecialSnapshot readDecodedSpecialSnapshot(DataInputStream input) throws IOException {
        int typeId = input.readUnsignedByte();
        return switch (typeId) {
            case SpecialBlockSnapshot.NONE_TYPE -> null;
            case SpecialBlockSnapshot.SIGN_TYPE -> readDecodedSignSnapshot(input);
            case SpecialBlockSnapshot.CONTAINER_TYPE -> readDecodedContainerSnapshot(input);
            default -> throw new IOException("Unsupported special snapshot type id: " + typeId);
        };
    }

    private static DecodedSignSnapshot readDecodedSignSnapshot(DataInputStream input) throws IOException {
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

        return new DecodedSignSnapshot(frontLines, backLines, frontColorName, backColorName, frontGlowingText, backGlowingText, waxed);
    }

    private static DecodedContainerSnapshot readDecodedContainerSnapshot(DataInputStream input) throws IOException {
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

        return new DecodedContainerSnapshot(doubleChest, partnerOffsetX, partnerOffsetY, partnerOffsetZ, inventorySize, List.copyOf(slots));
    }

    private static BlockHistoryAction readAction(int actionId) throws IOException {
        for (BlockHistoryAction action : BlockHistoryAction.values()) {
            if (action.id() == actionId) {
                return action;
            }
        }

        throw new IOException("Unsupported action id: " + actionId);
    }

    record DecodedEntry(
            UUID worldUuid,
            int chunkX,
            int chunkZ,
            int localX,
            int blockY,
            int localZ,
            long timestampEpochMillisUtc,
            UUID actorUuid,
            BlockHistoryAction action,
            String afterBlockDataString,
            String removedBlockDataString,
            DecodedSpecialSnapshot resultSnapshot,
            DecodedSpecialSnapshot removedSnapshot
    ) {
        int absoluteX() {
            return (chunkX << 4) + localX;
        }

        int absoluteZ() {
            return (chunkZ << 4) + localZ;
        }

        BlockPositionKey positionKey() {
            return new BlockPositionKey(worldUuid, absoluteX(), blockY, absoluteZ());
        }
    }

    sealed interface DecodedSpecialSnapshot permits DecodedSignSnapshot, DecodedContainerSnapshot {
    }

    record DecodedSignSnapshot(
            String[] frontLines,
            String[] backLines,
            String frontColorName,
            String backColorName,
            boolean frontGlowingText,
            boolean backGlowingText,
            boolean waxed
    ) implements DecodedSpecialSnapshot {
    }

    record DecodedContainerSnapshot(
            boolean doubleChest,
            int partnerOffsetX,
            int partnerOffsetY,
            int partnerOffsetZ,
            int inventorySize,
            List<ContainerSlotSnapshot> slots
    ) implements DecodedSpecialSnapshot {
    }
}
