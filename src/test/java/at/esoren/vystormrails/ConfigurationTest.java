package at.esoren.vystormrails;

import static at.esoren.vystormrails.BugFixTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
    @Test void nonFiniteSpeedsAndDamageUseSafeDefaults() throws Exception {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            YamlConfiguration config = new YamlConfiguration();
            for (String key : List.of("speed.cap", "speed.default", "speed.curve-speed", "collisions.mob-damage", "collisions.min-speed"))
                config.set(key, invalid);
            config.set("speed.blocks.GOLD_BLOCK", invalid); config.set("favorites.max-per-world", 18);
            RailsPlugin plugin = TestCore.attach(mock(RailsPlugin.class, CALLS_REAL_METHODS));
            doNothing().when(plugin).reloadConfig(); doReturn(config).when(plugin).getConfig();
            doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
            var lang = mock(Vystorm.vystorm_core.lang.Lang.class); field(plugin, "lang", lang);
            field(plugin, "blockSpeeds", new EnumMap<Material, Double>(Material.class));
            field(plugin, "cartBlocks", new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {}));
            field(plugin, "routes", new RouteCache(10_000)); field(plugin, "signCache", new HashMap<>());
            call(plugin, "load", new Class<?>[0]);
            assertEquals(1.5, (double) field(plugin, "cap"));
            assertEquals(.4, (double) field(plugin, "defaultSpeed"));
            assertEquals(.4, (double) field(plugin, "curveSpeed"));
            assertEquals(4, (double) field(plugin, "mobDamage"));
            assertEquals(.25, (double) field(plugin, "pushSpeed"));
            assertTrue(((Map<?, ?>) field(plugin, "blockSpeeds")).isEmpty());
            var sender = mock(org.bukkit.command.CommandSender.class);
            call(plugin, "blocks", new Class<?>[]{org.bukkit.command.CommandSender.class}, sender);
            verify(lang, never()).send(eq(sender), eq("blocks-speed"), eq("block"), any(), eq("kmh"), any());
        }
    }

    @Test void nonFiniteCartBlockSettingsCannotPoisonVelocity() throws Exception {
        Map<String, String> fields = Map.ofEntries(Map.entry("steer.degrees-per-tick", "steerDegrees"),
                Map.entry("steer.push-up-to", "pushSpeed"), Map.entry("jump.power", "jumpPower"),
                Map.entry("jump.speed-factor", "jumpFactor"), Map.entry("jump.min-speed", "jumpMin"),
                Map.entry("jump.air-keep", "airKeep"), Map.entry("bounce.keep", "bounceKeep"),
                Map.entry("elevator.speed", "liftSpeed"), Map.entry("float.speed", "floatSpeed"));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            YamlConfiguration config = new YamlConfiguration();
            for (String key : fields.keySet()) config.set(key, invalid);
            config.set("ground.ICE", invalid);
            CartBlocks blocks = new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {});
            Material material = mock(Material.class); when(material.isBlock()).thenReturn(true);
            try (var materials = mockStatic(Material.class, CALLS_REAL_METHODS)) {
                materials.when(() -> Material.matchMaterial(anyString())).thenReturn(material);
                blocks.load(config, Logger.getAnonymousLogger());
            }
            for (String name : fields.values()) assertTrue(Double.isFinite((double) field(blocks, name)), name);
            Map<Material, Double> ground = field(blocks, "ground");
            assertTrue(ground.values().stream().allMatch(Double::isFinite));
        }
    }

    @Test void nonFiniteTrainSpacingDoesNotBreakReloadOrMovement() throws Exception {
        Plugin plugin = mock(Plugin.class); when(plugin.getName()).thenReturn("VystormRails");
        when(plugin.namespace()).thenReturn("vystormrails");
        Trains trains = new Trains(plugin, null);
        YamlConfiguration config = new YamlConfiguration(); config.set("coupler", "MINECART");
        for (String key : List.of("spacing", "couple-distance", "break-distance")) config.set(key, Double.NaN);
        assertDoesNotThrow(() -> trains.load(config, 1.5));
        assertEquals(1.6, (double) field(trains, "spacing"));
        assertEquals(3, (double) field(trains, "reach"));
        assertEquals(6, (double) field(trains, "breakDistance"));
    }
}
