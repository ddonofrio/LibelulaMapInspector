package com.libelulamapinspector.storage;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Stores inventory contents for container blocks that were removed or replaced.
 */
public final class ContainerBlockSnapshot implements SpecialBlockSnapshot {

    private final boolean doubleChest;
    private final int partnerOffsetX;
    private final int partnerOffsetY;
    private final int partnerOffsetZ;
    private final int inventorySize;
    private final List<ContainerSlotSnapshot> slots;

    public ContainerBlockSnapshot(
            boolean doubleChest,
            int partnerOffsetX,
            int partnerOffsetY,
            int partnerOffsetZ,
            int inventorySize,
            List<ContainerSlotSnapshot> slots
    ) {
        this.doubleChest = doubleChest;
        this.partnerOffsetX = partnerOffsetX;
        this.partnerOffsetY = partnerOffsetY;
        this.partnerOffsetZ = partnerOffsetZ;
        this.inventorySize = inventorySize;
        this.slots = List.copyOf(slots);
    }

    @Override
    public byte typeId() {
        return CONTAINER_TYPE;
    }

    @Override
    public int estimatedBytes() {
        int size = 32;
        for (ContainerSlotSnapshot slot : slots) {
            size += slot.estimatedBytes();
        }
        return size;
    }

    @Override
    public void writeTo(DataOutputStream output) throws IOException {
        output.writeBoolean(doubleChest);
        output.writeInt(partnerOffsetX);
        output.writeInt(partnerOffsetY);
        output.writeInt(partnerOffsetZ);
        output.writeInt(inventorySize);
        output.writeInt(slots.size());

        for (ContainerSlotSnapshot slot : slots) {
            slot.writeTo(output);
        }
    }

    public boolean isDoubleChest() {
        return doubleChest;
    }

    public int partnerOffsetX() {
        return partnerOffsetX;
    }

    public int partnerOffsetY() {
        return partnerOffsetY;
    }

    public int partnerOffsetZ() {
        return partnerOffsetZ;
    }

    public int inventorySize() {
        return inventorySize;
    }

    public List<ContainerSlotSnapshot> slots() {
        return slots;
    }
}
