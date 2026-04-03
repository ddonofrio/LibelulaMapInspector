package com.libelulamapinspector.capture;

import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.ContainerBlockSnapshot;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockSnapshotFactoryTest {

    private final BlockSnapshotFactory snapshotFactory = new BlockSnapshotFactory();

    @Test
    void createsBreakEntriesWithRemovedSignSnapshots() {
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block block = CaptureTestSupport.block(world, 1, 64, 1, Material.OAK_SIGN);
        Sign sign = CaptureTestSupport.signState(
                block,
                new String[]{"front-1", "front-2", "", ""},
                new String[]{"back-1", "", "", ""},
                DyeColor.BLACK,
                DyeColor.BLUE,
                true,
                false,
                true
        );

        BlockHistoryEntry entry = snapshotFactory.createBreakEntry(UUID.randomUUID(), 1234L, sign);

        SignBlockSnapshot removedSnapshot = assertInstanceOf(SignBlockSnapshot.class, entry.removedSnapshot());
        assertEquals(BlockHistoryAction.REMOVE, entry.action());
        assertEquals(CaptureTestSupport.blockDataString(Material.AIR), entry.afterBlockDataString());
        assertEquals(CaptureTestSupport.blockDataString(Material.OAK_SIGN), entry.removedBlockDataString());
        assertArrayEquals(new String[]{"front-1", "front-2", "", ""}, removedSnapshot.frontLines());
        assertArrayEquals(new String[]{"back-1", "", "", ""}, removedSnapshot.backLines());
        assertEquals("BLUE", removedSnapshot.backColorName());
        assertTrue(removedSnapshot.frontGlowingText());
        assertTrue(removedSnapshot.waxed());
    }

    @Test
    void createsPlaceEntriesWithRemovedDoubleChestSnapshots() {
        World world = CaptureTestSupport.world("world", UUID.randomUUID());
        Block currentChestBlock = CaptureTestSupport.block(world, 10, 64, 10, Material.CHEST);
        Block partnerChestBlock = CaptureTestSupport.block(world, 11, 64, 10, Material.CHEST);
        Chest leftChest = mock(Chest.class);
        when(leftChest.getBlock()).thenReturn(currentChestBlock);
        Chest rightChest = mock(Chest.class);
        when(rightChest.getBlock()).thenReturn(partnerChestBlock);
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(leftChest);
        when(doubleChest.getRightSide()).thenReturn(rightChest);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(doubleChest);
        when(inventory.getContents()).thenReturn(new ItemStack[]{new ItemStack(Material.DIAMOND, 3), null});
        Chest replacedState = CaptureTestSupport.chestState(currentChestBlock, inventory);

        Block placedBlock = CaptureTestSupport.block(world, 10, 64, 10, Material.DIRT);
        BlockHistoryEntry entry = snapshotFactory.createPlaceEntry(UUID.randomUUID(), 1234L, placedBlock, replacedState);

        ContainerBlockSnapshot removedSnapshot = assertInstanceOf(ContainerBlockSnapshot.class, entry.removedSnapshot());
        assertEquals(BlockHistoryAction.PLACE_OR_REPLACE, entry.action());
        assertEquals(CaptureTestSupport.blockDataString(Material.DIRT), entry.afterBlockDataString());
        assertEquals(CaptureTestSupport.blockDataString(Material.CHEST), entry.removedBlockDataString());
        assertTrue(removedSnapshot.isDoubleChest());
        assertEquals(1, removedSnapshot.slots().size());
    }

    @Test
    void overridesTheChangedSignSideWhenBuildingAStateUpdateSnapshot() {
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

        SignBlockSnapshot snapshot = snapshotFactory.captureSignSnapshot(sign, Side.FRONT, new String[]{"new-1", "new-2", "", ""});

        assertArrayEquals(new String[]{"new-1", "new-2", "", ""}, snapshot.frontLines());
        assertArrayEquals(new String[]{"back-1", "", "", ""}, snapshot.backLines());
        assertEquals("WHITE", snapshot.backColorName());
    }
}
