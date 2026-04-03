package com.libelulamapinspector.capture;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.World;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Defines which capture listeners are enabled.
 */
public record CaptureConfiguration(
        Set<String> excludedWorlds,
        Blocks blocks,
        Fluids fluids,
        Explosions explosions
) {

    public static CaptureConfiguration from(FileConfiguration configuration) {
        return new CaptureConfiguration(
                configuration.getStringList("capture.excluded-worlds").stream()
                        .map(CaptureConfiguration::normalizeWorldName)
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toUnmodifiableSet()),
                new Blocks(
                        configuration.getBoolean("capture.blocks.block-break", true),
                        configuration.getBoolean("capture.blocks.block-place", true),
                        configuration.getBoolean("capture.blocks.block-multi-place", true),
                        configuration.getBoolean("capture.blocks.sign-change", true)
                ),
                new Fluids(
                        configuration.getBoolean("capture.fluids.bucket-empty", true),
                        configuration.getBoolean("capture.fluids.bucket-fill", true),
                        configuration.getBoolean("capture.fluids.fluid-grief-tracking", false)
                ),
                new Explosions(
                        configuration.getBoolean("capture.explosions.tnt-explosions", true)
                )
        );
    }

    public boolean isWorldExcluded(World world) {
        return world != null && isWorldExcluded(world.getName());
    }

    public boolean isWorldExcluded(String worldName) {
        return excludedWorlds.contains(normalizeWorldName(worldName));
    }

    public boolean shouldRegisterBlockCaptureListener() {
        return blocks.blockBreak()
                || blocks.blockPlace()
                || blocks.blockMultiPlace()
                || blocks.signChange()
                || fluids.bucketEmpty()
                || fluids.bucketFill();
    }

    public boolean shouldRegisterFluidGriefListener() {
        return fluids.fluidGriefTracking();
    }

    public boolean shouldRegisterTntCaptureListener() {
        return explosions.tntExplosions();
    }

    private static String normalizeWorldName(String worldName) {
        return worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT);
    }

    public record Blocks(
            boolean blockBreak,
            boolean blockPlace,
            boolean blockMultiPlace,
            boolean signChange
    ) {
    }

    public record Fluids(
            boolean bucketEmpty,
            boolean bucketFill,
            boolean fluidGriefTracking
    ) {
    }

    public record Explosions(boolean tntExplosions) {
    }
}
