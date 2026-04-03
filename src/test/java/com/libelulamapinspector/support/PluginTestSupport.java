package com.libelulamapinspector.support;

import org.bukkit.Server;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class PluginTestSupport {

    private PluginTestSupport() {
    }

    public static <T extends JavaPlugin> T mockPlugin(Class<T> pluginType, Path dataFolder, Consumer<YamlConfiguration> configurer) throws IOException {
        Files.createDirectories(dataFolder);

        YamlConfiguration configuration = new YamlConfiguration();
        if (configurer != null) {
            configurer.accept(configuration);
        }

        T plugin = mock(pluginType);
        Logger logger = Logger.getLogger("LibelulaMapInspectorTest-" + dataFolder.getFileName());
        logger.setUseParentHandlers(false);

        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }

    public static BukkitScheduler wireImmediateScheduler(JavaPlugin plugin) {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[0]);
        when(server.getPlayerExact(any())).thenReturn((Player) null);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return task;
        }).when(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return task;
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return task;
        }).when(scheduler).runTaskLater(any(Plugin.class), any(Runnable.class), any(Long.class));
        when(scheduler.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), any(Long.class), any(Long.class)))
                .thenReturn(task);

        return scheduler;
    }
}
