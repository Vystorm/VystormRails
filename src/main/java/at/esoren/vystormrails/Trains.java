package at.esoren.vystormrails;

import Vystorm.vystorm_core.lang.Lang;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Trains: right-click a cart with a chain, then a second cart, and they are coupled. A train runs as one: the cart in
 * front drives (powered rails, stations, signals, braking), the others follow at a fixed spacing and are pushed when the
 * front is pushed. Sneak + right-click with the chain uncouples a cart. Links are stored on the carts themselves and
 * survive restarts and chunk unloads.
 */
final class Trains implements Listener {
    private record Selection(UUID cart, long until) {}

    private final Plugin plugin;
    private final Lang lang;
    private final NamespacedKey linksKey;
    private final Couplings couplings = new Couplings();
    private final Map<UUID, Selection> selected = new HashMap<>();
    private final Map<UUID, Location> lastSeen = new HashMap<>();
    private final Set<UUID> trailing = new HashSet<>();
    /** Catch-up speed each follower got on top of the train speed, so it is not mistaken for the train speed next tick. */
    private Map<UUID, Double> corrections = new HashMap<>(), nextCorrections = new HashMap<>();
    private final Set<Material> couplers = new HashSet<>();
    private Predicate<UUID> held = cart -> false;
    private boolean enabled = true;
    private double spacing = 1.6, reach = 3, breakDistance = 6, cap = 1.5;
    private int maxLength = 8;

    Trains(Plugin plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.linksKey = new NamespacedKey(plugin, "coupled");
    }

    /** Carts that wait at a station or signal right now; a train with such a cart stands still. */
    void held(Predicate<UUID> held) { this.held = held; }

    void load(ConfigurationSection config, double cap) {
        this.cap = cap;
        couplers.clear();
        enabled = config == null || config.getBoolean("enabled", true);
        if (config == null) config = new org.bukkit.configuration.MemoryConfiguration();
        String coupler = config.getString("coupler", "*CHAIN").strip().toUpperCase(Locale.ROOT);
        if (coupler.startsWith("*")) {
            for (Material material : Material.values()) if (!material.name().startsWith("LEGACY_") && material.name().endsWith(coupler.substring(1)) && material.isItem()) couplers.add(material);
        } else {
            Material material = Material.matchMaterial(coupler);
            if (material != null) couplers.add(material);
        }
        if (couplers.isEmpty() && enabled) plugin.getLogger().warning("trains.coupler: no item matches " + coupler);
        spacing = Math.clamp(ConfigNumbers.read(config, "spacing", 1.6), 1.1, 4);
        reach = Math.clamp(ConfigNumbers.read(config, "couple-distance", 3), 1.5, 8);
        breakDistance = Math.clamp(ConfigNumbers.read(config, "break-distance", 6), spacing + 1, 16);
        maxLength = Math.clamp(config.getInt("max-length", 8), 2, 64);
    }

    boolean coupled(UUID cart) { return enabled && couplings.coupled(cart); }

    /** True for a coupled cart that is not the front of its train right now: stations and switches leave it alone. */
    boolean trailing(UUID cart) { return enabled && trailing.contains(cart); }

    boolean sameTrain(UUID a, UUID b) { return enabled && couplings.sameTrain(a, b); }

    /** This cart's train from one end to the other. */
    List<UUID> train(UUID cart) { return couplings.train(cart); }

    /** Number of carts in this cart's train (1 when not coupled). */
    int length(UUID cart) { return couplings.train(cart).size(); }

    // ---- coupling -------------------------------------------------------------------------------------------------

    /**
     * Clicks on entities go through Core's stages, so Core decides when this runs among all Vystorm plugins. Coupling
     * with the coupler item is the feature itself and consumes the click: ACT, after every protection and rule. It does
     * not see a cancelled click.
     */
    void handleSharedEvents(Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(plugin, PlayerInteractEntityEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ACT, this::onInteract);
    }

