package at.esoren.vystormrails;

import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tells {@link CartBlocks} and {@link Trains} when a minecart leaves the world (since 1.17.1). On servers where
 * Bukkit's {@code EntityRemoveEvent} is still deprecated (Paper 1.20.6), listening to it logs "Server performance will
 * be affected" and makes the server fire it for every entity, so there Paper's {@code EntityRemoveFromWorldEvent} is
 * used instead (destroyed carts are dead, unloaded ones are not).
 */
final class CartRemovals {
    private CartRemovals() { }

    static void register(Plugin plugin, CartBlocks blocks, Trains trains) {
        boolean deprecated = EntityRemoveEvent.class.isAnnotationPresent(Deprecated.class);
        boolean paperEvent;
        try { Class.forName("com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent"); paperEvent = true; }
        catch (ClassNotFoundException missing) { paperEvent = false; }
        Listener listener = deprecated && paperEvent ? new PaperRemovals(blocks, trains) : new BukkitRemovals(blocks, trains);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    static final class BukkitRemovals implements Listener {
        private final CartBlocks blocks;
        private final Trains trains;

        BukkitRemovals(CartBlocks blocks, Trains trains) { this.blocks = blocks; this.trains = trains; }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(EntityRemoveEvent event) {
            if (!(event.getEntity() instanceof Minecart cart)) return;
            blocks.removed(cart);
            trains.removed(cart, event.getCause() == EntityRemoveEvent.Cause.UNLOAD);
        }
    }

    static final class PaperRemovals implements Listener {
        private final CartBlocks blocks;
        private final Trains trains;

        PaperRemovals(CartBlocks blocks, Trains trains) { this.blocks = blocks; this.trains = trains; }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
            if (!(event.getEntity() instanceof Minecart cart)) return;
            blocks.removed(cart);
            trains.removed(cart, !cart.isDead());
        }
    }
}
