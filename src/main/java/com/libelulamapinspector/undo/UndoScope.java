package com.libelulamapinspector.undo;

import com.libelulamapinspector.index.BlockPositionKey;

import java.util.Objects;
import java.util.UUID;

/**
 * Describes the selected undo scope in a single world.
 */
public final class UndoScope {

    public enum Type {
        RADIUS,
        WORLD
    }

    private final UUID worldUuid;
    private final String worldName;
    private final Type type;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int radius;

    private UndoScope(
            UUID worldUuid,
            String worldName,
            Type type,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int radius
    ) {
        this.worldUuid = Objects.requireNonNull(worldUuid, "worldUuid");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.type = Objects.requireNonNull(type, "type");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.radius = radius;
    }

    public static UndoScope radius(UUID worldUuid, String worldName, int centerX, int centerY, int centerZ, int radius) {
        int normalizedRadius = Math.max(0, radius);
        return new UndoScope(
                worldUuid,
                worldName,
                Type.RADIUS,
                centerX - normalizedRadius,
                centerY - normalizedRadius,
                centerZ - normalizedRadius,
                centerX + normalizedRadius,
                centerY + normalizedRadius,
                centerZ + normalizedRadius,
                normalizedRadius
        );
    }

    public static UndoScope world(UUID worldUuid, String worldName) {
        return new UndoScope(
                worldUuid,
                worldName,
                Type.WORLD,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                -1
        );
    }

    public UUID worldUuid() {
        return worldUuid;
    }

    public String worldName() {
        return worldName;
    }

    public Type type() {
        return type;
    }

    public boolean isWorldScope() {
        return type == Type.WORLD;
    }

    public int radius() {
        return radius;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public boolean contains(BlockPositionKey positionKey) {
        return contains(positionKey.worldUuid(), positionKey.x(), positionKey.y(), positionKey.z());
    }

    public boolean contains(UUID otherWorldUuid, int x, int y, int z) {
        return worldUuid.equals(otherWorldUuid)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public String confirmationDescription() {
        if (isWorldScope()) {
            return "the current world";
        }

        return "this " + radius + "-block area";
    }
}
