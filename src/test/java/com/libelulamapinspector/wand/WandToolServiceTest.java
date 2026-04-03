package com.libelulamapinspector.wand;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WandToolServiceTest {

    @Test
    void onlyRecognizesTheExactManagedTools() {
        WandToolService wandToolService = new WandToolService(toolFactories(inspectorItem(), detectorItem(), historyItem()));

        assertTrue(wandToolService.isWandItem(inspectorItem()));
        assertTrue(wandToolService.isRemovedBlockDetector(detectorItem()));
        assertTrue(wandToolService.isBlockHistoryTool(historyItem()));
        assertTrue(wandToolService.isManagedTool(inspectorItem()));
        assertTrue(wandToolService.isManagedTool(detectorItem()));
        assertTrue(wandToolService.isManagedTool(historyItem()));
        assertFalse(wandToolService.isManagedTool(nonToolItem(Material.LIGHTNING_ROD)));
        assertFalse(wandToolService.isManagedTool(namedItem(Material.REDSTONE_ORE, "Other Detector")));
        assertFalse(wandToolService.isManagedTool(namedItem(Material.PAPER, "Other History")));
        assertFalse(wandToolService.isManagedTool(null));
    }

    @Test
    void arrangesAllToolsIntoSlotsOneTwoAndThree() {
        ItemStack inspector = inspectorItem();
        ItemStack detector = detectorItem();
        ItemStack history = historyItem();
        WandToolService wandToolService = new WandToolService(toolFactories(inspector, detector, history));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack slotZeroItem = nonToolItem(Material.STONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getItem(0)).thenReturn(slotZeroItem);
        when(inventory.getItem(1)).thenReturn(null);
        when(inventory.getItem(2)).thenReturn(null);
        when(inventory.getItem(5)).thenReturn(inspector);
        when(inventory.getItem(6)).thenReturn(detector);
        when(inventory.getItem(7)).thenReturn(history);
        when(inventory.getItem(2)).thenReturn(null);
        when(inventory.getItem(3)).thenReturn(null);
        when(inventory.getItem(4)).thenReturn(null);
        when(inventory.getItem(8)).thenReturn(null);

        assertEquals(WandToolService.ArrangeResult.ARRANGED, wandToolService.arrangeTools(player));
        verify(inventory).setItem(0, inspector);
        verify(inventory).setItem(1, detector);
        verify(inventory).setItem(2, history);
        verify(inventory).setHeldItemSlot(0);
    }

    @Test
    void createsMissingToolsAndMovesOccupiedSlotsAway() {
        ItemStack inspector = inspectorItem();
        ItemStack detector = detectorItem();
        ItemStack history = historyItem();
        WandToolService wandToolService = new WandToolService(toolFactories(inspector, detector, history));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack slotZeroItem = nonToolItem(Material.STONE);
        ItemStack slotOneItem = nonToolItem(Material.DIRT);
        ItemStack slotTwoItem = nonToolItem(Material.COBBLESTONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getItem(0)).thenReturn(slotZeroItem);
        when(inventory.getItem(1)).thenReturn(slotOneItem);
        when(inventory.getItem(2)).thenReturn(slotTwoItem);
        when(inventory.getItem(3)).thenReturn(null);
        when(inventory.getItem(4)).thenReturn(null);
        when(inventory.getItem(5)).thenReturn(null);
        when(inventory.getItem(6)).thenReturn(null);
        when(inventory.getItem(7)).thenReturn(null);
        when(inventory.getItem(8)).thenReturn(null);

        assertEquals(WandToolService.ArrangeResult.ARRANGED, wandToolService.arrangeTools(player));
        verify(inventory).setItem(0, inspector);
        verify(inventory).setItem(1, detector);
        verify(inventory).setItem(2, history);
        verify(inventory).setItem(3, slotZeroItem);
        verify(inventory).setItem(4, slotOneItem);
        verify(inventory).setItem(5, slotTwoItem);
    }

    @Test
    void reportsAlreadyArrangedWhenAllToolsAreAlreadyCorrect() {
        ItemStack inspector = inspectorItem();
        ItemStack detector = detectorItem();
        ItemStack history = historyItem();
        WandToolService wandToolService = new WandToolService(toolFactories(inspector, detector, history));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getItem(0)).thenReturn(inspector);
        when(inventory.getItem(1)).thenReturn(detector);
        when(inventory.getItem(2)).thenReturn(history);
        when(inventory.getItem(3)).thenReturn(null);
        when(inventory.getItem(4)).thenReturn(null);
        when(inventory.getItem(5)).thenReturn(null);
        when(inventory.getItem(6)).thenReturn(null);
        when(inventory.getItem(7)).thenReturn(null);
        when(inventory.getItem(8)).thenReturn(null);

        assertEquals(WandToolService.ArrangeResult.ALREADY_ARRANGED, wandToolService.arrangeTools(player));
        verify(inventory, never()).setItem(0, inspector);
        verify(inventory).setHeldItemSlot(0);
    }

    @Test
    void refusesToArrangeWhenThereIsNotEnoughInventorySpace() {
        ItemStack inspector = inspectorItem();
        ItemStack detector = detectorItem();
        ItemStack history = historyItem();
        WandToolService wandToolService = new WandToolService(toolFactories(inspector, detector, history));
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack slotZeroItem = nonToolItem(Material.STONE);
        ItemStack slotOneItem = nonToolItem(Material.DIRT);
        ItemStack slotTwoItem = nonToolItem(Material.COBBLESTONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(3);
        when(inventory.getItem(0)).thenReturn(slotZeroItem);
        when(inventory.getItem(1)).thenReturn(slotOneItem);
        when(inventory.getItem(2)).thenReturn(slotTwoItem);

        assertEquals(WandToolService.ArrangeResult.INVENTORY_FULL, wandToolService.arrangeTools(player));
        verify(inventory, never()).setHeldItemSlot(0);
    }

    private static Map<WandToolService.ToolType, Supplier<ItemStack>> toolFactories(ItemStack inspector, ItemStack detector, ItemStack history) {
        Map<WandToolService.ToolType, Supplier<ItemStack>> factories = new EnumMap<>(WandToolService.ToolType.class);
        factories.put(WandToolService.ToolType.INSPECTOR, () -> inspector);
        factories.put(WandToolService.ToolType.REMOVED_BLOCK_DETECTOR, () -> detector);
        factories.put(WandToolService.ToolType.BLOCK_HISTORY, () -> history);
        return factories;
    }

    private static ItemStack inspectorItem() {
        return namedItem(WandToolService.WAND_MATERIAL, WandToolService.WAND_NAME);
    }

    private static ItemStack detectorItem() {
        return namedItem(WandToolService.REMOVED_BLOCK_DETECTOR_MATERIAL, WandToolService.REMOVED_BLOCK_DETECTOR_NAME);
    }

    private static ItemStack historyItem() {
        return namedItem(WandToolService.BLOCK_HISTORY_MATERIAL, WandToolService.BLOCK_HISTORY_NAME);
    }

    private static ItemStack nonToolItem(Material material) {
        ItemStack itemStack = mock(ItemStack.class);
        when(itemStack.getType()).thenReturn(material);
        when(itemStack.hasItemMeta()).thenReturn(false);
        return itemStack;
    }

    private static ItemStack namedItem(Material material, String name) {
        ItemStack itemStack = mock(ItemStack.class);
        ItemMeta itemMeta = mock(ItemMeta.class);
        when(itemStack.getType()).thenReturn(material);
        when(itemStack.hasItemMeta()).thenReturn(true);
        when(itemStack.getItemMeta()).thenReturn(itemMeta);
        when(itemMeta.hasDisplayName()).thenReturn(true);
        when(itemMeta.getDisplayName()).thenReturn(name);
        return itemStack;
    }
}
