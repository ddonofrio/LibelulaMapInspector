package com.libelulamapinspector.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StorageBinaryIOTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsBinaryHelpers() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        UUID uuid = UUID.randomUUID();
        byte[] raw = new byte[]{1, 2, 3, 4, 5};

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            StorageBinaryIO.writeString(output, "hello");
            StorageBinaryIO.writeNullableString(output, null);
            StorageBinaryIO.writeNullableString(output, "world");
            StorageBinaryIO.writeUuid(output, uuid);
            StorageBinaryIO.writeByteArray(output, raw);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("hello", StorageBinaryIO.readString(input));
            assertEquals(null, StorageBinaryIO.readNullableString(input));
            assertEquals("world", StorageBinaryIO.readNullableString(input));
            assertEquals(uuid, StorageBinaryIO.readUuid(input));
            assertArrayEquals(raw, StorageBinaryIO.readByteArray(input));
        }
    }

    @Test
    void deletesDirectoryTreesRecursively() throws IOException {
        Path root = tempDir.resolve("root");
        Path nested = root.resolve("a").resolve("b");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("file.txt"), "content");

        StorageBinaryIO.deleteRecursively(root);

        assertFalse(Files.exists(root));
    }
}
