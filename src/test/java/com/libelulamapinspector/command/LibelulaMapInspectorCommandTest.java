package com.libelulamapinspector.command;

import com.libelulamapinspector.LibelulaMapInspectorPlugin;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.support.PluginTestSupport;
import com.libelulamapinspector.undo.UndoRequest;
import com.libelulamapinspector.undo.UndoScope;
import com.libelulamapinspector.undo.UndoService;
import com.libelulamapinspector.wand.WandToolService;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibelulaMapInspectorCommandTest {

    @TempDir
    Path tempDir;

    private LibelulaMapInspectorPlugin plugin;
    private StorageService storageService;
    private ResetConfirmationService resetConfirmationService;
    private WandToolService wandToolService;
    private UndoService undoService;
    private LibelulaMapInspectorCommand command;
    private Command bukkitCommand;

    @BeforeEach
    void setUp() throws IOException {
        plugin = PluginTestSupport.mockPlugin(LibelulaMapInspectorPlugin.class, tempDir.resolve("plugin-data"), configuration -> {
            configuration.set("commands.default-radius", 10);
            configuration.set("commands.undo-blocks-per-tick", 10);
        });
        PluginTestSupport.wireImmediateScheduler(plugin);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("LibelulaMapInspectorCommandTest"));

        storageService = mock(StorageService.class);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(storageService).submitReadTask(any(Runnable.class));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(storageService).submitMaintenanceTask(any(Runnable.class));
        resetConfirmationService = new ResetConfirmationService();
        wandToolService = mock(WandToolService.class);
        undoService = mock(UndoService.class);
        when(undoService.getConfiguredUndoBlocksPerTick()).thenReturn(10);
        command = new LibelulaMapInspectorCommand(plugin, storageService, resetConfirmationService, wandToolService, undoService);
        bukkitCommand = new TestCommand("lmi");
    }

    @Test
    void deniesAccessWithoutAdminPermission() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(false);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[0]));

        verify(sender).sendMessage(contains("You do not have permission"));
    }

    @Test
    void clearDbRequestsConfirmationForAdmins() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"clear-db"}));

        assertTrue(resetConfirmationService.hasPendingConfirmation(sender));
        verify(sender).sendMessage(contains("permanently delete"));
    }

    @Test
    void confirmRunsTheResetFlowImmediatelyOnTheTestScheduler() throws Exception {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");
        when(storageService.isResetInProgress()).thenReturn(false);

        command.onCommand(sender, bukkitCommand, "lmi", new String[]{"clear-db"});
        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"confirm"}));

        verify(storageService).clearStorageAndReinitialize();
        assertFalse(resetConfirmationService.hasPendingConfirmation(sender));
        verify(sender).sendMessage(contains("cleared and recreated successfully"));
    }

    @Test
    void confirmWithoutPendingResetShowsAnError() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"confirm"}));

        verify(sender).sendMessage(contains("There is nothing to confirm"));
    }

    @Test
    void wandRequiresAPlayerSender() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"wand"}));

        verify(sender).sendMessage(contains("Only players can receive"));
    }

    @Test
    void wandGivesTheToolToPlayers() {
        Player sender = mock(Player.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(wandToolService.arrangeTools(sender)).thenReturn(WandToolService.ArrangeResult.ARRANGED);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"wand"}));

        verify(sender).sendMessage(contains("slots 1, 2 and 3"));
    }

    @Test
    void discoverRequiresAPlayerSender() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"discover"}));

        verify(sender).sendMessage(contains("Only players can use /lmi discover"));
    }

    @Test
    void discoverUsesTheConfiguredDefaultRadiusAndReportsNames() throws Exception {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        Location location = new Location(world, 100, 64, 200);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getWorld()).thenReturn(world);
        when(sender.getLocation()).thenReturn(location);
        when(storageService.getActorNamesInBox(org.mockito.ArgumentMatchers.eq(world), org.mockito.ArgumentMatchers.any(BoundingBox.class)))
                .thenReturn(List.of("Alex", "Steve"));

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"discover"}));

        ArgumentCaptor<BoundingBox> boxCaptor = ArgumentCaptor.forClass(BoundingBox.class);
        verify(storageService).getActorNamesInBox(org.mockito.ArgumentMatchers.eq(world), boxCaptor.capture());
        BoundingBox box = boxCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(90.0D, box.getMinX());
        org.junit.jupiter.api.Assertions.assertEquals(54.0D, box.getMinY());
        org.junit.jupiter.api.Assertions.assertEquals(190.0D, box.getMinZ());
        org.junit.jupiter.api.Assertions.assertEquals(110.0D, box.getMaxX());
        org.junit.jupiter.api.Assertions.assertEquals(74.0D, box.getMaxY());
        org.junit.jupiter.api.Assertions.assertEquals(210.0D, box.getMaxZ());
        verify(sender).sendMessage(contains("Discovering players in this area"));
        verify(sender).sendMessage(contains("Alex, Steve"));
    }

    @Test
    void discoverAcceptsACustomRadiusAndCanReportNobody() throws Exception {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        Location location = new Location(world, 10, 70, 10);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getWorld()).thenReturn(world);
        when(sender.getLocation()).thenReturn(location);
        when(storageService.getActorNamesInBox(org.mockito.ArgumentMatchers.eq(world), org.mockito.ArgumentMatchers.any(BoundingBox.class)))
                .thenReturn(List.of());

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"discover", "5"}));

        ArgumentCaptor<BoundingBox> boxCaptor = ArgumentCaptor.forClass(BoundingBox.class);
        verify(storageService).getActorNamesInBox(org.mockito.ArgumentMatchers.eq(world), boxCaptor.capture());
        BoundingBox box = boxCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(5.0D, box.getMinX());
        org.junit.jupiter.api.Assertions.assertEquals(65.0D, box.getMinY());
        org.junit.jupiter.api.Assertions.assertEquals(5.0D, box.getMinZ());
        org.junit.jupiter.api.Assertions.assertEquals(15.0D, box.getMaxX());
        org.junit.jupiter.api.Assertions.assertEquals(75.0D, box.getMaxY());
        org.junit.jupiter.api.Assertions.assertEquals(15.0D, box.getMaxZ());
        verify(sender).sendMessage(contains("nobody"));
    }

    @Test
    void undoRequestsConfirmationWithTheConfiguredDefaultRadius() {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        UUID worldUuid = UUID.randomUUID();
        OfflinePlayer targetPlayer = mock(OfflinePlayer.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getWorld()).thenReturn(world);
        when(sender.getLocation()).thenReturn(new Location(world, 50, 60, 70));
        when(world.getUID()).thenReturn(worldUuid);
        when(world.getName()).thenReturn("world");
        when(targetPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(targetPlayer.getName()).thenReturn("Alex");
        when(plugin.getServer().getOfflinePlayers()).thenReturn(new OfflinePlayer[]{targetPlayer});

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"undo", "Alex"}));

        ResetConfirmationService.PendingConfirmation confirmation = resetConfirmationService.getPendingConfirmation(sender);
        assertTrue(confirmation != null && confirmation.type() == ResetConfirmationService.PendingConfirmation.Type.UNDO);
        assertTrue(confirmation.undoRequest().scope().type() == UndoScope.Type.RADIUS);
        org.junit.jupiter.api.Assertions.assertEquals(10, confirmation.undoRequest().scope().radius());
        verify(sender).sendMessage(contains("10-block area"));
    }

    @Test
    void undoWorldRequestsTheStrongerWorldConfirmation() {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        UUID worldUuid = UUID.randomUUID();
        OfflinePlayer targetPlayer = mock(OfflinePlayer.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(worldUuid);
        when(world.getName()).thenReturn("world");
        when(targetPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(targetPlayer.getName()).thenReturn("Alex");
        when(plugin.getServer().getOfflinePlayers()).thenReturn(new OfflinePlayer[]{targetPlayer});

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"undo", "Alex", "world"}));

        verify(sender).sendMessage(contains("current world"));
    }

    @Test
    void confirmStartsUndoWhenAnUndoConfirmationIsPending() {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        UndoRequest undoRequest = new UndoRequest(UUID.randomUUID(), "Alex", UndoScope.world(UUID.randomUUID(), "world"), 10);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        when(sender.getWorld()).thenReturn(world);
        resetConfirmationService.requestUndoConfirmation(sender, undoRequest);
        when(undoService.isUndoInProgress()).thenReturn(false);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"confirm"}));

        verify(undoService).startUndo(sender, undoRequest);
        assertFalse(resetConfirmationService.hasPendingConfirmation(sender));
    }

    @Test
    void statusDoesNotShowConfirmWhenNothingIsPending() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[0]));

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        org.junit.jupiter.api.Assertions.assertTrue(messages.getAllValues().stream().noneMatch(message -> message.contains("/lmi confirm")));
    }

    @Test
    void statusShowsConfirmWhenAResetIsPending() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");
        resetConfirmationService.requestClearDatabaseConfirmation(sender);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[0]));

        verify(sender).sendMessage(contains("/lmi confirm"));
    }

    @Test
    void tabCompleteOnlyShowsConfirmWhenAConfirmationIsPending() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");

        assertIterableEquals(List.of("wand", "discover", "undo"), command.onTabComplete(sender, bukkitCommand, "lmi", new String[]{""}));

        resetConfirmationService.requestClearDatabaseConfirmation(sender);

        assertIterableEquals(List.of("wand", "discover", "undo", "confirm"), command.onTabComplete(sender, bukkitCommand, "lmi", new String[]{""}));
    }

    @Test
    void tabCompleteNeverSuggestsClearDatabaseEvenWhenItsPrefixIsTyped() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getName()).thenReturn("Admin");

        assertTrue(command.onTabComplete(sender, bukkitCommand, "lmi", new String[]{"cl"}).isEmpty());
    }

    @Test
    void unknownUndoTargetFailsWithoutSchedulingAnything() {
        Player sender = mock(Player.class);
        World world = mock(World.class);
        when(sender.hasPermission("libelulamapinspector.admin")).thenReturn(true);
        when(sender.getWorld()).thenReturn(world);
        when(plugin.getServer().getOfflinePlayers()).thenReturn(new OfflinePlayer[0]);

        assertTrue(command.onCommand(sender, bukkitCommand, "lmi", new String[]{"undo", "MissingPlayer"}));

        verify(sender).sendMessage(contains("could not be resolved"));
        verify(undoService, never()).startUndo(any(), any());
    }

    private static final class TestCommand extends Command {

        private TestCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return false;
        }
    }
}
