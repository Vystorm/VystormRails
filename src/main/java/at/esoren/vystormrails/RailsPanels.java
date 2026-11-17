package at.esoren.vystormrails;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Loaded only after the Core capability guard, keeping panel types out of the plugin's entry points. */
final class RailsPanels {
    private final RailsPlugin plugin;
    private final Panels panels;
    private final RailsPanel network, favorites;

    RailsPanels(RailsPlugin plugin) {
        this.plugin = plugin;
        panels = plugin.core().gui().panels().of(plugin);
        network = new RailsPanel(plugin, false);
        favorites = new RailsPanel(plugin, true);
    }

    void register() {
        try {
            panels.register(RailsPanel.FAVORITES, PanelSpec.builder(plugin.lang().ref("favorites-title"))
                    .access(this::allowed).render(favorites::render).fallback(p -> FavoritesMenu.open(plugin, p)).build());
            panels.register(RailsPanel.NETWORK, PanelSpec.builder(plugin.lang().ref("menu-rails"))
                    .access(this::allowed).render(network::render)
                    .menu(MenuCategory.WORLD, Material.MINECART, plugin.lang().ref("menu-rails-desc"))
                    .fallback(p -> NetworkMenu.open(plugin, p)).build());
        } catch (RuntimeException failure) {
            try { panels.unregister(RailsPanel.NETWORK); panels.unregister(RailsPanel.FAVORITES); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private boolean allowed(Player player) { return plugin.isEnabled() && player.hasPermission("vystormrails.use"); }

    boolean open(Player player, boolean favorites) {
        return panels.open(player, favorites ? RailsPanel.FAVORITES : RailsPanel.NETWORK) != PanelOpenResult.NONE;
    }

    void forget(UUID player) { network.forget(player); favorites.forget(player); }
    void close() {
        network.clear(); favorites.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            panels.close(player, RailsPanel.NETWORK);
            panels.close(player, RailsPanel.FAVORITES);
        }
        panels.unregister(RailsPanel.NETWORK);
        panels.unregister(RailsPanel.FAVORITES);
    }
}
