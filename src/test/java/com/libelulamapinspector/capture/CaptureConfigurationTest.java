package com.libelulamapinspector.capture;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.libelulamapinspector.capture.CaptureTestSupport.world;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureConfigurationTest {

    @Test
    void readsDefaultCaptureFlagsWhenTheConfigurationIsMissing() {
        CaptureConfiguration configuration = CaptureConfiguration.from(new YamlConfiguration());

        assertTrue(configuration.blocks().blockBreak());
        assertTrue(configuration.blocks().blockPlace());
        assertTrue(configuration.blocks().blockMultiPlace());
        assertTrue(configuration.blocks().signChange());
        assertTrue(configuration.fluids().bucketEmpty());
        assertTrue(configuration.fluids().bucketFill());
        assertFalse(configuration.fluids().fluidGriefTracking());
        assertTrue(configuration.explosions().tntExplosions());
        assertFalse(configuration.isWorldExcluded(world("world", UUID.randomUUID())));
        assertTrue(configuration.shouldRegisterBlockCaptureListener());
        assertFalse(configuration.shouldRegisterFluidGriefListener());
        assertTrue(configuration.shouldRegisterTntCaptureListener());
    }

    @Test
    void readsCustomCaptureFlagsAndRegistrationHints() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("capture.blocks.block-break", false);
        yaml.set("capture.blocks.block-place", false);
        yaml.set("capture.blocks.block-multi-place", false);
        yaml.set("capture.blocks.sign-change", false);
        yaml.set("capture.fluids.bucket-empty", false);
        yaml.set("capture.fluids.bucket-fill", false);
        yaml.set("capture.fluids.fluid-grief-tracking", true);
        yaml.set("capture.explosions.tnt-explosions", false);
        yaml.set("capture.excluded-worlds", java.util.List.of("world_creative", "  EventWorld  "));

        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);

        assertTrue(configuration.isWorldExcluded("WORLD_CREATIVE"));
        assertTrue(configuration.isWorldExcluded(world("eventworld", UUID.randomUUID())));
        assertFalse(configuration.isWorldExcluded("survival"));
        assertFalse(configuration.shouldRegisterBlockCaptureListener());
        assertTrue(configuration.shouldRegisterFluidGriefListener());
        assertFalse(configuration.shouldRegisterTntCaptureListener());
    }
}
