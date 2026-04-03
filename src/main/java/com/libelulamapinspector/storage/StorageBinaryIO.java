package com.libelulamapinspector.storage;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * Shared binary helpers for LibelulaMapInspector storage files.
 */
final class StorageBinaryIO {

    private StorageBinaryIO() {
    }

    static void writeString(DataOutput output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInput input) throws IOException {
        int length = input.readInt();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeNullableString(DataOutput output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    static String readNullableString(DataInput input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    static void writeUuid(DataOutput output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    static UUID readUuid(DataInput input) throws IOException {
        long mostSignificantBits = input.readLong();
        long leastSignificantBits = input.readLong();
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    static void writeByteArray(DataOutput output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    static byte[] readByteArray(DataInput input) throws IOException {
        int length = input.readInt();
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }

            throw exception;
        }
    }
}
