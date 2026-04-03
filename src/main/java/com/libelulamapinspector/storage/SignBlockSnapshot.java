package com.libelulamapinspector.storage;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Stores sign text and rendering metadata.
 */
public final class SignBlockSnapshot implements SpecialBlockSnapshot {

    private final String[] frontLines;
    private final String[] backLines;
    private final String frontColorName;
    private final String backColorName;
    private final boolean frontGlowingText;
    private final boolean backGlowingText;
    private final boolean waxed;

    public SignBlockSnapshot(
            String[] frontLines,
            String[] backLines,
            String frontColorName,
            String backColorName,
            boolean frontGlowingText,
            boolean backGlowingText,
            boolean waxed
    ) {
        this.frontLines = normalizeLines(frontLines);
        this.backLines = normalizeLines(backLines);
        this.frontColorName = frontColorName;
        this.backColorName = backColorName;
        this.frontGlowingText = frontGlowingText;
        this.backGlowingText = backGlowingText;
        this.waxed = waxed;
    }

    @Override
    public byte typeId() {
        return SIGN_TYPE;
    }

    @Override
    public int estimatedBytes() {
        int size = 32;
        size += estimatedStringBytes(frontColorName);
        size += estimatedStringBytes(backColorName);

        for (String line : frontLines) {
            size += estimatedStringBytes(line);
        }

        for (String line : backLines) {
            size += estimatedStringBytes(line);
        }

        return size;
    }

    @Override
    public void writeTo(DataOutputStream output) throws IOException {
        output.writeBoolean(frontGlowingText);
        output.writeBoolean(backGlowingText);
        output.writeBoolean(waxed);
        StorageBinaryIO.writeString(output, frontColorName);
        StorageBinaryIO.writeString(output, backColorName);

        for (String line : frontLines) {
            StorageBinaryIO.writeString(output, line);
        }

        for (String line : backLines) {
            StorageBinaryIO.writeString(output, line);
        }
    }

    public String[] frontLines() {
        return Arrays.copyOf(frontLines, frontLines.length);
    }

    public String[] backLines() {
        return Arrays.copyOf(backLines, backLines.length);
    }

    public String frontColorName() {
        return frontColorName;
    }

    public String backColorName() {
        return backColorName;
    }

    public boolean frontGlowingText() {
        return frontGlowingText;
    }

    public boolean backGlowingText() {
        return backGlowingText;
    }

    public boolean waxed() {
        return waxed;
    }

    private static String[] normalizeLines(String[] lines) {
        String[] normalized = new String[4];
        for (int index = 0; index < normalized.length; index++) {
            normalized[index] = lines != null && index < lines.length && lines[index] != null ? lines[index] : "";
        }
        return normalized;
    }

    private static int estimatedStringBytes(String value) {
        return 8 + (value != null ? value.length() * 2 : 0);
    }
}
