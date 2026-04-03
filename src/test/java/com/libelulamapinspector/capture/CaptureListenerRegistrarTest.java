package com.libelulamapinspector.capture;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureListenerRegistrarTest {

    @Test
    void doesNotRegisterTheFluidGriefListenerWhenFluidTrackingIsDisabled() {
        CaptureConfiguration configuration = CaptureConfiguration.from(new YamlConfiguration());
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        new CaptureListenerRegistrar(plugin, configuration, mock(BlockHistoryCaptureService.class), null, mock(TntAttributionService.class))
                .registerListeners();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(pluginManager, org.mockito.Mockito.times(2)).registerEvents(listenerCaptor.capture(), org.mockito.ArgumentMatchers.eq(plugin));
        List<Class<?>> listenerTypes = listenerCaptor.getAllValues().stream().map(Object::getClass).toList();
        assertEquals(2, listenerTypes.size());
        assertTrue(listenerTypes.stream().noneMatch(type -> type.getSimpleName().equals("FluidGriefCaptureListener")));
    }

    @Test
    void registersAllConfiguredCaptureListeners() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("capture.fluids.fluid-grief-tracking", true);
        CaptureConfiguration configuration = CaptureConfiguration.from(yaml);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        new CaptureListenerRegistrar(
                plugin,
                configuration,
                mock(BlockHistoryCaptureService.class),
                mock(FluidAttributionService.class),
                mock(TntAttributionService.class)
        ).registerListeners();

        verify(pluginManager, org.mockito.Mockito.times(3)).registerEvents(org.mockito.ArgumentMatchers.any(Listener.class), org.mockito.ArgumentMatchers.eq(plugin));
    }
}
