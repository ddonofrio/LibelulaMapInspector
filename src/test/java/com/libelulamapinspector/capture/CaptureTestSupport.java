package com.libelulamapinspector.capture;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class CaptureTestSupport {

    private CaptureTestSupport() {
    }

    public static World world(String name, UUID worldUuid) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getUID()).thenReturn(worldUuid);
        return world;
    }

    public static Player player(UUID playerUuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        return player;
    }

    public static Block block(World world, int x, int y, int z, Material material) {
        Block block = mock(Block.class);
        BlockData blockData = blockData(material);
        Location location = new Location(world, x, y, z);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenReturn(location);
        when(block.getType()).thenReturn(material);
        when(block.getBlockData()).thenReturn(blockData);
        return block;
    }

    public static BlockState blockState(Block block, Material material) {
        BlockState blockState = mock(BlockState.class);
        BlockData blockData = blockData(material);
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Location location = block.getLocation();
        when(blockState.getBlock()).thenReturn(block);
        when(blockState.getType()).thenReturn(material);
        when(blockState.getBlockData()).thenReturn(blockData);
        when(blockState.getWorld()).thenReturn(world);
        when(blockState.getX()).thenReturn(x);
        when(blockState.getY()).thenReturn(y);
        when(blockState.getZ()).thenReturn(z);
        when(blockState.getLocation()).thenReturn(location);
        when(block.getState()).thenReturn(blockState);
        return blockState;
    }

    public static Sign signState(
            Block block,
            String[] frontLines,
            String[] backLines,
            DyeColor frontColor,
            DyeColor backColor,
            boolean frontGlowing,
            boolean backGlowing,
            boolean waxed
    ) {
        Sign sign = mock(Sign.class);
        SignSide frontSide = mock(SignSide.class);
        SignSide backSide = mock(SignSide.class);
        Material material = block.getType();
        BlockData blockData = block.getBlockData();
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Location location = block.getLocation();
        when(frontSide.getColor()).thenReturn(frontColor);
        when(backSide.getColor()).thenReturn(backColor);
        when(frontSide.isGlowingText()).thenReturn(frontGlowing);
        when(backSide.isGlowingText()).thenReturn(backGlowing);
        for (int index = 0; index < 4; index++) {
            when(frontSide.getLine(index)).thenReturn(frontLines[index]);
            when(backSide.getLine(index)).thenReturn(backLines[index]);
        }

        when(sign.getBlock()).thenReturn(block);
        when(sign.getType()).thenReturn(material);
        when(sign.getBlockData()).thenReturn(blockData);
        when(sign.getWorld()).thenReturn(world);
        when(sign.getX()).thenReturn(x);
        when(sign.getY()).thenReturn(y);
        when(sign.getZ()).thenReturn(z);
        when(sign.getLocation()).thenReturn(location);
        when(sign.isWaxed()).thenReturn(waxed);
        when(sign.getSide(org.bukkit.block.sign.Side.FRONT)).thenReturn(frontSide);
        when(sign.getSide(org.bukkit.block.sign.Side.BACK)).thenReturn(backSide);
        when(block.getState()).thenReturn(sign);
        return sign;
    }

    public static Chest chestState(Block block, Inventory inventory) {
        Chest chest = mock(Chest.class);
        Material material = block.getType();
        BlockData blockData = block.getBlockData();
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        Location location = block.getLocation();
        when(chest.getBlock()).thenReturn(block);
        when(chest.getType()).thenReturn(material);
        when(chest.getBlockData()).thenReturn(blockData);
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(x);
        when(chest.getY()).thenReturn(y);
        when(chest.getZ()).thenReturn(z);
        when(chest.getLocation()).thenReturn(location);
        when(chest.getInventory()).thenReturn(inventory);
        when(block.getState()).thenReturn(chest);
        return chest;
    }

    public static Inventory inventory(InventoryHolder holder, ItemStack... contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        when(inventory.getContents()).thenReturn(contents);
        return inventory;
    }

    public static String blockDataString(Material material) {
        return switch (material) {
            case AIR -> "minecraft:air";
            case WATER -> "minecraft:water[level=0]";
            case LAVA -> "minecraft:lava[level=0]";
            default -> material.getKey().getNamespace() + ":" + material.getKey().getKey();
        };
    }

    private static BlockData blockData(Material material) {
        BlockData blockData = mock(BlockData.class);
        when(blockData.getAsString()).thenReturn(blockDataString(material));
        return blockData;
    }
}
