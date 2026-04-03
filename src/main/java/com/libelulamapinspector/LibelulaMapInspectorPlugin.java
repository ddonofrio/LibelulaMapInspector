package com.libelulamapinspector;

import com.libelulamapinspector.capture.BlockHistoryCaptureService;
import com.libelulamapinspector.capture.CaptureConfiguration;
import com.libelulamapinspector.capture.CaptureListenerRegistrar;
import com.libelulamapinspector.capture.FluidAttributionService;
import com.libelulamapinspector.capture.TntAttributionService;
import com.libelulamapinspector.command.LibelulaMapInspectorCommand;
import com.libelulamapinspector.command.ResetConfirmationService;
import com.libelulamapinspector.listener.ResetConfirmationListener;
import com.libelulamapinspector.listener.WandToolListener;
import com.libelulamapinspector.storage.StorageService;
import com.libelulamapinspector.undo.UndoService;
import com.libelulamapinspector.wand.WandToolService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Locale;

/**
 * Main entry point for the LibelulaMapInspector plugin.
 */
public final class LibelulaMapInspectorPlugin extends JavaPlugin {

    private static final String STARTUP_LOGO = String.join(System.lineSeparator(),
            " __         _           _         __",
            "( '\\___      \\_  (^)  _/      ___/' )",
            " \\ , ' \\____   \\ / \\ /   ____/ ' , /",
            "  \\__ ' , ' \\___{~V~}___/ ' , ' __/",
            " ____\\_________ {<!>} _________/____",
            "/ , ' , ' , ' ,`{<!>}~, ' , ' , ' , \\",
            "\\_____________ /{<!>}\\______________/",
            "                \\.../",
            "                 (0)",
            "                 (0)",
            "                 (0)           LibelulaMapInspector Plugin",
            "                 (0)           This plugin is part of Libelula Server",
            "                 (0)           Lightweight Minecraft server software",
            "                 (0)           (2026) Libelula Server Team",
            "                  0",
            "                  \""
    );

    private StorageService storageService;
    private ResetConfirmationService resetConfirmationService;
    private BlockHistoryCaptureService blockHistoryCaptureService;
    private CaptureConfiguration captureConfiguration;
    private FluidAttributionService fluidAttributionService;
    private TntAttributionService tntAttributionService;
    private WandToolService wandToolService;
    private UndoService undoService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        resetConfirmationService = new ResetConfirmationService();
        storageService = new StorageService(this);
        captureConfiguration = CaptureConfiguration.from(getConfig());
        wandToolService = new WandToolService();
        undoService = new UndoService(this, storageService);
        initializeStorageService();
        initializeCaptureServices();
        registerCommands();
        registerListeners();
        logStartupLogo();
        logStartupPanel();
        getLogger().info("LibelulaMapInspector has been enabled.");
    }

    @Override
    public void onDisable() {
        if (storageService != null) {
            try {
                storageService.shutdown();
            } catch (IOException exception) {
                getLogger().log(java.util.logging.Level.SEVERE, "Unable to persist LibelulaMapInspector storage during shutdown.", exception);
            }
        }
        getLogger().info("LibelulaMapInspector has been disabled.");
    }

    private void initializeStorageService() {
        try {
            storageService.initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize LibelulaMapInspector storage.", exception);
        }
    }

    private void registerCommands() {
        PluginCommand lmiCommand = getCommand("lmi");
        if (lmiCommand == null) {
            throw new IllegalStateException("Command 'lmi' is missing from plugin.yml.");
        }

        LibelulaMapInspectorCommand commandHandler = new LibelulaMapInspectorCommand(this, storageService, resetConfirmationService, wandToolService, undoService);
        lmiCommand.setExecutor(commandHandler);
        lmiCommand.setTabCompleter(commandHandler);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new ResetConfirmationListener(resetConfirmationService), this);
        getServer().getPluginManager().registerEvents(new WandToolListener(this, storageService, wandToolService), this);
        new CaptureListenerRegistrar(this, captureConfiguration, blockHistoryCaptureService, fluidAttributionService, tntAttributionService).registerListeners();
    }

    private void logStartupLogo() {
        getLogger().info(System.lineSeparator() + STARTUP_LOGO);
    }

    private void logStartupPanel() {
        getLogger().info("Recorded events: " + formatCount(storageService.getRecordedEventCount()));
        getLogger().info("Disk usage: " + formatMebibytes(storageService.getChunkDiskBytes()) + " MiB");
        getLogger().info("Disk limit: " + storageService.getMaxChunkDiskMegabytes() + " MiB");
        getLogger().info("Retention: " + storageService.getMaxChunkRecordAgeDays() + " days");
        getLogger().info("Capture listeners: blocks=" + captureConfiguration.shouldRegisterBlockCaptureListener()
                + ", tnt=" + captureConfiguration.shouldRegisterTntCaptureListener()
                + ", fluid-grief=" + captureConfiguration.shouldRegisterFluidGriefListener());
        if (storageService.isStartupMaintenanceRunning()) {
            getLogger().info("Startup cleanup: running in the background");
        }
    }

    private void initializeCaptureServices() {
        blockHistoryCaptureService = new BlockHistoryCaptureService(storageService);
        fluidAttributionService = captureConfiguration.shouldRegisterFluidGriefListener() ? new FluidAttributionService() : null;
        tntAttributionService = captureConfiguration.shouldRegisterTntCaptureListener() ? new TntAttributionService() : null;
        if (tntAttributionService != null) {
            storageService.addBlockMutationObserver(tntAttributionService);
        }
    }

    private String formatCount(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatMebibytes(long bytes) {
        return String.format(Locale.US, "%,.1f", bytes / (1024.0D * 1024.0D));
    }
}
