package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._integrations.vault.VaultInterface;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spigot has no {@code Bukkit.getOfflinePlayerIfCached} (Paper API). A paid fare with {@code fares.payee} set called it
 * directly, so on Spigot the fare was taken from the rider, the payee got nothing and the cart was not sent off
 * (NoSuchMethodError in the station task). The payee lookup goes through Core's platform part, which has a Spigot
 * fallback. Without a server a direct Bukkit call fails here too, so this test also shows the old path.
 */
class FarePayeePlatformTest {
    @Test
    void givenAPayee_whenARiderPaysAFare_thenThePayeeIsLookedUpThroughCoreAndPaid(@TempDir Path dir) throws Exception {
        RailsPlugin plugin = mock(RailsPlugin.class, CALLS_REAL_METHODS);
        Fares fares = new Fares(dir.resolve("fares.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        fares.set("Central", 3);
        YamlConfiguration config = new YamlConfiguration();
        config.set("fares.payee", "Owner");
        doReturn(config).when(plugin).getConfig();

        OfflinePlayer owner = mock(OfflinePlayer.class);
        PlatformCompatInterface platform = mock(PlatformCompatInterface.class);
        when(platform.getOfflinePlayerIfCached("Owner")).thenReturn(owner);
        CompatInterface compat = mock(CompatInterface.class);
        when(compat.platform()).thenReturn(platform);
        VaultInterface economy = mock(VaultInterface.class);
        when(economy.isEconomyAvailable()).thenReturn(true);
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(economy.withdraw(any(), anyDouble())).thenReturn(new VaultInterface.Transaction(true, 3, 0, null));
        when(economy.format(anyDouble())).thenReturn("3");
        API core = mock(API.class);
        when(core.compat()).thenReturn(compat);
        when(core.vault()).thenReturn(economy);
        set(plugin, "fares", fares);
        set(plugin, "core", core);
        set(plugin, "lang", mock(Lang.class));

        Player rider = mock(Player.class);
        when(rider.getUniqueId()).thenReturn(UUID.randomUUID());
        Minecart cart = mock(Minecart.class);
        when(cart.getPassengers()).thenReturn(List.of(rider));

        var charge = RailsPlugin.class.getDeclaredMethod("chargeFares", Minecart.class, String.class);
        charge.setAccessible(true);
        assertDoesNotThrow(() -> charge.invoke(plugin, cart, "Central"));
        verify(economy).withdraw(rider, 3.0);
        verify(platform).getOfflinePlayerIfCached("Owner");
        verify(economy).deposit(owner, 3.0);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = RailsPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
