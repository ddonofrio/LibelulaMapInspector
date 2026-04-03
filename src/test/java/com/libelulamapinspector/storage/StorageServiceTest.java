package com.libelulamapinspector.storage;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.support.PluginTestSupport;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.libelulamapinspector.storage.StorageTestSupport.createBlockStoreService;
import static com.libelulamapinspector.storage.StorageTestSupport.entry;
import static com.libelulamapinspector.storage.StorageTestSupport.persistAll;
import static com.libelulamapinspector.storage.StorageTestSupport.readAllEntries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shutdownPersistsPendingIndexAndChunkData() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        long recentTimestamp = System.currentTimeMillis();
        JavaPlugin plugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(plugin);
        StorageService service = new StorageService(plugin);
        BlockPositionKey positionKey = new BlockPositionKey(UUID.randomUUID(), 1, 64, 1);

        service.initialize();
        service.trackBlockMutation("world", positionKey, entry(UUID.randomUUID(), recentTimestamp, "minecraft:stone", null));
        service.shutdown();

        assertTrue(Files.exists(dataFolder.resolve("index").resolve("bloom-filter.bin")));
        assertEquals(1, readAllEntries(dataFolder).size());

        JavaPlugin reloadedPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(reloadedPlugin);
        StorageService reloadedService = new StorageService(reloadedPlugin);
        reloadedService.initialize();

        assertEquals(1L, reloadedService.getRecordedEventCount());
        assertTrue(reloadedService.mightContainHistory(positionKey));
        reloadedService.shutdown();
    }

    @Test
    void clearStorageAndReinitializeDeletesOldHistoryAndRecreatesFreshStorage() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        long recentTimestamp = System.currentTimeMillis();
        JavaPlugin plugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(plugin);
        StorageService service = new StorageService(plugin);

        service.initialize();
        service.trackBlockMutation("world", new BlockPositionKey(UUID.randomUUID(), 1, 64, 1), entry(UUID.randomUUID(), recentTimestamp, "minecraft:stone", null));
        service.shutdown();

        JavaPlugin freshPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(freshPlugin);
        StorageService freshService = new StorageService(freshPlugin);
        freshService.initialize();
        freshService.clearStorageAndReinitialize();

        assertTrue(Files.exists(dataFolder.resolve("index").resolve("bloom-filter.bin")));
        assertTrue(Files.exists(dataFolder.resolve("chunks").resolve("store.meta.bin")));
        assertEquals(List.of(), StorageTestSupport.regionFiles(dataFolder));
        assertEquals(0L, freshService.getRecordedEventCount());
        freshService.shutdown();
    }

    @Test
    void initializeFailsWhenChunkHistoryExistsButTheIndexIsMissing() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        long recentTimestamp = System.currentTimeMillis();
        BlockStoreService seedStore = createBlockStoreService(dataFolder, configuration -> {
        });
        seedStore.append("world", new BlockPositionKey(UUID.randomUUID(), 1, 64, 1), entry(UUID.randomUUID(), recentTimestamp, "minecraft:stone", null));
        persistAll(seedStore, 100L);

        JavaPlugin plugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        StorageService service = new StorageService(plugin);

        assertThrows(IOException.class, service::initialize);
    }

    @Test
    void getBlockHistoryReturnsPersistedAndBufferedEntriesForTheSameLocation() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        long baseTimestamp = System.currentTimeMillis();
        UUID worldUuid = UUID.randomUUID();
        UUID persistedActor = UUID.randomUUID();
        UUID bufferedActor = UUID.randomUUID();
        BlockPositionKey positionKey = new BlockPositionKey(worldUuid, 1, 64, 1);

        JavaPlugin firstPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(firstPlugin);
        StorageService firstService = new StorageService(firstPlugin);
        firstService.initialize();
        firstService.trackBlockMutation("world", positionKey, entry(persistedActor, baseTimestamp, "minecraft:stone", null));
        firstService.shutdown();
        assertEquals(1, readAllEntries(dataFolder).size());

        JavaPlugin secondPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(secondPlugin);
        StorageService secondService = new StorageService(secondPlugin);
        secondService.initialize();
        secondService.trackBlockMutation("world", positionKey, entry(bufferedActor, baseTimestamp + 1L, "minecraft:dirt", null));

        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        List<BlockHistoryEntry> history = secondService.getBlockHistory(new Location(world, 1.7D, 64.2D, 1.9D));

        assertEquals(2, history.size());
        assertEquals(List.of(persistedActor, bufferedActor), history.stream().map(BlockHistoryEntry::actorUuid).toList());
        assertEquals(List.of("minecraft:stone", "minecraft:dirt"), history.stream().map(BlockHistoryEntry::afterBlockDataString).toList());
        secondService.shutdown();
    }

    @Test
    void getActorNamesInBoxMergesPersistedAndBufferedActorsAcrossChunksAndRegions() throws IOException {
        Path dataFolder = tempDir.resolve("plugin-data");
        long baseTimestamp = System.currentTimeMillis();
        UUID worldUuid = UUID.randomUUID();
        UUID persistedActor = UUID.randomUUID();
        UUID bufferedActor = UUID.randomUUID();
        UUID secondPersistedActor = UUID.randomUUID();

        JavaPlugin firstPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(firstPlugin);
        StorageService firstService = new StorageService(firstPlugin);
        firstService.initialize();
        firstService.trackBlockMutation("world", new BlockPositionKey(worldUuid, 1, 64, 1), entry(persistedActor, baseTimestamp, "minecraft:stone", null));
        firstService.trackBlockMutation("world", new BlockPositionKey(worldUuid, 600, 64, 600), entry(secondPersistedActor, baseTimestamp + 1L, "minecraft:glass", null));
        firstService.shutdown();
        assertEquals(2, readAllEntries(dataFolder).size());

        JavaPlugin secondPlugin = PluginTestSupport.mockPlugin(JavaPlugin.class, dataFolder, configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(secondPlugin);
        Server server = secondPlugin.getServer();
        OfflinePlayer persistedOfflinePlayer = offlinePlayer("Zelda");
        OfflinePlayer bufferedOfflinePlayer = offlinePlayer("Alex");
        OfflinePlayer secondPersistedOfflinePlayer = offlinePlayer("Morgan");
        when(server.getOfflinePlayer(persistedActor)).thenReturn(persistedOfflinePlayer);
        when(server.getOfflinePlayer(bufferedActor)).thenReturn(bufferedOfflinePlayer);
        when(server.getOfflinePlayer(secondPersistedActor)).thenReturn(secondPersistedOfflinePlayer);

        StorageService secondService = new StorageService(secondPlugin);
        secondService.initialize();
        secondService.trackBlockMutation("world", new BlockPositionKey(worldUuid, 32, 70, 32), entry(bufferedActor, baseTimestamp + 2L, "minecraft:dirt", null));

        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUuid);
        List<String> names = secondService.getActorNamesInBox(world, new BoundingBox(0, 0, 0, 620, 100, 620));

        assertIterableEquals(List.of("Alex", "Morgan", "Zelda"), names);
        secondService.shutdown();
    }

    private OfflinePlayer offlinePlayer(String name) {
        OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);
        when(offlinePlayer.getName()).thenReturn(name);
        return offlinePlayer;
    }
}
