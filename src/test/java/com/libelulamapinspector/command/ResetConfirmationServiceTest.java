package com.libelulamapinspector.command;

import com.libelulamapinspector.undo.UndoRequest;
import com.libelulamapinspector.undo.UndoScope;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResetConfirmationServiceTest {

    @Test
    void tracksAndConsumesPendingClearDatabaseConfirmationsPerPlayer() {
        ResetConfirmationService service = new ResetConfirmationService();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        service.requestClearDatabaseConfirmation(player);

        assertTrue(service.hasPendingConfirmation(player));
        ResetConfirmationService.PendingConfirmation confirmation = service.consumeConfirmation(player);
        assertNotNull(confirmation);
        assertEquals(ResetConfirmationService.PendingConfirmation.Type.CLEAR_DATABASE, confirmation.type());
        assertFalse(service.hasPendingConfirmation(player));
    }

    @Test
    void tracksAndConsumesPendingUndoConfirmations() {
        ResetConfirmationService service = new ResetConfirmationService();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        UndoRequest undoRequest = new UndoRequest(
                UUID.randomUUID(),
                "Alex",
                UndoScope.world(UUID.randomUUID(), "world"),
                10
        );

        service.requestUndoConfirmation(player, undoRequest);

        ResetConfirmationService.PendingConfirmation confirmation = service.consumeConfirmation(player);
        assertNotNull(confirmation);
        assertEquals(ResetConfirmationService.PendingConfirmation.Type.UNDO, confirmation.type());
        assertEquals(undoRequest, confirmation.undoRequest());
    }

    @Test
    void cancelsPendingConfirmationsForGenericSenders() {
        ResetConfirmationService service = new ResetConfirmationService();
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("ConsoleProxy");

        service.requestClearDatabaseConfirmation(sender);

        assertTrue(service.cancelConfirmation(sender));
        assertFalse(service.hasPendingConfirmation(sender));
    }
}
