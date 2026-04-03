package com.libelulamapinspector.storage;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Stores a non-empty inventory slot.
 */
public record ContainerSlotSnapshot(int slotIndex, byte[] serializedItemBytes) {

    public int estimatedBytes() {
        return 16 + serializedItemBytes.length;
    }

    public void writeTo(DataOutputStream output) throws IOException {
        output.writeInt(slotIndex);
        StorageBinaryIO.writeByteArray(output, serializedItemBytes);
    }
}
