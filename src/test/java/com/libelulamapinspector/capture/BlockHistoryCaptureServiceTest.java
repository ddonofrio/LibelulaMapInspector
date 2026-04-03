package com.libelulamapinspector.capture;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.ContainerBlockSnapshot;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import com.libelulamapinspector.storage.StorageService;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BlockHistoryCaptureServiceTest {

    @Test
    void recordsBlockBreaksAsRemoveEntries() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 500L);
        UUID playerUuid = UUID.randomUUID();
        Player player = CaptureTestSupport.player(playerUuid);
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block block = CaptureTestSupport.block(world, 1, 64, 1, Material.CHEST);
        Inventory inventory = CaptureTestSupport.inventory(null, new ItemStack(Material.DIAMOND, 2));
        BlockState removedState = CaptureTestSupport.chestState(block, inventory);

        captureService.recordBlockBreak(player, removedState);

        ArgumentCaptor<String> worldCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BlockPositionKey> keyCaptor = ArgumentCaptor.forClass(BlockPositionKey.class);
        ArgumentCaptor<BlockHistoryEntry> entryCaptor = ArgumentCaptor.forClass(BlockHistoryEntry.class);
        verify(storageService).trackBlockMutation(worldCaptor.capture(), keyCaptor.capture(), entryCaptor.capture());
        assertEquals("world", worldCaptor.getValue());
        assertEquals(new BlockPositionKey(world.getUID(), 1, 64, 1), keyCaptor.getValue());
        BlockHistoryEntry recordedEntry = entryCaptor.getValue();
        assertEquals(BlockHistoryAction.REMOVE, recordedEntry.action());
        assertEquals(playerUuid, recordedEntry.actorUuid());
        assertEquals(500L, recordedEntry.timestampEpochMillisUtc());
        assertEquals(CaptureTestSupport.blockDataString(Material.AIR), recordedEntry.afterBlockDataString());
        assertInstanceOf(ContainerBlockSnapshot.class, recordedEntry.removedSnapshot());
    }

    @Test
    void recordsSingleBlockPlacements() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 600L);
        Player player = CaptureTestSupport.player(UUID.randomUUID());
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block placedBlock = CaptureTestSupport.block(world, 4, 70, 4, Material.STONE);
        BlockState replacedState = CaptureTestSupport.blockState(placedBlock, Material.AIR);

        captureService.recordBlockPlace(player, placedBlock, replacedState);

        ArgumentCaptor<String> worldCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BlockPositionKey> keyCaptor = ArgumentCaptor.forClass(BlockPositionKey.class);
        ArgumentCaptor<BlockHistoryEntry> entryCaptor = ArgumentCaptor.forClass(BlockHistoryEntry.class);
        verify(storageService).trackBlockMutation(worldCaptor.capture(), keyCaptor.capture(), entryCaptor.capture());
        assertEquals("world", worldCaptor.getValue());
        assertEquals(new BlockPositionKey(world.getUID(), 4, 70, 4), keyCaptor.getValue());
        assertEquals(BlockHistoryAction.PLACE_OR_REPLACE, entryCaptor.getValue().action());
        assertEquals(CaptureTestSupport.blockDataString(Material.STONE), entryCaptor.getValue().afterBlockDataString());
    }

    @Test
    void recordsMultiPlaceEveryAffectedBlock() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 700L);
        Player player = CaptureTestSupport.player(UUID.randomUUID());
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        BlockState first = CaptureTestSupport.blockState(CaptureTestSupport.block(world, 1, 64, 1, Material.OAK_DOOR), Material.AIR);
        BlockState second = CaptureTestSupport.blockState(CaptureTestSupport.block(world, 1, 65, 1, Material.OAK_DOOR), Material.AIR);

        captureService.recordMultiPlace(player, List.of(first, second));

        verify(storageService, times(2)).trackBlockMutation(eq("world"), any(), any());
    }

    @Test
    void recordsSignChangesAsStateUpdates() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 800L);
        Player player = CaptureTestSupport.player(UUID.randomUUID());
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block block = CaptureTestSupport.block(world, 1, 64, 1, Material.OAK_SIGN);
        Sign sign = CaptureTestSupport.signState(
                block,
                new String[]{"old-1", "old-2", "", ""},
                new String[]{"back-1", "", "", ""},
                DyeColor.BLACK,
                DyeColor.WHITE,
                false,
                true,
                false
        );

        captureService.recordSignChange(player, block, Side.FRONT, new String[]{"new-1", "new-2", "", ""});

        ArgumentCaptor<String> worldCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BlockPositionKey> keyCaptor = ArgumentCaptor.forClass(BlockPositionKey.class);
        ArgumentCaptor<BlockHistoryEntry> entryCaptor = ArgumentCaptor.forClass(BlockHistoryEntry.class);
        verify(storageService).trackBlockMutation(worldCaptor.capture(), keyCaptor.capture(), entryCaptor.capture());
        assertEquals("world", worldCaptor.getValue());
        assertEquals(new BlockPositionKey(world.getUID(), 1, 64, 1), keyCaptor.getValue());
        BlockHistoryEntry recordedEntry = entryCaptor.getValue();
        SignBlockSnapshot snapshot = assertInstanceOf(SignBlockSnapshot.class, recordedEntry.resultSnapshot());
        assertEquals(BlockHistoryAction.STATE_UPDATE, recordedEntry.action());
        assertArrayEquals(new String[]{"new-1", "new-2", "", ""}, snapshot.frontLines());
    }

    @Test
    void recordsBucketEmptyAndBucketFillForFluids() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 900L);
        Player player = CaptureTestSupport.player(UUID.randomUUID());
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block fluidBlock = CaptureTestSupport.block(world, 8, 65, 8, Material.WATER);
        BlockState previousState = CaptureTestSupport.blockState(fluidBlock, Material.AIR);
        BlockState removedFluidState = CaptureTestSupport.blockState(fluidBlock, Material.WATER);

        captureService.recordBucketEmpty(player, fluidBlock, Material.WATER_BUCKET, previousState);
        captureService.recordBucketFill(player, removedFluidState);

        verify(storageService, times(2)).trackBlockMutation(eq("world"), any(BlockPositionKey.class), any(BlockHistoryEntry.class));
    }

    @Test
    void recordsTntExplosionRemovalsAndFormedBlocks() {
        StorageService storageService = mock(StorageService.class);
        BlockHistoryCaptureService captureService = new BlockHistoryCaptureService(storageService, new BlockSnapshotFactory(), () -> 1000L);
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block destroyedOne = CaptureTestSupport.block(world, 1, 64, 1, Material.STONE);
        CaptureTestSupport.blockState(destroyedOne, Material.STONE);
        Block destroyedTwo = CaptureTestSupport.block(world, 2, 64, 2, Material.GLASS);
        CaptureTestSupport.blockState(destroyedTwo, Material.GLASS);
        Block formedBlock = CaptureTestSupport.block(world, 3, 64, 3, Material.COBBLESTONE);
        BlockState newState = CaptureTestSupport.blockState(formedBlock, Material.COBBLESTONE);
        UUID actorUuid = UUID.randomUUID();

        captureService.recordExplosionRemovals(actorUuid, List.of(destroyedOne, destroyedTwo));
        captureService.recordFormedBlock(actorUuid, newState);

        verify(storageService, times(3)).trackBlockMutation(eq("world"), any(), any());
    }
}
