package com.libelulamapinspector.capture;

import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.ContainerBlockSnapshot;
import com.libelulamapinspector.storage.ContainerSlotSnapshot;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import com.libelulamapinspector.storage.SpecialBlockSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds block history entries and special snapshots from Bukkit block state.
 */
public final class BlockSnapshotFactory {

    public BlockHistoryEntry createBreakEntry(UUID actorUuid, long timestampEpochMillisUtc, BlockState removedState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.REMOVE,
                blockDataString(Material.AIR),
                removedState.getBlockData().getAsString(),
                null,
                captureSpecialSnapshot(removedState)
        );
    }

    public BlockHistoryEntry createPlaceEntry(UUID actorUuid, long timestampEpochMillisUtc, Block placedBlock, BlockState replacedState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.PLACE_OR_REPLACE,
                placedBlock.getBlockData().getAsString(),
                replacedState != null && replacedState.getType() != Material.AIR ? replacedState.getBlockData().getAsString() : null,
                null,
                replacedState != null && replacedState.getType() != Material.AIR ? captureSpecialSnapshot(replacedState) : null
        );
    }

    public BlockHistoryEntry createStateUpdateEntry(UUID actorUuid, long timestampEpochMillisUtc, Block block, SpecialBlockSnapshot resultSnapshot) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.STATE_UPDATE,
                block.getBlockData().getAsString(),
                null,
                resultSnapshot,
                null
        );
    }

    public BlockHistoryEntry createBucketEmptyEntry(UUID actorUuid, long timestampEpochMillisUtc, Block targetBlock, Material fluidMaterial, BlockState replacedState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.PLACE_OR_REPLACE,
                blockDataString(fluidMaterial),
                replacedState != null && replacedState.getType() != Material.AIR ? replacedState.getBlockData().getAsString() : null,
                null,
                replacedState != null && replacedState.getType() != Material.AIR ? captureSpecialSnapshot(replacedState) : null
        );
    }

    public BlockHistoryEntry createBucketFillEntry(UUID actorUuid, long timestampEpochMillisUtc, BlockState removedFluidState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.REMOVE,
                blockDataString(Material.AIR),
                removedFluidState.getBlockData().getAsString(),
                null,
                null
        );
    }

    public BlockHistoryEntry createFormedBlockEntry(UUID actorUuid, long timestampEpochMillisUtc, BlockState newState) {
        return new BlockHistoryEntry(
                actorUuid,
                timestampEpochMillisUtc,
                BlockHistoryAction.PLACE_OR_REPLACE,
                newState.getBlockData().getAsString(),
                null,
                null,
                null
        );
    }

    public SignBlockSnapshot captureSignSnapshot(Sign sign) {
        return captureSignSnapshot(sign, null, null);
    }

    public SignBlockSnapshot captureSignSnapshot(Sign sign, Side changedSide, String[] changedLines) {
        SignSide frontSide = sign.getSide(Side.FRONT);
        SignSide backSide = sign.getSide(Side.BACK);

        String[] frontLines = copySignLines(frontSide);
        String[] backLines = copySignLines(backSide);
        if (changedSide == Side.FRONT) {
            frontLines = normalizeLines(changedLines);
        } else if (changedSide == Side.BACK) {
            backLines = normalizeLines(changedLines);
        }

        return new SignBlockSnapshot(
                frontLines,
                backLines,
                colorName(frontSide),
                colorName(backSide),
                frontSide.isGlowingText(),
                backSide.isGlowingText(),
                sign.isWaxed()
        );
    }

    public SpecialBlockSnapshot captureSpecialSnapshot(BlockState blockState) {
        if (blockState instanceof Sign sign) {
            return captureSignSnapshot(sign);
        }

        if (blockState instanceof BlockInventoryHolder inventoryHolder) {
            return captureContainerSnapshot(blockState, inventoryHolder);
        }

        return null;
    }

    private ContainerBlockSnapshot captureContainerSnapshot(BlockState blockState, BlockInventoryHolder inventoryHolder) {
        Inventory inventory = inventoryHolder.getInventory();
        boolean doubleChest = false;
        int partnerOffsetX = 0;
        int partnerOffsetY = 0;
        int partnerOffsetZ = 0;

        if (blockState instanceof Chest && inventory.getHolder() instanceof DoubleChest doubleChestHolder) {
            doubleChest = true;
            Location partnerLocation = resolvePartnerLocation(blockState, doubleChestHolder);
            if (partnerLocation != null) {
                partnerOffsetX = partnerLocation.getBlockX() - blockState.getX();
                partnerOffsetY = partnerLocation.getBlockY() - blockState.getY();
                partnerOffsetZ = partnerLocation.getBlockZ() - blockState.getZ();
            }
        }

        ItemStack[] contents = inventory.getContents();
        List<ContainerSlotSnapshot> slots = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < contents.length; slotIndex++) {
            ItemStack itemStack = contents[slotIndex];
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }

            slots.add(new ContainerSlotSnapshot(slotIndex, serializeItemStack(itemStack)));
        }

        return new ContainerBlockSnapshot(doubleChest, partnerOffsetX, partnerOffsetY, partnerOffsetZ, contents.length, slots);
    }

    private Location resolvePartnerLocation(BlockState blockState, DoubleChest doubleChest) {
        Location currentLocation = blockState.getLocation();
        if (currentLocation == null) {
            return null;
        }

        Location leftLocation = inventoryHolderLocation(doubleChest.getLeftSide());
        Location rightLocation = inventoryHolderLocation(doubleChest.getRightSide());

        if (leftLocation != null && !sameBlock(leftLocation, currentLocation)) {
            return leftLocation;
        }

        if (rightLocation != null && !sameBlock(rightLocation, currentLocation)) {
            return rightLocation;
        }

        return null;
    }

    private Location inventoryHolderLocation(InventoryHolder inventoryHolder) {
        if (inventoryHolder instanceof BlockInventoryHolder blockInventoryHolder) {
            return blockInventoryHolder.getBlock().getLocation();
        }

        return null;
    }

    private boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null
                && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private byte[] serializeItemStack(ItemStack itemStack) {
        if (Bukkit.getServer() == null) {
            return fallbackSerializedItemStack(itemStack);
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(itemStack);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to serialize ItemStack for block history capture.", exception);
        }
    }

    private byte[] fallbackSerializedItemStack(ItemStack itemStack) {
        String serialized = itemStack.getType().name() + ":" + itemStack.getAmount();
        return serialized.getBytes(StandardCharsets.UTF_8);
    }

    private String[] copySignLines(SignSide signSide) {
        String[] lines = new String[4];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = signSide.getLine(index);
        }
        return lines;
    }

    private String[] normalizeLines(String[] lines) {
        String[] normalized = new String[4];
        for (int index = 0; index < normalized.length; index++) {
            normalized[index] = lines != null && index < lines.length && lines[index] != null ? lines[index] : "";
        }
        return normalized;
    }

    private String colorName(SignSide signSide) {
        DyeColor color = signSide.getColor();
        return color != null ? color.name() : DyeColor.BLACK.name();
    }

    private String blockDataString(Material material) {
        try {
            return material.createBlockData().getAsString();
        } catch (RuntimeException ignored) {
            return fallbackBlockDataString(material);
        }
    }

    private String fallbackBlockDataString(Material material) {
        if (material == Material.AIR) {
            return "minecraft:air";
        }
        if (material == Material.WATER) {
            return "minecraft:water[level=0]";
        }
        if (material == Material.LAVA) {
            return "minecraft:lava[level=0]";
        }

        NamespacedKey key = material.getKey();
        return key.getNamespace() + ":" + key.getKey();
    }
}