    /** Stage ACT, registered in {@link #handleSharedEvents}. */
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!enabled || event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Minecart cart)) return;
        Player player = event.getPlayer();
        if (!couplers.contains(player.getInventory().getItemInMainHand().getType())) return;
        event.setCancelled(true);
        if (!player.hasPermission("vystormrails.train")) { lang.send(player, "denied"); return; }
        UUID id = cart.getUniqueId();
        if (player.isSneaking()) {
            selected.remove(player.getUniqueId());
            if (uncouple(cart)) {
                lang.send(player, "train-uncoupled");
                cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_CHAIN_BREAK, 0.8f, 1f);
            } else lang.send(player, "train-not-coupled");
            return;
        }
        Selection selection = selected.remove(player.getUniqueId());
        Minecart first = selection != null && selection.until() > System.currentTimeMillis() && Bukkit.getEntity(selection.cart()) instanceof Minecart other
                && other.isValid() && !other.getUniqueId().equals(id) ? other : null;
        if (first == null) {
            selected.put(player.getUniqueId(), new Selection(id, System.currentTimeMillis() + 30_000));
            lang.send(player, "train-selected");
            cart.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, cart.getLocation().add(0, 0.6, 0), 8, 0.3, 0.2, 0.3);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
            return;
        }
        if (first.getWorld() != cart.getWorld() || first.getLocation().distance(cart.getLocation()) > reach) {
            lang.send(player, "train-too-far", "distance", Long.toString(Math.round(reach)));
            return;
        }
        Couplings.Refusal refusal = couplings.check(first.getUniqueId(), id, maxLength);
        if (refusal != null) {
            lang.send(player, switch (refusal) {
                case FULL -> "train-full";
                case TOO_LONG -> "train-too-long";
                default -> "train-same";
            }, "max", Integer.toString(maxLength));
            return;
        }
        couplings.link(first.getUniqueId(), id);
        store(first);
        store(cart);
        seen(first);
        seen(cart);
        lang.send(player, "train-coupled", "cars", Integer.toString(length(id)));
        cart.getWorld().playSound(cart.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1f);
        Location from = first.getLocation().add(0, 0.5, 0), to = cart.getLocation().add(0, 0.5, 0);
        Vector step = to.toVector().subtract(from.toVector()).multiply(1 / 6.0);
        for (int i = 0; i <= 6; i++) cart.getWorld().spawnParticle(Particle.CRIT, from.clone().add(step.clone().multiply(i)), 2, 0.05, 0.05, 0.05, 0);
    }

    /** Uncouples the cart from its neighbours; false when it was not coupled. */
    boolean uncouple(Minecart cart) {
        List<UUID> partners = couplings.unlinkAll(cart.getUniqueId());
        if (partners.isEmpty()) return false;
        store(cart);
        for (UUID partner : partners) {
            if (Bukkit.getEntity(partner) instanceof Minecart other) store(other);
            if (!couplings.coupled(partner)) lastSeen.remove(partner);
        }
        trailing.remove(cart.getUniqueId());
        lastSeen.remove(cart.getUniqueId());
        return true;
    }

    private void store(Minecart cart) {
        List<UUID> neighbours = couplings.neighbours(cart.getUniqueId());
        if (neighbours.isEmpty()) cart.getPersistentDataContainer().remove(linksKey);
        else cart.getPersistentDataContainer().set(linksKey, PersistentDataType.STRING, Couplings.format(neighbours));
    }

    private void seen(Entity cart) { lastSeen.put(cart.getUniqueId(), cart.getLocation()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { selected.remove(event.getPlayer().getUniqueId()); }

    /** Links come back with the carts when their chunk loads. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Minecart cart) restore(cart);
    }

    /** Reads the links of every cart that is already loaded (plugin start, reload). */
    void restoreLoaded() {
        for (World world : Bukkit.getWorlds())
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) restore(cart);
    }

    /**
     * Links stored on a cart come back. A partner that is known to be gone is dropped at once; a partner that is not
     * loaded is expected near this cart, and is dropped once everything around here is loaded and it still does not
     * show up (it was removed while this cart's chunk was not loaded).
     */
    private void restore(Minecart cart) {
        String stored = cart.getPersistentDataContainer().get(linksKey, PersistentDataType.STRING);
        if (stored == null) return;
        seen(cart);
        if (couplings.restore(cart.getUniqueId(), Couplings.parse(stored))) store(cart);
        for (UUID partner : couplings.neighbours(cart.getUniqueId()))
            if (!(Bukkit.getEntity(partner) instanceof Minecart)) lastSeen.putIfAbsent(partner, cart.getLocation());
    }

    // ---- every tick -----------------------------------------------------------------------------------------------

    void tick() {
        if (!enabled) { trailing.clear(); corrections.clear(); nextCorrections.clear(); return; }
        if (couplings.carts() == 0) { trailing.clear(); corrections.clear(); return; }
        Set<UUID> nowTrailing = new HashSet<>();
        for (List<UUID> ids : couplings.trains()) {
            List<Minecart> carts = new ArrayList<>(ids.size());
            boolean complete = true;
            for (UUID id : ids) {
                if (Bukkit.getEntity(id) instanceof Minecart cart && cart.isValid()) carts.add(cart);
                else { complete = false; forgetIfGone(id); }
            }
            if (!complete) { // part of the train is in an unloaded chunk: wait until it is back
                for (Minecart cart : carts) cart.setVelocity(new Vector());
                for (UUID id : ids) if (trailing.contains(id)) nowTrailing.add(id);
                continue;
            }
            for (UUID id : ids) couplings.present(id);
            if (!checkLinks(carts)) continue;
            run(carts, nowTrailing);
        }
        trailing.clear();
        trailing.addAll(nowTrailing);
        Map<UUID, Double> swap = corrections;
        corrections = nextCorrections;
        nextCorrections = swap;
        nextCorrections.clear();
    }

    /**
     * A cart missing for two seconds although every chunk around its last place (or, after a restart, around the partner
     * that remembered it) is loaded with its entities is gone for good (broken, picked up, removed while its partner was
     * not loaded): uncouple it.
     */
    private void forgetIfGone(UUID id) {
        Location last = lastSeen.get(id);
        World world = last == null ? null : last.getWorld();
        boolean checkable = world != null && Couplings.areaLoaded(last.getX(), last.getZ(), breakDistance,
                (x, z) -> world.isChunkLoaded(x, z) && world.getChunkAt(x, z).isEntitiesLoaded());
        List<UUID> partners = couplings.missing(id, checkable, 40);
        if (partners.isEmpty()) return;
        for (UUID partner : partners) {
            if (Bukkit.getEntity(partner) instanceof Minecart other) store(other);
            if (!couplings.coupled(partner)) lastSeen.remove(partner);
        }
        lastSeen.remove(id);
        trailing.remove(id);
    }

    /** A coupled cart that is destroyed or removed (not just unloaded) is uncoupled at once; partners not loaded drop it when they load. */
    void removed(Minecart cart, boolean unloaded) {
        if (unloaded) return;
        UUID id = cart.getUniqueId();
        if (!couplings.coupled(id) && !cart.getPersistentDataContainer().has(linksKey, PersistentDataType.STRING)) return;
        for (UUID partner : couplings.removed(id)) {
            if (Bukkit.getEntity(partner) instanceof Minecart other && !other.getUniqueId().equals(id)) store(other);
            if (!couplings.coupled(partner)) lastSeen.remove(partner);
        }
        lastSeen.remove(id);
        trailing.remove(id);
    }

    /** Breaks links between carts that got too far apart (derailed, teleported); false when anything broke. */
    private boolean checkLinks(List<Minecart> carts) {
        boolean intact = true;
        for (int i = 1; i < carts.size(); i++) {
            Minecart a = carts.get(i - 1), b = carts.get(i);
            if (a.getWorld() == b.getWorld() && a.getLocation().distanceSquared(b.getLocation()) <= breakDistance * breakDistance) continue;
            couplings.unlink(a.getUniqueId(), b.getUniqueId());
            store(a);
            store(b);
            for (Minecart cart : List.of(a, b))
                for (Entity rider : cart.getPassengers()) if (rider instanceof Player player) lang.actionBar(player, "train-broke");
            intact = false;
        }
        for (Minecart cart : carts) seen(cart);
        return intact;
    }

    /**
     * One tick of a train: the fastest cart sets the speed, the cart at the front in that direction leads, every other
     * cart follows the one before it at the spacing. A train with a waiting cart stands still and closes up.
     */
    private void run(List<Minecart> carts, Set<UUID> nowTrailing) {
        int n = carts.size();
        Vector[] position = new Vector[n];
        for (int i = 0; i < n; i++) position[i] = carts.get(i).getLocation().toVector().setY(0);
        boolean waiting = false;
        double speed = 0;
        for (int i = 0; i < n; i++) {
            Minecart cart = carts.get(i);
            if (held.test(cart.getUniqueId())) waiting = true;
            // towards the first cart of the list, without the catch-up speed this cart got last tick
            Vector forward = i > 0 ? position[i - 1].clone().subtract(position[i]) : position[0].clone().subtract(position[1]);
            if (forward.lengthSquared() < 1.0E-6) continue;
            double along = cart.getVelocity().setY(0).dot(forward.normalize()) - corrections.getOrDefault(cart.getUniqueId(), 0.0);
            if (Math.abs(along) > Math.abs(speed)) speed = along;
        }
        boolean backwards;
        if (waiting || Math.abs(speed) < 0.005) {
            speed = 0;
            backwards = trailing.contains(carts.getFirst().getUniqueId()); // keep the last front
        } else backwards = speed < 0;
        List<Minecart> order = new ArrayList<>(carts);
        if (backwards) java.util.Collections.reverse(order);
        Vector[] at = new Vector[n];
        for (int i = 0; i < n; i++) at[i] = order.get(i).getLocation().toVector().setY(0);
        double pace = Math.min(Math.min(Math.abs(speed), cap), order.getFirst().getMaxSpeed()); // the front brakes for the train

        Minecart front = order.getFirst();
        if (pace > 0 && !held.test(front.getUniqueId())) {
            Vector own = front.getVelocity();
            Vector ahead = at[0].clone().subtract(at[1]);
            Vector horizontal = own.clone().setY(0);
            Vector direction = horizontal.lengthSquared() > 1.0E-4 && horizontal.dot(ahead) > 0 ? horizontal.normalize()
                    : ahead.lengthSquared() > 1.0E-6 ? ahead.normalize() : null;
            if (direction != null) {
                front.setVelocity(direction.multiply(pace).setY(own.getY()));
            }
        }
        for (int i = 1; i < n; i++) {
            Minecart cart = order.get(i);
            nowTrailing.add(cart.getUniqueId());
            if (held.test(cart.getUniqueId())) continue;
            Vector toNext = at[i - 1].clone().subtract(at[i]);
            double gap = toNext.length();
            if (gap < 1.0E-3) continue;
            if (pace == 0 && Math.abs(gap - spacing) < 0.3) continue; // parked and close enough: let it rest
            double follow = Couplings.follow(gap, spacing, pace, cap);
            cart.setMaxSpeed(Math.max(cart.getMaxSpeed(), Math.min(cap, Math.abs(follow) + 0.05)));
            cart.setVelocity(toNext.multiply(follow / gap).setY(cart.getVelocity().getY()));
            nextCorrections.put(cart.getUniqueId(), (backwards ? -1 : 1) * (follow - pace));
        }
    }

    void clear() {
        couplings.clear();
        selected.clear();
        lastSeen.clear();
        corrections.clear();
        trailing.clear();
    }
}
