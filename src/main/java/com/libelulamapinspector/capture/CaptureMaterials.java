package com.libelulamapinspector.capture;

import org.bukkit.Material;

/**
 * Shared material helpers for event capture.
 */
public final class CaptureMaterials {

    private CaptureMaterials() {
    }

    public static Material fluidMaterialFromBucket(Material bucketMaterial) {
        return switch (bucketMaterial) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            default -> null;
        };
    }

    public static boolean isTrackableFluid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    public static int fluidPriority(Material material) {
        if (material == Material.WATER) {
            return 2;
        }
        if (material == Material.LAVA) {
            return 1;
        }
        return 0;
    }
}
