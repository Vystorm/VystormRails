package at.esoren.vystormrails;

import static org.mockito.Mockito.*;
import java.lang.reflect.Proxy;
import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.Registry;
import org.bukkit.Sound;

/** Supplies the modern API's sound constants without starting a server. */
final class SoundRegistryTestSupport {
    static void initialize() throws Exception {
        RegistryAccess access = mock(RegistryAccess.class, invocation -> {
            if (!invocation.getMethod().getName().equals("getRegistry")) return RETURNS_DEFAULTS.answer(invocation);
            return Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[]{Registry.class}, (proxy, method, args) -> {
                if (method.getName().equals("get") || method.getName().equals("getOrThrow"))
                    return Proxy.newProxyInstance(Sound.class.getClassLoader(), new Class<?>[]{Sound.class},
                            (sound, operation, parameters) -> operation.getName().equals("toString") ? args[0].toString() : null);
                return null;
            });
        });
        try (var registryAccess = mockStatic(RegistryAccess.class)) {
            registryAccess.when(RegistryAccess::registryAccess).thenReturn(access);
            Class.forName("org.bukkit.Sound", true, Sound.class.getClassLoader());
        }
    }
}
