package com.libelulamapinspector.command;

import com.libelulamapinspector.LibelulaMapInspectorPlugin;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.undo.UndoRequest;
import com.libelulamapinspector.undo.UndoScope;
import com.libelulamapinspector.undo.UndoService;
import com.libelulamapinspector.wand.WandToolService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Handles the base /lmi command.
 */
public final class LibelulaMapInspectorCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "libelulamapinspector.admin";
    private static final int DEFAULT_RADIUS = 10;

    private final LibelulaMapInspectorPlugin plugin;
    private final StorageService storageService;
    private final ResetConfirmationService resetConfirmationService;
    private final WandToolService wandToolService;
    private final UndoService undoService;

    public LibelulaMapInspectorCommand(
            LibelulaMapInspectorPlugin plugin,
            StorageService storageService,
            ResetConfirmationService resetConfirmationService,
            WandToolService wandToolService,
            UndoService undoService
    ) {
        this.plugin = plugin;
        this.storageService = storageService;
        this.resetConfirmationService = resetConfirmationService;
        this.wandToolService = wandToolService;
        this.undoService = undoService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage LibelulaMapInspector.");
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender, label);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "wand" -> handleWand(sender);
            case "discover" -> handleDiscover(sender, args);
            case "undo" -> handleUndo(sender, args);
            case "clear-db" -> handleClearDatabase(sender);
            case "confirm" -> handleConfirm(sender);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
                sender.sendMessage(ChatColor.YELLOW + "Use /" + label + " to see the available subcommands.");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }

        if (args.length == 1) {
            return availableTabCompleteSubcommands(sender).stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && "discover".equalsIgnoreCase(args[0])) {
            return Stream.of(String.valueOf(getDefaultRadius()), "20", "30")
                    .filter(value -> value.startsWith(args[1]))
                    .toList();
        }

        if (args.length == 2 && "undo".equalsIgnoreCase(args[0])) {
            return Arrays.stream(plugin.getServer().getOfflinePlayers())
                    .map(OfflinePlayer::getName)
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 3 && "undo".equalsIgnoreCase(args[0])) {
            return Stream.of(String.valueOf(getDefaultRadius()), "20", "30", "world")
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }

    private void sendStatus(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "LibelulaMapInspector");
        sender.sendMessage(ChatColor.YELLOW + "Index: " + ChatColor.WHITE + "ready");
        sender.sendMessage(ChatColor.YELLOW + "Index memory budget: " + ChatColor.WHITE + storageService.getActiveMegabytes() + " MiB");
        sender.sendMessage(ChatColor.YELLOW + "Chunk write buffer budget: " + ChatColor.WHITE + storageService.getBufferMegabytes() + " MiB");
        sender.sendMessage(ChatColor.YELLOW + "False positive disk access rate: " + ChatColor.WHITE + storageService.getFormattedFalsePositiveRate());
        sender.sendMessage(ChatColor.YELLOW + "Expected indexed positions: " + ChatColor.WHITE + storageService.getExpectedInsertions());
        sender.sendMessage(ChatColor.YELLOW + "Persist interval: " + ChatColor.WHITE + storageService.getPersistIntervalMinutes() + " minute(s)");
        sender.sendMessage(ChatColor.YELLOW + "Buffered history bytes: " + ChatColor.WHITE + storageService.getBufferedBytes());
        sender.sendMessage(ChatColor.YELLOW + "Pending flush windows: " + ChatColor.WHITE + storageService.getPendingSnapshotCount());
        sender.sendMessage(ChatColor.YELLOW + "Recorded events: " + ChatColor.WHITE + storageService.getRecordedEventCount());
        sender.sendMessage(ChatColor.YELLOW + "Chunk disk usage: " + ChatColor.WHITE + storageService.getChunkDiskBytes() + " bytes");
        sender.sendMessage(ChatColor.YELLOW + "Chunk disk limit: " + ChatColor.WHITE + storageService.getMaxChunkDiskMegabytes() + " MiB");
        sender.sendMessage(ChatColor.YELLOW + "Chunk retention: " + ChatColor.WHITE + storageService.getMaxChunkRecordAgeDays() + " day(s)");
        sender.sendMessage(ChatColor.YELLOW + "Default command radius: " + ChatColor.WHITE + getDefaultRadius());
        sender.sendMessage(ChatColor.YELLOW + "Undo blocks per tick: " + ChatColor.WHITE + undoService.getConfiguredUndoBlocksPerTick());
        for (String usage : availableUsages(sender, label)) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: " + ChatColor.WHITE + usage);
        }
    }

    private boolean handleWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can receive the LMI tools.");
            return true;
        }

        WandToolService.ArrangeResult arrangeResult = wandToolService.arrangeTools(player);
        return switch (arrangeResult) {
            case ARRANGED -> {
                player.sendMessage(ChatColor.GREEN + "The LMI tools were arranged in slots 1, 2 and 3.");
                yield true;
            }
            case ALREADY_ARRANGED -> {
                player.sendMessage(ChatColor.YELLOW + "The LMI tools are already in slots 1, 2 and 3.");
                yield true;
            }
            case INVENTORY_FULL -> {
                player.sendMessage(ChatColor.RED + "Free enough inventory space before requesting the LMI tools.");
                yield true;
            }
        };
    }

    private boolean handleClearDatabase(CommandSender sender) {
        resetConfirmationService.requestClearDatabaseConfirmation(sender);
        sender.sendMessage(ChatColor.DARK_RED + "Warning: this will permanently delete all stored LibelulaMapInspector history.");
        sender.sendMessage(ChatColor.RED + "Run /lmi confirm to continue.");
        sender.sendMessage(ChatColor.GRAY + "Running any other command will cancel this confirmation.");
        return true;
    }

    private boolean handleDiscover(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /lmi discover.");
            return true;
        }

        if (args.length > 2) {
            player.sendMessage(ChatColor.RED + "Usage: /lmi discover [radius]");
            return true;
        }

        Integer parsedRadius = args.length == 2 ? parseRadius(args[1], player) : getDefaultRadius();
        if (parsedRadius == null) {
            return true;
        }

        World world = player.getWorld();
        Location location = player.getLocation();
        if (world == null || location.getWorld() == null) {
            player.sendMessage(ChatColor.RED + "Your current world could not be resolved.");
            return true;
        }

        BoundingBox boundingBox = new BoundingBox(
                location.getBlockX() - parsedRadius,
                location.getBlockY() - parsedRadius,
                location.getBlockZ() - parsedRadius,
                location.getBlockX() + parsedRadius,
                location.getBlockY() + parsedRadius,
                location.getBlockZ() + parsedRadius
        );

        player.sendMessage(ChatColor.YELLOW + "Discovering players in this area...");
        storageService.submitReadTask(() -> discoverAsync(player, world, boundingBox));
        return true;
    }

    private boolean handleUndo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /lmi undo.");
            return true;
        }

        if (args.length < 2 || args.length > 3) {
            player.sendMessage(ChatColor.RED + "Usage: /lmi undo <player> [radius|world]");
            return true;
        }

        OfflinePlayer targetPlayer = resolveTargetPlayer(args[1]);
        if (targetPlayer == null || targetPlayer.getName() == null) {
            player.sendMessage(ChatColor.RED + "That player could not be resolved.");
            return true;
        }

        UndoScope undoScope;
        if (args.length == 2) {
            undoScope = createRadiusScope(player, getDefaultRadius());
        } else if ("world".equalsIgnoreCase(args[2])) {
            undoScope = UndoScope.world(player.getWorld().getUID(), player.getWorld().getName());
        } else {
            Integer parsedRadius = parseRadius(args[2], player);
            if (parsedRadius == null) {
                return true;
            }
            undoScope = createRadiusScope(player, parsedRadius);
        }

        UndoRequest undoRequest = new UndoRequest(
                targetPlayer.getUniqueId(),
                targetPlayer.getName(),
                undoScope,
                undoService.getConfiguredUndoBlocksPerTick()
        );
        resetConfirmationService.requestUndoConfirmation(sender, undoRequest);

        if (undoScope.isWorldScope()) {
            sender.sendMessage(ChatColor.DARK_RED + "This will remove the changes made by " + targetPlayer.getName() + " in the current world. This cannot be undone. Are you sure?");
        } else {
            sender.sendMessage(ChatColor.DARK_RED + "This will remove the changes made by " + targetPlayer.getName() + " in this " + undoScope.radius() + "-block area. This cannot be undone. Are you sure?");
        }
        sender.sendMessage(ChatColor.RED + "Run /lmi confirm to continue.");
        sender.sendMessage(ChatColor.GRAY + "Running any other command will cancel this confirmation.");
        return true;
    }

    private boolean handleConfirm(CommandSender sender) {
        ResetConfirmationService.PendingConfirmation pendingConfirmation = resetConfirmationService.consumeConfirmation(sender);
        if (pendingConfirmation == null) {
            sender.sendMessage(ChatColor.RED + "There is nothing to confirm.");
            return true;
        }

        return switch (pendingConfirmation.type()) {
            case CLEAR_DATABASE -> handleConfirmedClearDatabase(sender);
            case UNDO -> handleConfirmedUndo(sender, pendingConfirmation.undoRequest());
        };
    }

    private boolean handleConfirmedClearDatabase(CommandSender sender) {
        if (storageService.isResetInProgress()) {
            sender.sendMessage(ChatColor.RED + "A database reset is already in progress.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Clearing LibelulaMapInspector storage...");
        storageService.submitMaintenanceTask(() -> clearDatabaseAsync(sender));
        return true;
    }

    private boolean handleConfirmedUndo(CommandSender sender, UndoRequest undoRequest) {
        if (undoService.isUndoInProgress()) {
            sender.sendMessage(ChatColor.RED + "Another undo operation is already running.");
            return true;
        }

        undoService.startUndo(sender, undoRequest);
        return true;
    }

    private void clearDatabaseAsync(CommandSender sender) {
        try {
            storageService.clearStorageAndReinitialize();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.GREEN + "The LibelulaMapInspector storage was cleared and recreated successfully.");
                sender.sendMessage(ChatColor.YELLOW + "The index and chunk storage now use the current configuration from config.yml.");
            });
        } catch (Exception exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to clear the LibelulaMapInspector storage.", exception);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ChatColor.RED + "The storage could not be cleared. Check the console for details."));
        }
    }

    private List<String> availableSubcommands(CommandSender sender) {
        Stream<String> commands = Stream.of("wand", "discover", "undo", "clear-db");
        if (resetConfirmationService.hasPendingConfirmation(sender)) {
            commands = Stream.concat(commands, Stream.of("confirm"));
        }

        return commands.toList();
    }

    private List<String> availableTabCompleteSubcommands(CommandSender sender) {
        Stream<String> commands = Stream.of("wand", "discover", "undo");
        if (resetConfirmationService.hasPendingConfirmation(sender)) {
            commands = Stream.concat(commands, Stream.of("confirm"));
        }

        return commands.toList();
    }

    private List<String> availableUsages(CommandSender sender, String label) {
        Stream<String> usages = Stream.of(
                "/" + label + " wand",
                "/" + label + " discover [radius]",
                "/" + label + " undo <player> [radius|world]",
                "/" + label + " clear-db"
        );
        if (resetConfirmationService.hasPendingConfirmation(sender)) {
            usages = Stream.concat(usages, Stream.of("/" + label + " confirm"));
        }

        return usages.toList();
    }

    private void discoverAsync(Player player, World world, BoundingBox boundingBox) {
        try {
            List<String> actorNames = storageService.getActorNamesInBox(world, boundingBox);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String message = actorNames.isEmpty() ? "nobody" : String.join(", ", actorNames);
                player.sendMessage(ChatColor.YELLOW + "Players who have edited this area: " + ChatColor.WHITE + message);
            });
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to discover area editors.", exception);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.sendMessage(ChatColor.RED + "The area could not be inspected. Check the console for details."));
        }
    }

    private Integer parseRadius(String rawValue, CommandSender sender) {
        int radius;
        try {
            radius = Integer.parseInt(rawValue);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Radius must be a whole number or the literal word 'world'.");
            return null;
        }

        if (radius < 0) {
            sender.sendMessage(ChatColor.RED + "Radius must be zero or greater.");
            return null;
        }

        return radius;
    }

    private int getDefaultRadius() {
        int configuredRadius = plugin.getConfig().getInt("commands.default-radius", DEFAULT_RADIUS);
        return configuredRadius > 0 ? configuredRadius : DEFAULT_RADIUS;
    }

    private UndoScope createRadiusScope(Player player, int radius) {
        Location location = player.getLocation();
        World world = player.getWorld();
        return UndoScope.radius(
                world.getUID(),
                world.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ(),
                radius
        );
    }

    private OfflinePlayer resolveTargetPlayer(String rawPlayerName) {
        Player onlinePlayer = plugin.getServer().getPlayerExact(rawPlayerName);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }

        for (OfflinePlayer offlinePlayer : plugin.getServer().getOfflinePlayers()) {
            if (offlinePlayer.getName() != null && offlinePlayer.getName().equalsIgnoreCase(rawPlayerName)) {
                return offlinePlayer;
            }
        }

        return null;
    }
}
