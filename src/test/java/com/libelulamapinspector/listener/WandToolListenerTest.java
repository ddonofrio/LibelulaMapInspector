package com.libelulamapinspector.listener;

import com.libelulamapinspector.LibelulaMapInspectorPlugin;
import com.libelulamapinspector.capture.CaptureTestSupport;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.support.PluginTestSupport;
import com.libelulamapinspector.wand.WandToolService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

class WandToolListenerTest {

    @TempDir
    Path tempDir;

    private LibelulaMapInspectorPlugin plugin;
    private StorageService storageService;
    private WandToolService wandToolService;
    private WandToolListener listener;

    @BeforeEach
    void setUp() throws Exception {
        plugin = PluginTestSupport.mockPlugin(LibelulaMapInspectorPlugin.class, tempDir.resolve("plugin-data"), configuration -> {
        });
        PluginTestSupport.wireImmediateScheduler(plugin);
        storageService = mock(StorageService.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(storageService).submitReadTask(any(Runnable.class));
        wandToolService = mock(WandToolService.class);
        listener = new WandToolListener(plugin, storageService, wandToolService);
    }

    @Test
    void creativeWandBreakReportsTheLatestActor() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack mainHand = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);
        UUID actorUuid = UUID.randomUUID();
        OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isWandItem(mainHand)).thenReturn(true);
        when(storageService.getBlockHistory(block.getLocation())).thenReturn(List.of(new BlockHistoryEntry(
                actorUuid,
                1000L,
                BlockHistoryAction.PLACE_OR_REPLACE,
                CaptureTestSupport.blockDataString(Material.STONE),
                null,
                null,
                null
        )));
        when(plugin.getServer().getOfflinePlayer(actorUuid)).thenReturn(offlinePlayer);
        when(offlinePlayer.getName()).thenReturn("Steve");

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(storageService).getBlockHistory(block.getLocation());
        verify(player).sendMessage(ChatColor.GOLD + "1970-01-01 00:00:01 " + ChatColor.YELLOW + "Steve");
    }

    @Test
    void removedBlockDetectorReportsTheLatestRemoveActor() throws Exception {
        Player player = mock(Player.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        ItemStack detector = mock(ItemStack.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.REDSTONE_ORE);
        UUID placedActorUuid = UUID.randomUUID();
        UUID removedActorUuid = UUID.randomUUID();
        OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);

        when(event.getPlayer()).thenReturn(player);
        when(event.getItemInHand()).thenReturn(detector);
        when(event.getBlockPlaced()).thenReturn(block);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(wandToolService.isRemovedBlockDetector(detector)).thenReturn(true);
        when(storageService.getBlockHistory(block.getLocation())).thenReturn(List.of(
                new BlockHistoryEntry(
                        placedActorUuid,
                        500L,
                        BlockHistoryAction.PLACE_OR_REPLACE,
                        CaptureTestSupport.blockDataString(Material.STONE),
                        null,
                        null,
                        null
                ),
                new BlockHistoryEntry(
                        removedActorUuid,
                        1000L,
                        BlockHistoryAction.REMOVE,
                        CaptureTestSupport.blockDataString(Material.AIR),
                        CaptureTestSupport.blockDataString(Material.STONE),
                        null,
                        null
                )
        ));
        when(plugin.getServer().getOfflinePlayer(removedActorUuid)).thenReturn(offlinePlayer);
        when(offlinePlayer.getName()).thenReturn("Alex");

        listener.onBlockPlace(event);

        verify(event).setCancelled(true);
        verify(storageService).getBlockHistory(block.getLocation());
        verify(player).sendMessage(ChatColor.GOLD + "1970-01-01 00:00:01 " + ChatColor.YELLOW + "Alex");
    }

    @Test
    void breakingWithTheRemovedBlockDetectorCancelsTheBreakAndReportsTheLatestRemoveActor() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack detector = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);
        UUID removedActorUuid = UUID.randomUUID();
        OfflinePlayer offlinePlayer = mock(OfflinePlayer.class);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(detector);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isRemovedBlockDetector(detector)).thenReturn(true);
        when(wandToolService.isBlockHistoryTool(detector)).thenReturn(false);
        when(wandToolService.isWandItem(detector)).thenReturn(false);
        when(storageService.getBlockHistory(block.getLocation())).thenReturn(List.of(
                new BlockHistoryEntry(
                        removedActorUuid,
                        1000L,
                        BlockHistoryAction.REMOVE,
                        CaptureTestSupport.blockDataString(Material.AIR),
                        CaptureTestSupport.blockDataString(Material.STONE),
                        null,
                        null
                )
        ));
        when(plugin.getServer().getOfflinePlayer(removedActorUuid)).thenReturn(offlinePlayer);
        when(offlinePlayer.getName()).thenReturn("Alex");

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(storageService).getBlockHistory(block.getLocation());
        verify(player).sendMessage(ChatColor.GOLD + "1970-01-01 00:00:01 " + ChatColor.YELLOW + "Alex");
    }

    @Test
    void blockHistoryToolReportsTheCompleteHistory() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack paper = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        OfflinePlayer offlinePlayerOne = mock(OfflinePlayer.class);
        OfflinePlayer offlinePlayerTwo = mock(OfflinePlayer.class);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(paper);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isWandItem(paper)).thenReturn(false);
        when(wandToolService.isBlockHistoryTool(paper)).thenReturn(true);
        when(storageService.getBlockHistory(block.getLocation())).thenReturn(List.of(
                new BlockHistoryEntry(
                        actorOne,
                        1000L,
                        BlockHistoryAction.PLACE_OR_REPLACE,
                        CaptureTestSupport.blockDataString(Material.STONE),
                        null,
                        null,
                        null
                ),
                new BlockHistoryEntry(
                        actorTwo,
                        2000L,
                        BlockHistoryAction.REMOVE,
                        CaptureTestSupport.blockDataString(Material.AIR),
                        CaptureTestSupport.blockDataString(Material.STONE),
                        null,
                        null
                )
        ));
        when(plugin.getServer().getOfflinePlayer(actorOne)).thenReturn(offlinePlayerOne);
        when(plugin.getServer().getOfflinePlayer(actorTwo)).thenReturn(offlinePlayerTwo);
        when(offlinePlayerOne.getName()).thenReturn("Steve");
        when(offlinePlayerTwo.getName()).thenReturn("Alex");

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(ChatColor.YELLOW + "Block history:");
        verify(player).sendMessage(ChatColor.GOLD + "1970-01-01 00:00:01 " + ChatColor.YELLOW + "Steve " + ChatColor.GRAY + "placed or replaced");
        verify(player).sendMessage(ChatColor.GOLD + "1970-01-01 00:00:02 " + ChatColor.YELLOW + "Alex " + ChatColor.GRAY + "removed");
    }

    @Test
    void placingTheWandIsAlwaysCancelledWithAPreciousToolMessage() throws Exception {
        Player player = mock(Player.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        ItemStack wand = mock(ItemStack.class);

        when(event.getPlayer()).thenReturn(player);
        when(event.getItemInHand()).thenReturn(wand);
        when(wandToolService.isWandItem(wand)).thenReturn(true);

        listener.onBlockPlace(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("too precious"));
        verify(storageService, never()).getBlockHistory(any(Location.class));
    }

    @Test
    void nonCreativePlayersCannotUseTheWand() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack mainHand = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isWandItem(mainHand)).thenReturn(true);

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(storageService, never()).getBlockHistory(block.getLocation());
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("creative mode"));
    }

    @Test
    void nonCreativePlayersCannotUseTheRemovedBlockDetector() throws Exception {
        Player player = mock(Player.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        ItemStack detector = mock(ItemStack.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.REDSTONE_ORE);

        when(event.getPlayer()).thenReturn(player);
        when(event.getItemInHand()).thenReturn(detector);
        when(event.getBlockPlaced()).thenReturn(block);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(wandToolService.isRemovedBlockDetector(detector)).thenReturn(true);

        listener.onBlockPlace(event);

        verify(event).setCancelled(true);
        verify(storageService, never()).getBlockHistory(block.getLocation());
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("creative mode"));
    }

    @Test
    void nonCreativePlayersCannotUseTheRemovedBlockDetectorWhenBreaking() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack detector = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(detector);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isRemovedBlockDetector(detector)).thenReturn(true);
        when(wandToolService.isBlockHistoryTool(detector)).thenReturn(false);
        when(wandToolService.isWandItem(detector)).thenReturn(false);

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(storageService, never()).getBlockHistory(block.getLocation());
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("creative mode"));
    }

    @Test
    void creativeWandBreakReportsWhenNoInformationExists() throws Exception {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack mainHand = mock(ItemStack.class);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        Block block = CaptureTestSupport.block(CaptureTestSupport.world("world", UUID.randomUUID()), 10, 64, 10, Material.STONE);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(player.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        when(wandToolService.isWandItem(mainHand)).thenReturn(true);
        when(storageService.getBlockHistory(block.getLocation())).thenReturn(List.of());

        listener.onBlockBreak(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(ChatColor.GRAY.toString() + ChatColor.ITALIC + "No information was found");
    }

    @Test
    void droppingTheWandIsCancelled() {
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        Item itemEntity = mock(Item.class);
        ItemStack itemStack = mock(ItemStack.class);
        Player player = mock(Player.class);

        when(event.getItemDrop()).thenReturn(itemEntity);
        when(itemEntity.getItemStack()).thenReturn(itemStack);
        when(event.getPlayer()).thenReturn(player);
        when(wandToolService.isManagedTool(itemStack)).thenReturn(true);

        listener.onPlayerDropItem(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("too powerful"));
    }

    @Test
    void droppingTheRemovedBlockDetectorIsCancelled() {
        PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
        Item itemEntity = mock(Item.class);
        ItemStack itemStack = mock(ItemStack.class);
        Player player = mock(Player.class);

        when(event.getItemDrop()).thenReturn(itemEntity);
        when(itemEntity.getItemStack()).thenReturn(itemStack);
        when(event.getPlayer()).thenReturn(player);
        when(wandToolService.isManagedTool(itemStack)).thenReturn(true);

        listener.onPlayerDropItem(event);

        verify(event).setCancelled(true);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("too powerful"));
    }
}
