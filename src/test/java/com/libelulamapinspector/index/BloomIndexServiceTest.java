package com.libelulamapinspector.index;

import com.libelulamapinspector.support.PluginTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsAndPersistsIndexOnFirstStartup() throws IOException {
        BloomIndexService service = new BloomIndexService(plugin(tempDir.resolve("plugin-data"), configuration -> {
        }));

        service.initialize(false);

        assertTrue(Files.exists(tempDir.resolve("plugin-data").resolve("index").resolve("bloom-filter.bin")));
        assertTrue(Files.exists(tempDir.resolve("plugin-data").resolve("index").resolve("bloom-filter.meta.bin")));
    }

    @Test
    void loadsPersistedEntriesOnNextStartup() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 10, 64, 20);

        BloomIndexService firstService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        firstService.initialize(false);
        firstService.put(positionKey);
        firstService.persistSnapshot(firstService.createPersistedSnapshot());

        BloomIndexService secondService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        secondService.initialize(false);

        assertTrue(secondService.mightContain(positionKey));
    }

    @Test
    void keepsStoredShapeWhenUserConfigurationChanges() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");

        BloomIndexService firstService = new BloomIndexService(plugin(dataFolder, configuration -> {
            configuration.set("index.megabytes", 100);
            configuration.set("index.false-positive-rate", 0.01D);
        }));
        firstService.initialize(false);

        BloomIndexService secondService = new BloomIndexService(plugin(dataFolder, configuration -> {
            configuration.set("index.megabytes", 250);
            configuration.set("index.false-positive-rate", 0.001D);
        }));
        secondService.initialize(false);

        assertEquals(100, secondService.getActiveMegabytes());
        assertEquals("1%", secondService.getFormattedFalsePositiveRate());
        assertEquals(87_517_547L, secondService.getExpectedInsertions());
    }

    @Test
    void failsWhenChunkHistoryExistsButIndexMetadataIsMissing() throws IOException {
        BloomIndexService service = new BloomIndexService(plugin(tempDir.resolve("plugin-data"), configuration -> {
        }));

        IOException exception = assertThrows(IOException.class, () -> service.initialize(true));

        assertTrue(exception.getMessage().contains("metadata file is missing"));
    }

    @Test
    void failsWhenChunkHistoryExistsButBloomFileIsMissing() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BloomIndexService firstService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        firstService.initialize(false);
        Files.delete(dataFolder.resolve("index").resolve("bloom-filter.bin"));

        BloomIndexService secondService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        IOException exception = assertThrows(IOException.class, () -> secondService.initialize(true));

        assertTrue(exception.getMessage().contains("index file is missing"));
    }

    @Test
    void failsWhenChunkHistoryExistsButBloomFileIsCorrupted() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        BloomIndexService firstService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        firstService.initialize(false);
        Files.writeString(dataFolder.resolve("index").resolve("bloom-filter.bin"), "corrupted");

        BloomIndexService secondService = new BloomIndexService(plugin(dataFolder, configuration -> {
        }));
        IOException exception = assertThrows(IOException.class, () -> secondService.initialize(true));

        assertTrue(exception.getMessage().contains("could not be loaded"));
    }

    private JavaPlugin plugin(Path dataFolder, java.util.function.Consumer<YamlConfiguration> configurer) throws IOException {
        return PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configurer);
    }
}
