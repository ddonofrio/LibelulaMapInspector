package com.libelulamapinspector.index;

/**
 * Describes the persisted index shape that must remain stable across restarts.
 */
public record IndexShapeConfiguration(int megabytes, double falsePositiveRate) {

    public long bytes() {
        return megabytes * 1024L * 1024L;
    }

    public long expectedInsertions() {
        double totalBits = bytes() * 8.0D;
        double expected = totalBits * (Math.log(2) * Math.log(2)) / -Math.log(falsePositiveRate);
        return Math.max(1L, (long) Math.floor(expected));
    }

    public boolean matches(IndexShapeConfiguration other) {
        return megabytes == other.megabytes
                && Double.compare(falsePositiveRate, other.falsePositiveRate) == 0;
    }
}
