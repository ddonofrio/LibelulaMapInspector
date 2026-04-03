package com.libelulamapinspector.undo;

import com.libelulamapinspector.storage.ContainerBlockSnapshot;
import com.libelulamapinspector.storage.ContainerSlotSnapshot;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import com.libelulamapinspector.storage.SpecialBlockSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Applies precomputed undo states to live Bukkit blocks on the main thread.
 */
public final class UndoBlockApplier {

    private final Logger logger;

    public UndoBlockApplier(Logger logger) {
        this.logger = logger;
    }

    public void applyBlockState(World world, UndoWorldChange worldChange) {
        Block block = world.getBlockAt(worldChange.positionKey().x(), worldChange.positionKey().y(), worldChange.positionKey().z());
        UndoResolvedState desiredState = worldChange.desiredState();
        if (desiredState.kind() == UndoResolvedState.Kind.AIR) {
            block.setType(Material.AIR, false);
            return;
        }

        block.setBlockData(Bukkit.createBlockData(desiredState.blockDataString()), false);
    }

    public void applyDeferredSnapshot(World world, UndoWorldChange worldChange) {
        SpecialBlockSnapshot snapshot = worldChange.desiredState().snapshot();
        if (snapshot == null) {
            return;
        }

        Block block = world.getBlockAt(worldChange.positionKey().x(), worldChange.positionKey().y(), worldChange.positionKey().z());
        if (snapshot instanceof SignBlockSnapshot signSnapshot) {
            applySignSnapshot(block, signSnapshot);
            return;
        }

        if (snapshot instanceof ContainerBlockSnapshot containerSnapshot) {
            applyContainerSnapshot(block, containerSnapshot);
        }
    }

    private void applySignSnapshot(Block block, SignBlockSnapshot signSnapshot) {
        if (!(block.getState() instanceof Sign sign)) {
            return;
        }

        applySignSide(sign.getSide(Side.FRONT), signSnapshot.frontLines(), signSnapshot.frontColorName(), signSnapshot.frontGlowingText());
        applySignSide(sign.getSide(Side.BACK), signSnapshot.backLines(), signSnapshot.backColorName(), signSnapshot.backGlowingText());
        sign.setWaxed(signSnapshot.waxed());
        sign.update(true, false);
    }

    private void applySignSide(SignSide signSide, String[] lines, String colorName, boolean glowing) {
        for (int index = 0; index < lines.length; index++) {
            signSide.setLine(index, lines[index]);
        }
        signSide.setGlowingText(glowing);
        try {
            signSide.setColor(DyeColor.valueOf(colorName.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            signSide.setColor(DyeColor.BLACK);
        }
    }

    private void applyContainerSnapshot(Block block, ContainerBlockSnapshot containerSnapshot) {
        if (!(block.getState() instanceof BlockInventoryHolder inventoryHolder)) {
            return;
        }

        Inventory inventory = inventoryHolder.getInventory();
        inventory.clear();
        for (ContainerSlotSnapshot slot : containerSnapshot.slots()) {
            if (slot.slotIndex() < 0 || slot.slotIndex() >= inventory.getSize()) {
                continue;
            }

            ItemStack itemStack = deserializeItemStack(slot.serializedItemBytes());
            if (itemStack != null) {
                inventory.setItem(slot.slotIndex(), itemStack);
            }
        }
    }

    private ItemStack deserializeItemStack(byte[] serializedItemBytes) {
        if (serializedItemBytes == null || serializedItemBytes.length == 0) {
            return null;
        }

        if (Bukkit.getServer() == null) {
            return fallbackDeserializeItemStack(serializedItemBytes);
        }

        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(serializedItemBytes))) {
            Object restoredObject = input.readObject();
            return restoredObject instanceof ItemStack itemStack ? itemStack : null;
        } catch (IOException | ClassNotFoundException exception) {
            logger.warning("Unable to deserialize an ItemStack snapshot during undo. The affected inventory slot will be skipped.");
            return null;
        }
    }

    private ItemStack fallbackDeserializeItemStack(byte[] serializedItemBytes) {
        String rawValue = new String(serializedItemBytes, StandardCharsets.UTF_8);
        String[] parts = rawValue.split(":");
        if (parts.length != 2) {
            return null;
        }

        try {
            Material material = Material.valueOf(parts[0]);
            int amount = Integer.parseInt(parts[1]);
            return new ItemStack(material, amount);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
