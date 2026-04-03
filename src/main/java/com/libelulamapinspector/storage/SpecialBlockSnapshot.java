package com.libelulamapinspector.storage;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Represents additional data that cannot be reconstructed from BlockData alone.
 */
public interface SpecialBlockSnapshot {

    byte NONE_TYPE = 0;
    byte SIGN_TYPE = 1;
    byte CONTAINER_TYPE = 2;

    byte typeId();

    int estimatedBytes();

    void writeTo(DataOutputStream output) throws IOException;
}
