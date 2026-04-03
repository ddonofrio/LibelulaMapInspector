package com.libelulamapinspector.index;

import com.google.common.hash.BloomFilter;
import com.libelulamapinspector.support.PluginTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomIndexServiceAdditionalTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToDefaultValuesWhenTheConfiguredIndexShapeIsInvalid() throws IOException {
        BloomIndexService service = new BloomIndexService(plugin(tempDir.resolve("plugin-data"), configuration -> {
            configuration.set("index.megabytes", 0);
            configuration.set("index.false-positive-rate", 0.0D);
            configuration.set("index.persist-interval-minutes", 0);
        }));

        service.initialize(false);

        assertEquals(100, service.getActiveMegabytes());
        assertEquals("1%", service.getFormattedFalsePositiveRate());
        assertEquals(87_517_547L, service.getExpectedInsertions());
        assertEquals(60, service.getPersistIntervalMinutes());
    }

    @Test
    void persistsExactlyTheSnapshotThatWasCapturedEvenIfTheLiveIndexChangesLater() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockPositionKey firstKey = new BlockPositionKey(UUID.randomUUID(), 10, 64, 20);
        BlockPositionKey secondKey = new BlockPositionKey(UUID.randomUUID(), 30, 70, 40);

        BloomIndexService service = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        service.initialize(false);
        service.put(firstKey);
        BloomIndexService.PersistedBloomSnapshot snapshot = service.createPersistedSnapshot();
        byte[] snapshotBytes = serialize(snapshot.bloomFilter());

        service.put(secondKey);
        service.persistSnapshot(snapshot);

        BloomIndexService reloaded = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        reloaded.initialize(false);

        assertArrayEquals(snapshotBytes, serialize(reloaded.createPersistedSnapshot().bloomFilter()));
    }

    @Test
    void deleteStorageFilesRemovesPersistedIndexArtifacts() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BloomIndexService service = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        service.initialize(false);

        service.deleteStorageFiles();

        assertTrue(Files.notExists(dataFolder.resolve("index").resolve("bloom-filter.bin")));
        assertTrue(Files.notExists(dataFolder.resolve("index").resolve("bloom-filter.meta.bin")));
        assertTrue(Files.notExists(dataFolder.resolve("index")));
    }

    @Test
    void failsWhenTheMetadataFileIsCorruptedAndChunkHistoryAlreadyExists() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BloomIndexService firstService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        firstService.initialize(false);
        Files.writeString(dataFolder.resolve("index").resolve("bloom-filter.meta.bin"), "corrupted");

        BloomIndexService secondService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        IOException exception = assertThrows(IOException.class, () -> secondService.initialize(true));

        assertTrue(exception.getMessage().contains("metadata file is missing"));
    }

    private JavaPlugin plugin(Path dataFolder, java.util.function.Consumer<YamlConfiguration> configurer) throws IOException {
        return PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configurer);
    }

    private byte[] serialize(BloomFilter<BlockPositionKey> bloomFilter) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bloomFilter.writeTo(output);
        return output.toByteArray();
    }
}
