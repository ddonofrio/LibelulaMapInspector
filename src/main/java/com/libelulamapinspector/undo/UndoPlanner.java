package com.libelulamapinspector.undo;

import com.libelulamapinspector.index.BlockPositionKey;
import com.libelulamapinspector.storage.BlockHistoryAction;
import com.libelulamapinspector.storage.BlockHistoryEntry;
import com.libelulamapinspector.storage.ContainerBlockSnapshot;
import com.libelulamapinspector.storage.ContainerSlotSnapshot;
import com.libelulamapinspector.storage.SignBlockSnapshot;
import com.libelulamapinspector.storage.SpecialBlockSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts raw per-block history into a deterministic undo plan.
 */
public final class UndoPlanner {

    public UndoPlan createPlan(UUID targetPlayerUuid, long cutoffTimestampEpochMillisUtc, Map<BlockPositionKey, List<BlockHistoryEntry>> historiesByBlock) {
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid");
        Objects.requireNonNull(historiesByBlock, "historiesByBlock");

        List<UndoWorldChange> worldChanges = new ArrayList<>();
        long removedHistoryEntries = 0L;
        int affectedBlocks = 0;

        for (Map.Entry<BlockPositionKey, List<BlockHistoryEntry>> entry : historiesByBlock.entrySet()) {
            List<BlockHistoryEntry> originalHistory = List.copyOf(entry.getValue());
            if (originalHistory.isEmpty()) {
                continue;
            }

            BlockHistoryEntry originalLatest = originalHistory.get(originalHistory.size() - 1);
            List<BlockHistoryEntry> rewrittenHistory = new ArrayList<>(originalHistory.size());
            long removedFromBlock = 0L;
            for (BlockHistoryEntry historyEntry : originalHistory) {
                if (historyEntry.actorUuid().equals(targetPlayerUuid) && historyEntry.timestampEpochMillisUtc() <= cutoffTimestampEpochMillisUtc) {
                    removedFromBlock++;
                    continue;
                }
                rewrittenHistory.add(historyEntry);
            }

            if (removedFromBlock == 0L) {
                continue;
            }

            removedHistoryEntries += removedFromBlock;
            affectedBlocks++;

            UndoResolvedState beforeState = resolveVisibleState(originalHistory, originalLatest);
            UndoResolvedState afterState = resolveVisibleState(rewrittenHistory, originalLatest);

            if (!sameVisibleState(beforeState, afterState)) {
                worldChanges.add(new UndoWorldChange(entry.getKey(), afterState));
            }
        }

        return new UndoPlan(worldChanges, removedHistoryEntries, affectedBlocks);
    }

    private UndoResolvedState resolveVisibleState(List<BlockHistoryEntry> history, BlockHistoryEntry originalLatest) {
        if (!history.isEmpty()) {
            return resolveVisibleState(history.get(history.size() - 1));
        }

        return fallbackVisibleState(originalLatest);
    }

    private UndoResolvedState resolveVisibleState(BlockHistoryEntry historyEntry) {
        if (historyEntry.action() == BlockHistoryAction.REMOVE) {
            return UndoResolvedState.air();
        }

        return UndoResolvedState.blockState(historyEntry.afterBlockDataString(), historyEntry.resultSnapshot());
    }

    private UndoResolvedState fallbackVisibleState(BlockHistoryEntry originalLatest) {
        return switch (originalLatest.action()) {
            case PLACE_OR_REPLACE -> UndoResolvedState.air();
            case REMOVE, STATE_UPDATE -> resolveVisibleState(originalLatest);
        };
    }

    private boolean sameVisibleState(UndoResolvedState left, UndoResolvedState right) {
        if (left.kind() != right.kind()) {
            return false;
        }

        if (!Objects.equals(left.blockDataString(), right.blockDataString())) {
            return false;
        }

        return sameSnapshot(left.snapshot(), right.snapshot());
    }

    private boolean sameSnapshot(SpecialBlockSnapshot left, SpecialBlockSnapshot right) {
        if (left == right) {
            return true;
        }

        if (left == null || right == null || left.typeId() != right.typeId()) {
            return false;
        }

        if (left instanceof SignBlockSnapshot leftSign && right instanceof SignBlockSnapshot rightSign) {
            return Arrays.equals(leftSign.frontLines(), rightSign.frontLines())
                    && Arrays.equals(leftSign.backLines(), rightSign.backLines())
                    && Objects.equals(leftSign.frontColorName(), rightSign.frontColorName())
                    && Objects.equals(leftSign.backColorName(), rightSign.backColorName())
                    && leftSign.frontGlowingText() == rightSign.frontGlowingText()
                    && leftSign.backGlowingText() == rightSign.backGlowingText()
                    && leftSign.waxed() == rightSign.waxed();
        }

        if (left instanceof ContainerBlockSnapshot leftContainer && right instanceof ContainerBlockSnapshot rightContainer) {
            return leftContainer.isDoubleChest() == rightContainer.isDoubleChest()
                    && leftContainer.partnerOffsetX() == rightContainer.partnerOffsetX()
                    && leftContainer.partnerOffsetY() == rightContainer.partnerOffsetY()
                    && leftContainer.partnerOffsetZ() == rightContainer.partnerOffsetZ()
                    && leftContainer.inventorySize() == rightContainer.inventorySize()
                    && sameContainerSlots(leftContainer.slots(), rightContainer.slots());
        }

        return false;
    }

    private boolean sameContainerSlots(List<ContainerSlotSnapshot> leftSlots, List<ContainerSlotSnapshot> rightSlots) {
        if (leftSlots.size() != rightSlots.size()) {
            return false;
        }

        for (int index = 0; index < leftSlots.size(); index++) {
            ContainerSlotSnapshot leftSlot = leftSlots.get(index);
            ContainerSlotSnapshot rightSlot = rightSlots.get(index);
            if (leftSlot.slotIndex() != rightSlot.slotIndex()) {
                return false;
            }
            if (!Arrays.equals(leftSlot.serializedItemBytes(), rightSlot.serializedItemBytes())) {
                return false;
            }
        }
        return true;
    }
}
