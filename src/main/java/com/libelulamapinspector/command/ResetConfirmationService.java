package com.libelulamapinspector.command;

import com.libelulamapinspector.undo.UndoRequest;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks destructive command confirmations per sender.
 */
public final class ResetConfirmationService {

    private final Map<String, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

    public void requestClearDatabaseConfirmation(CommandSender sender) {
        pendingConfirmations.put(senderKey(sender), PendingConfirmation.clearDatabase());
    }

    public void requestUndoConfirmation(CommandSender sender, UndoRequest undoRequest) {
        pendingConfirmations.put(senderKey(sender), PendingConfirmation.undo(undoRequest));
    }

    public PendingConfirmation consumeConfirmation(CommandSender sender) {
        return pendingConfirmations.remove(senderKey(sender));
    }

    public boolean cancelConfirmation(CommandSender sender) {
        return pendingConfirmations.remove(senderKey(sender)) != null;
    }

    public boolean hasPendingConfirmation(CommandSender sender) {
        return pendingConfirmations.containsKey(senderKey(sender));
    }

    public PendingConfirmation getPendingConfirmation(CommandSender sender) {
        return pendingConfirmations.get(senderKey(sender));
    }

    private String senderKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return "player:" + player.getUniqueId();
        }

        if (sender instanceof ConsoleCommandSender) {
            return "console";
        }

        return "sender:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    public record PendingConfirmation(Type type, UndoRequest undoRequest) {

        public PendingConfirmation {
            Objects.requireNonNull(type, "type");
        }

        public static PendingConfirmation clearDatabase() {
            return new PendingConfirmation(Type.CLEAR_DATABASE, null);
        }

        public static PendingConfirmation undo(UndoRequest undoRequest) {
            return new PendingConfirmation(Type.UNDO, Objects.requireNonNull(undoRequest, "undoRequest"));
        }

        public enum Type {
            CLEAR_DATABASE,
            UNDO
        }
    }
}
