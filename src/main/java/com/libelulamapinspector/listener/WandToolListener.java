package com.libelulamapinspector.listener;

import com.libelulamapinspector.LibelulaMapInspectorPlugin;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.wand.WandToolService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles the administrative LMI wand interactions.
 */
public final class WandToolListener implements Listener {

    private static final String ADMIN_PERMISSION = "libelulamapinspector.admin";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC);

    private final LibelulaMapInspectorPlugin plugin;
    private final StorageService storageService;
    private final WandToolService wandToolService;

    public WandToolListener(LibelulaMapInspectorPlugin plugin, StorageService storageService, WandToolService wandToolService) {
        this.plugin = plugin;
        this.storageService = storageService;
        this.wandToolService = wandToolService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        boolean inspectorTool = wandToolService.isWandItem(heldItem);
        boolean removedBlockDetector = wandToolService.isRemovedBlockDetector(heldItem);
        boolean blockHistoryTool = wandToolService.isBlockHistoryTool(heldItem);
        if (!inspectorTool && !removedBlockDetector && !blockHistoryTool) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use the LMI tools.");
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            player.sendMessage(ChatColor.RED + "You must be in creative mode to use the LMI tools.");
            return;
        }

        Location blockLocation = event.getBlock().getLocation();
        if (removedBlockDetector) {
            inspectRemovedBlockAsync(player, blockLocation);
            return;
        }

        if (blockHistoryTool) {
            inspectFullHistoryAsync(player, blockLocation);
            return;
        }

        inspectBlockAsync(player, blockLocation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (wandToolService.isWandItem(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "The LMI wand is too precious to be placed as a block.");
            return;
        }

        if (!wandToolService.isRemovedBlockDetector(event.getItemInHand())) {
            return;
        }

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use the removed block detector.");
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            player.sendMessage(ChatColor.RED + "You must be in creative mode to use the removed block detector.");
            return;
        }

        inspectRemovedBlockAsync(player, event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack itemStack = event.getItemDrop().getItemStack();
        if (!wandToolService.isManagedTool(itemStack)) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "These LMI tools are too powerful to be left lying around.");
    }

    private void inspectBlockAsync(Player player, Location blockLocation) {
        storageService.submitReadTask(() -> {
            try {
                List<BlockHistoryEntry> history = storageService.getBlockHistory(blockLocation);
                plugin.getServer().getScheduler().runTask(plugin, () -> sendLatestEntryResult(player, history));
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Unable to inspect block history with the LMI wand.", exception);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "The block could not be inspected. Check the console for details."));
            }
        });
    }

    private void inspectRemovedBlockAsync(Player player, Location blockLocation) {
        storageService.submitReadTask(() -> {
            try {
                List<BlockHistoryEntry> history = storageService.getBlockHistory(blockLocation);
                plugin.getServer().getScheduler().runTask(plugin, () -> sendLatestRemovedEntryResult(player, history));
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Unable to inspect removed block history with the removed block detector.", exception);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "The block could not be inspected. Check the console for details."));
            }
        });
    }

    private void inspectFullHistoryAsync(Player player, Location blockLocation) {
        storageService.submitReadTask(() -> {
            try {
                List<BlockHistoryEntry> history = storageService.getBlockHistory(blockLocation);
                plugin.getServer().getScheduler().runTask(plugin, () -> sendFullHistoryResult(player, history));
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Unable to inspect full block history with the block history tool.", exception);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(ChatColor.RED + "The block history could not be inspected. Check the console for details."));
            }
        });
    }

    private void sendLatestEntryResult(Player player, List<BlockHistoryEntry> history) {
        if (history.isEmpty()) {
            player.sendMessage(ChatColor.GRAY.toString() + ChatColor.ITALIC + "No information was found");
            return;
        }

        BlockHistoryEntry latestEntry = history.get(history.size() - 1);
        sendResultLine(player, latestEntry);
    }

    private void sendLatestRemovedEntryResult(Player player, List<BlockHistoryEntry> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            BlockHistoryEntry historyEntry = history.get(index);
            if (historyEntry.action() == BlockHistoryAction.REMOVE) {
                sendResultLine(player, historyEntry);
                return;
            }
        }

        player.sendMessage(ChatColor.GRAY.toString() + ChatColor.ITALIC + "No information was found");
    }

    private void sendFullHistoryResult(Player player, List<BlockHistoryEntry> history) {
        if (history.isEmpty()) {
            player.sendMessage(ChatColor.GRAY.toString() + ChatColor.ITALIC + "No information was found");
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Block history:");
        for (BlockHistoryEntry historyEntry : history) {
            player.sendMessage(
                    ChatColor.GOLD + formatTimestamp(historyEntry.timestampEpochMillisUtc())
                            + " "
                            + ChatColor.YELLOW + resolveActorName(historyEntry.actorUuid())
                            + " "
                            + ChatColor.GRAY + actionLabel(historyEntry.action())
            );
        }
    }

    private void sendResultLine(Player player, BlockHistoryEntry entry) {
        player.sendMessage(
                ChatColor.GOLD + formatTimestamp(entry.timestampEpochMillisUtc())
                        + " "
                        + ChatColor.YELLOW + resolveActorName(entry.actorUuid())
        );
    }

    private String resolveActorName(UUID actorUuid) {
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(actorUuid);
        String playerName = offlinePlayer != null ? offlinePlayer.getName() : null;
        return playerName != null ? playerName : actorUuid.toString().toLowerCase(Locale.ROOT);
    }

    private String formatTimestamp(long timestampEpochMillisUtc) {
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampEpochMillisUtc));
    }

    private String actionLabel(BlockHistoryAction action) {
        return switch (action) {
            case PLACE_OR_REPLACE -> "placed or replaced";
            case REMOVE -> "removed";
            case STATE_UPDATE -> "updated";
        };
    }
}
