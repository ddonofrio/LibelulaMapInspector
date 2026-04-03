package com.libelulamapinspector.wand;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds and manages the administrative LMI tools.
 */
public final class WandToolService {

    public static final String WAND_NAME = "LMI Wand Tool";
    public static final Material WAND_MATERIAL = Material.WAXED_LIGHTNING_ROD;
    public static final String REMOVED_BLOCK_DETECTOR_NAME = "Removed Block Detector";
    public static final Material REMOVED_BLOCK_DETECTOR_MATERIAL = Material.REDSTONE_ORE;
    public static final String BLOCK_HISTORY_NAME = "Block History";
    public static final Material BLOCK_HISTORY_MATERIAL = Material.PAPER;

    private final Map<ToolType, Supplier<ItemStack>> toolFactories;

    public WandToolService() {
        this(createDefaultFactories());
    }

    WandToolService(Map<ToolType, Supplier<ItemStack>> toolFactories) {
        this.toolFactories = Map.copyOf(Objects.requireNonNull(toolFactories, "toolFactories"));
    }

    public boolean isWandItem(ItemStack itemStack) {
        return ToolType.INSPECTOR.matches(itemStack);
    }

    public boolean isRemovedBlockDetector(ItemStack itemStack) {
        return ToolType.REMOVED_BLOCK_DETECTOR.matches(itemStack);
    }

    public boolean isBlockHistoryTool(ItemStack itemStack) {
        return ToolType.BLOCK_HISTORY.matches(itemStack);
    }

    public boolean isManagedTool(ItemStack itemStack) {
        return isWandItem(itemStack) || isRemovedBlockDetector(itemStack) || isBlockHistoryTool(itemStack);
    }

    public ArrangeResult arrangeTools(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerInventory inventory = player.getInventory();
        ItemStack[] originalItems = snapshot(inventory);
        ItemStack[] arrangedItems = originalItems.clone();
        boolean changed = false;

        for (ToolType toolType : ToolType.values()) {
            int currentSlot = findMatchingSlot(arrangedItems, toolType);
            if (currentSlot == toolType.targetSlot()) {
                continue;
            }

            if (currentSlot >= 0) {
                ItemStack temporaryItem = arrangedItems[toolType.targetSlot()];
                arrangedItems[toolType.targetSlot()] = arrangedItems[currentSlot];
                arrangedItems[currentSlot] = temporaryItem;
                changed = true;
            }
        }

        for (ToolType toolType : ToolType.values()) {
            if (toolType.matches(arrangedItems[toolType.targetSlot()])) {
                continue;
            }

            if (isEmpty(arrangedItems[toolType.targetSlot()])) {
                arrangedItems[toolType.targetSlot()] = createToolItem(toolType);
                changed = true;
                continue;
            }

            int emptySlot = findFirstEmptySlot(arrangedItems);
            if (emptySlot < 0) {
                return ArrangeResult.INVENTORY_FULL;
            }

            arrangedItems[emptySlot] = arrangedItems[toolType.targetSlot()];
            arrangedItems[toolType.targetSlot()] = createToolItem(toolType);
            changed = true;
        }

        if (!changed) {
            inventory.setHeldItemSlot(ToolType.INSPECTOR.targetSlot());
            return ArrangeResult.ALREADY_ARRANGED;
        }

        applySnapshot(inventory, arrangedItems);
        inventory.setHeldItemSlot(ToolType.INSPECTOR.targetSlot());
        return ArrangeResult.ARRANGED;
    }

    private ItemStack createToolItem(ToolType toolType) {
        Supplier<ItemStack> supplier = toolFactories.get(toolType);
        if (supplier == null) {
            throw new IllegalStateException("Missing tool factory for " + toolType + ".");
        }

        return supplier.get();
    }

    private ItemStack[] snapshot(PlayerInventory inventory) {
        ItemStack[] items = new ItemStack[inventory.getSize()];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = inventory.getItem(slot);
        }
        return items;
    }

    private void applySnapshot(PlayerInventory inventory, ItemStack[] items) {
        for (int slot = 0; slot < items.length; slot++) {
            inventory.setItem(slot, items[slot]);
        }
    }

    private int findMatchingSlot(ItemStack[] items, ToolType toolType) {
        for (int slot = 0; slot < items.length; slot++) {
            if (toolType.matches(items[slot])) {
                return slot;
            }
        }

        return -1;
    }

    private int findFirstEmptySlot(ItemStack[] items) {
        for (int slot = 0; slot < items.length; slot++) {
            if (isEmpty(items[slot])) {
                return slot;
            }
        }

        return -1;
    }

    private static boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR;
    }

    private static Map<ToolType, Supplier<ItemStack>> createDefaultFactories() {
        Map<ToolType, Supplier<ItemStack>> factories = new EnumMap<>(ToolType.class);
        factories.put(ToolType.INSPECTOR, () -> createToolItem(ToolType.INSPECTOR.material(), ToolType.INSPECTOR.displayName()));
        factories.put(ToolType.REMOVED_BLOCK_DETECTOR, () -> createToolItem(ToolType.REMOVED_BLOCK_DETECTOR.material(), ToolType.REMOVED_BLOCK_DETECTOR.displayName()));
        factories.put(ToolType.BLOCK_HISTORY, () -> createToolItem(ToolType.BLOCK_HISTORY.material(), ToolType.BLOCK_HISTORY.displayName()));
        return factories;
    }

    private static ItemStack createToolItem(Material material, String displayName) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) {
            throw new IllegalStateException("Unable to create item meta for " + displayName + ".");
        }

        itemMeta.setDisplayName(displayName);
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }

    public enum ArrangeResult {
        ARRANGED,
        ALREADY_ARRANGED,
        INVENTORY_FULL
    }

    enum ToolType {
        INSPECTOR(WAND_MATERIAL, WAND_NAME, 0),
        REMOVED_BLOCK_DETECTOR(REMOVED_BLOCK_DETECTOR_MATERIAL, REMOVED_BLOCK_DETECTOR_NAME, 1),
        BLOCK_HISTORY(BLOCK_HISTORY_MATERIAL, BLOCK_HISTORY_NAME, 2);

        private final Material material;
        private final String displayName;
        private final int targetSlot;

        ToolType(Material material, String displayName, int targetSlot) {
            this.material = material;
            this.displayName = displayName;
            this.targetSlot = targetSlot;
        }

        public Material material() {
            return material;
        }

        public String displayName() {
            return displayName;
        }

        public int targetSlot() {
            return targetSlot;
        }

        public boolean matches(ItemStack itemStack) {
            if (itemStack == null || itemStack.getType() != material || !itemStack.hasItemMeta()) {
                return false;
            }

            ItemMeta itemMeta = itemStack.getItemMeta();
            return itemMeta != null && itemMeta.hasDisplayName() && displayName.equals(itemMeta.getDisplayName());
        }
    }
}
