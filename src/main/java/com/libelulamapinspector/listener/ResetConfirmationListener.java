package com.libelulamapinspector.listener;

import com.libelulamapinspector.command.ResetConfirmationService;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Cancels pending destructive confirmations when the sender runs another command.
 */
public final class ResetConfirmationListener implements Listener {

    private final ResetConfirmationService resetConfirmationService;

    public ResetConfirmationListener(ResetConfirmationService resetConfirmationService) {
        this.resetConfirmationService = resetConfirmationService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        handleCommand(event.getPlayer(), event.getMessage(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        handleCommand(event.getSender(), event.getCommand(), false);
    }

    private void handleCommand(CommandSender sender, String rawCommand, boolean startsWithSlash) {
        ResetConfirmationService.PendingConfirmation pendingConfirmation = resetConfirmationService.getPendingConfirmation(sender);
        if (pendingConfirmation == null) {
            return;
        }

        if (isConfirmCommand(rawCommand, startsWithSlash)) {
            return;
        }

        if (resetConfirmationService.cancelConfirmation(sender)) {
            sender.sendMessage(ChatColor.GRAY + switch (pendingConfirmation.type()) {
                case CLEAR_DATABASE -> "Database clear confirmation cancelled.";
                case UNDO -> "Undo confirmation cancelled.";
            });
        }
    }

    private boolean isConfirmCommand(String rawCommand, boolean startsWithSlash) {
        String normalizedCommand = rawCommand.trim();
        if (startsWithSlash && normalizedCommand.startsWith("/")) {
            normalizedCommand = normalizedCommand.substring(1);
        }

        String[] parts = normalizedCommand.split("\\s+");
        return parts.length == 2
                && parts[0].equalsIgnoreCase("lmi")
                && parts[1].equalsIgnoreCase("confirm");
    }
}
