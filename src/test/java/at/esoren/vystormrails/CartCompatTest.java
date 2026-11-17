package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.kyori.adventure.util.TriState;
import org.bukkit.Input;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CartCompatTest {
    @Test void absentInputReturnsNeutralControls() {
        assertEquals(CartCompat.NONE, CartCompat.controls(Vystorm.vystorm_core.compat.ServerCompatInterface.DEFAULT, Object.class).read(null));
    }

    @Test void modernInputPreservesEveryButton() {
        Player player = mock(Player.class);
        Input input = mock(Input.class);
        when(player.getCurrentInput()).thenReturn(input);
        for (int mask = 0; mask < 16; mask++) {
            when(input.isLeft()).thenReturn((mask & 1) != 0);
            when(input.isRight()).thenReturn((mask & 2) != 0);
            when(input.isBackward()).thenReturn((mask & 4) != 0);
            when(input.isForward()).thenReturn((mask & 8) != 0);
            assertEquals(new CartCompat.Input((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0),
                    CartCompat.controls(Vystorm.vystorm_core.compat.ServerCompatInterface.DEFAULT, Player.class).read(player));
        }
    }

    @Test void oldFireApiLeavesVanillaFireAlone() {
        Entity entity = mock(Entity.class);
        CartCompat.Fire fire = CartCompat.fire(Object.class);
        fire.suppress(entity, true);
        fire.suppress(entity, false);
        verifyNoInteractions(entity);
    }

    @Test void modernFireSuppressesAndResets() {
        Entity entity = mock(Entity.class);
        CartCompat.Fire fire = CartCompat.fire(Entity.class);
        fire.suppress(entity, true);
        fire.suppress(entity, false);
        verify(entity).setVisualFire(TriState.FALSE);
        verify(entity).setVisualFire(TriState.NOT_SET);
    }

    @Test void loadsWithReal1206ApiWithoutModernAdapters() throws Exception {
        var urls = new ArrayList<URL>();
        urls.add(CartCompat.class.getProtectionDomain().getCodeSource().getLocation());
        urls.add(Path.of(System.getProperty("compat.coreJar")).toUri().toURL());
        try (var paths = Files.walk(Path.of(System.getProperty("compat.libraries")))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(path.toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.bukkit.Input"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("at.esoren.vystormrails.ModernControls"));
            Class<?> blocks = loader.loadClass("at.esoren.vystormrails.CartBlocks");
            Class<?> parts = loader.loadClass("Vystorm.vystorm_core.compat.CompatInterface");
            Object defaults = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {parts},
                    (proxy, method, args) -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args));
            var constructor = blocks.getDeclaredConstructor(parts);
            constructor.setAccessible(true);
            assertNotNull(constructor.newInstance(defaults));
            Class<?> compat = loader.loadClass("at.esoren.vystormrails.CartCompat");
            Class<?> server = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompatInterface");
            var controls = compat.getDeclaredMethod("controls", server, Class.class);
            controls.setAccessible(true);
            Class<?> player = loader.loadClass("org.bukkit.entity.Player");
            Object fallback = controls.invoke(null, server.getField("DEFAULT").get(null), player);
            Object input = loader.loadClass("at.esoren.vystormrails.CartCompat$Controls")
                    .getMethod("read", player).invoke(fallback, new Object[] {null});
            for (String key : new String[] {"isLeft", "isRight", "isBackward", "isForward"})
                assertEquals(false, input.getClass().getMethod(key).invoke(input));
        }
    }
}
