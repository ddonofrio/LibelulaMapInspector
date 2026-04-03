package com.libelulamapinspector.capture;

import com.libelulamapinspector.listener.BlockCaptureListener;
import com.libelulamapinspector.listener.FluidGriefCaptureListener;
import com.libelulamapinspector.listener.TntCaptureListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers capture listeners according to the current configuration.
 */
public final class CaptureListenerRegistrar {

    private final JavaPlugin plugin;
    private final CaptureConfiguration captureConfiguration;
    private final BlockHistoryCaptureService blockHistoryCaptureService;
    private final FluidAttributionService fluidAttributionService;
    private final TntAttributionService tntAttributionService;

    public CaptureListenerRegistrar(
            JavaPlugin plugin,
            CaptureConfiguration captureConfiguration,
            BlockHistoryCaptureService blockHistoryCaptureService,
            FluidAttributionService fluidAttributionService,
            TntAttributionService tntAttributionService
    ) {
        this.plugin = plugin;
        this.captureConfiguration = captureConfiguration;
        this.blockHistoryCaptureService = blockHistoryCaptureService;
        this.fluidAttributionService = fluidAttributionService;
        this.tntAttributionService = tntAttributionService;
    }

    public void registerListeners() {
        PluginManager pluginManager = plugin.getServer().getPluginManager();

        if (captureConfiguration.shouldRegisterBlockCaptureListener()) {
            pluginManager.registerEvents(new BlockCaptureListener(captureConfiguration, blockHistoryCaptureService, fluidAttributionService), plugin);
        }

        if (captureConfiguration.shouldRegisterFluidGriefListener()) {
            if (fluidAttributionService == null) {
                throw new IllegalStateException("Fluid grief tracking is enabled, but no FluidAttributionService was provided.");
            }

            pluginManager.registerEvents(new FluidGriefCaptureListener(captureConfiguration, blockHistoryCaptureService, fluidAttributionService), plugin);
        }

        if (captureConfiguration.shouldRegisterTntCaptureListener()) {
            if (tntAttributionService == null) {
                throw new IllegalStateException("TNT capture is enabled, but no TntAttributionService was provided.");
            }

            pluginManager.registerEvents(new TntCaptureListener(captureConfiguration, blockHistoryCaptureService, tntAttributionService), plugin);
        }
    }
}
