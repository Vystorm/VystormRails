package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RailsPanelsTest {
    private RailsPlugin plugin() throws Exception {
        RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.lang()).thenReturn(RailsPanelTest.lang());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        doCallRealMethod().when(plugin).openNetwork(any());
        doCallRealMethod().when(plugin).openFavorites(any());
        return plugin;
    }

    private static boolean register(RailsPlugin plugin) throws Exception {
        var register = RailsPlugin.class.getDeclaredMethod("registerPanels");
        register.setAccessible(true);
        return (boolean) register.invoke(plugin);
    }

    @Test void givenPanels_whenRegistered_thenBothSpecsHaveAccessAndChestFallbacks() throws Exception {
        RailsPlugin plugin = plugin();
        Player player = mock(Player.class);
        when(player.hasPermission("vystormrails.use")).thenReturn(true);
        Panels panels = mock(Panels.class);
        try (var api = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class);
             var network = mockStatic(NetworkMenu.class); var favorites = mockStatic(FavoritesMenu.class)) {
            api.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            assertTrue(register(plugin));
            ArgumentCaptor<PanelSpec> spec = ArgumentCaptor.forClass(PanelSpec.class);
            verify(panels).register(eq("network"), spec.capture());
            assertNotNull(spec.getValue().menuCategory());
            assertTrue(spec.getValue().access().test(player));
            when(player.hasPermission("vystormrails.use")).thenReturn(false);
            assertFalse(spec.getValue().access().test(player));
            spec.getValue().fallback().accept(player);
            network.verify(() -> NetworkMenu.open(plugin, player));
            verify(panels).register(eq("favorites"), spec.capture());
            spec.getValue().fallback().accept(player);
            favorites.verify(() -> FavoritesMenu.open(plugin, player));
        }
    }

    @Test void givenFailedOrEmptyOpen_whenRequested_thenChestFallbackRunsExactlyOnce() throws Exception {
        RailsPlugin plugin = plugin();
        Player player = mock(Player.class);
        when(player.hasPermission("vystormrails.use")).thenReturn(true);
        RailsPanels panels = mock(RailsPanels.class);
        var field = RailsPlugin.class.getDeclaredField("panels");
        field.setAccessible(true); field.set(plugin, panels);
        try (var network = mockStatic(NetworkMenu.class); var favorites = mockStatic(FavoritesMenu.class)) {
            when(panels.open(player, false)).thenReturn(false);
            when(panels.open(player, true)).thenThrow(new IllegalStateException("panel failure"));
            plugin.openNetwork(player);
            plugin.openFavorites(player);
            network.verify(() -> NetworkMenu.open(plugin, player));
            favorites.verify(() -> FavoritesMenu.open(plugin, player));
            when(player.hasPermission("vystormrails.use")).thenReturn(false);
            plugin.openNetwork(player);
            plugin.openFavorites(player);
            network.verifyNoMoreInteractions();
            favorites.verifyNoMoreInteractions();
        }
    }

    @Test void givenSuccessfulOrCoreFallbackOpen_whenRequested_thenNoSecondChestIsOpened() throws Exception {
        RailsPlugin plugin = plugin();
        Player player = mock(Player.class);
        when(player.hasPermission("vystormrails.use")).thenReturn(true);
        Panels panels = mock(Panels.class);
        try (var api = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class); var network = mockStatic(NetworkMenu.class)) {
            api.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            assertTrue(register(plugin));
            when(panels.open(player, "network")).thenReturn(PanelOpenResult.NATIVE, PanelOpenResult.FALLBACK, PanelOpenResult.NONE);
            plugin.openNetwork(player);
            plugin.openNetwork(player);
            network.verifyNoInteractions();
            plugin.openNetwork(player);
            network.verify(() -> NetworkMenu.open(plugin, player));
        }
    }

    @Test void givenPartialRegistrationFailure_whenRegistering_thenBothSpecsAreRemoved() throws Exception {
        RailsPlugin plugin = plugin();
        Panels panels = mock(Panels.class);
        try (var api = mockStatic(CoreApi.class); var factory = mockStatic(Panels.class)) {
            api.when(() -> CoreApi.has(CoreApi.Capability.PANELS)).thenReturn(true);
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            doThrow(new IllegalStateException("registration failed")).when(panels).register(eq("network"), any());
            assertFalse(register(plugin));
            verify(panels).unregister("network");
            verify(panels).unregister("favorites");
        }
    }

    @Test void givenCoreWithoutPanelClasses_whenLoadingPluginOn1206_thenEntryPointsStillResolve() throws Exception {
        var urls = new ArrayList<URL>();
        urls.add(RailsPlugin.class.getProtectionDomain().getCodeSource().getLocation());
        urls.add(Path.of(System.getProperty("compat.coreJar")).toUri().toURL());
        try (var paths = Files.walk(Path.of(System.getProperty("compat.libraries")))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(path.toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("Vystorm.vystorm_core.panel.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> plugin = loader.loadClass("at.esoren.vystormrails.RailsPlugin");
            assertNotNull(plugin.getDeclaredMethods());
            assertNotNull(plugin.getDeclaredFields());
        }
    }
}
