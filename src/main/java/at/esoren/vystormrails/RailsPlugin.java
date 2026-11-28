package at.esoren.vystormrails;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CoreCommands;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rail;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Vystorm Rails: faster minecarts with speed blocks and signs, braking before curves and slopes, stations, destinations
 * and switches, carts that go back into the inventory, mobs pushed off the track. Work happens only when a cart enters a
 * new block; sign lookups are cached per block.
 */
public final class RailsPlugin extends JavaPlugin implements Listener {
    /** {@code station}: the fare station the cart leaves when the hold ends (null for freight stops). */
    private record Hold(long until, BlockFace direction, double speed, String station) {
        Hold(long until, BlockFace direction, double speed) { this(until, direction, speed, null); }
    }

    private record SignKey(UUID world, long position) {
        static SignKey of(Vystorm.vystorm_core.compat.PlatformCompatInterface platform, Block block) {
            return new SignKey(block.getWorld().getUID(), platform.getBlockKey(block));
        }
    }

    private final Map<SignKey, Optional<RailSign>> signCache = new HashMap<>();
    private final Map<UUID, Hold> holds = new HashMap<>();
    private final Map<Material, Double> blockSpeeds = new EnumMap<>(Material.class);
    private final Map<UUID, Network> networks = new HashMap<>();
    /** Worlds whose network file was unreadable: moved aside and not saved until the next restart. */
    private final java.util.Set<UUID> brokenNetworks = new java.util.HashSet<>();
    /** The Core hub; set first thing in onEnable. */
    private Vystorm.vystorm_core.API core;
    /** Created in onEnable: the cart blocks need Core's compatibility parts. */
    private CartBlocks cartBlocks;
    /** Auto-routing results per junction, heading and destination; valid for one revision of the world's network. */
    private final RouteCache routes = new RouteCache(10_000);
    private Trains trains;
    private boolean routing = true;
    private int routingLimit = 4000;
    private int favoritesLimit = 18;
    private Lang lang;
    private RailsPanels panels;
    private TaskGroup tasks;
    private NamespacedKey speedKey, destKey, ownerKey;
    private TrainLines lines;
    private Fares fares;
    private String header = "[rails]";
    private double defaultSpeed = 0.4, cap = 1.5, curveSpeed = 0.4, mobDamage = 4, pushSpeed = 0.25;
    private int lookahead = 3;
    private boolean slowBeforeCurves = true, pushMobs = true, passPlayers = true, emptyPickup = false;
    private boolean returnOnExit = true, returnOnBreak = true, speedometer = true, arrivalTitle = true, arrivalClear = true;
    /**
     * signs.enabled, destinations.enabled and lines.enabled, read in load() because they are asked on every cart move.
     * Kept as "off" flags so a plugin object without load() (tests) behaves as before: everything on.
     */
    private boolean signsOff, destinationsOff, linesOff;

    /** The Core hub for the classes of this plugin (they get the plugin in their constructor). */
    Vystorm.vystorm_core.API core() { return core; }

    @Override
    public void onEnable() {
        if (!Vystorm.vystorm_core.CoreApi.atLeast("0.65.0")) {
            getLogger().severe("Vystorm Core 0.65.0 or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();

        cartBlocks = new CartBlocks(core.compat());
        cartBlocks.reportCompatibility(getLogger());
        core.admin().updates().spigot(this, 140076);
        saveDefaultConfig();
        lang = core.lang().register(this); try { if (RailSwitches.on(getConfig(), RailSwitches.TUTORIAL)) core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
        speedKey = new NamespacedKey(this, "speed");
        destKey = new NamespacedKey(this, "dest");
        ownerKey = new NamespacedKey(this, "owner");
        fares = new Fares(getDataFolder().toPath().resolve("fares.yml"), core.data().files());
        try { lines = new TrainLines(this); }
        catch (Exception failure) { getLogger().severe("Train timetables unavailable; preserving lines.yml: " + failure); }
        trains = new Trains(this, lang);
        trains.held(cart -> holds.containsKey(cart) || cartBlocks.busy(cart));
        cartBlocks.trains(trains);
        rides = new RideCounts(getDataFolder().toPath().resolve("riders.yml"), core.data().files());
        if (rides.problem() != null) getLogger().severe(rides.problem());
        load();
        getServer().getPluginManager().registerEvents(this, this);
        handleBlockEvents(core.priority().stages());
        getServer().getPluginManager().registerEvents(new NetworkMenu.Clicks(this), this);
        getServer().getPluginManager().registerEvents(new StationMenu.Clicks(this), this);
        if (!registerPanels()) try {
            core.gui().registerMenu(this, "network", lang.ref("menu-rails"), org.bukkit.Material.MINECART,
                    p -> RailSwitches.on(getConfig(), RailSwitches.MAP) && p.hasPermission("vystormrails.use"), this::openNetwork,
                    Vystorm.vystorm_core._uiMethods.menu.MenuCategory.WORLD.group(), lang.ref("menu-rails-desc"));
        } catch (RuntimeException noMainMenu) { getLogger().fine("Vystorm Core main menu not available: " + noMainMenu); }
        getServer().getPluginManager().registerEvents(new FavoritesMenu.Clicks(), this);
        cartBlocks.notices((player, key) -> core.compat().platform().sendActionBar(player, lang.component(player, key)));
        getServer().getPluginManager().registerEvents(cartBlocks, this);
        cartBlocks.handleSharedEvents(this, core.priority().stages());
        getServer().getPluginManager().registerEvents(trains, this);
        trains.handleSharedEvents(core.priority().stages());
        CartRemovals.register(this, cartBlocks, trains);
        trains.restoreLoaded();
        tasks = core.tasks().of(this, "rails");
        tasks.timer(1L, 1L, this::tickHolds);
        tasks.timer(1L, 1L, () -> cartBlocks.tick(getServer().getOnlinePlayers()));
        tasks.timer(1L, 1L, trains::tick);
        tasks.timer(1200L, 1200L, routes::clear);
        tasks.timer(10L, 10L, this::tickSpeedometer);
        tasks.timer(6000L, 6000L, () -> saveNetworks(true));
        if (lines != null) lines.start();
        core.settings().config().register(this, "config.yml", this::load);
        describeSwitches();
        try {
            RailsPlaceholders.register(core.placeholders(), this, lang, placeholderSource());
        } catch (RuntimeException unavailable) { getLogger().warning("Placeholders not registered: " + unavailable); }
        rankingsReady = true;
        applyRankings();
        tasks.timer(1200L, 1200L, this::saveRides);
        core.command().register(this, lang, commandSpec(this));
        RailsMenuEntries.register(this);
    }

    @Override
    public void onDisable() {
        if (panels != null) {
            try { panels.close(); }
            catch (RuntimeException failure) { getLogger().fine("Rail panels could not close: " + failure); }
            panels = null;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            var holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof NetworkMenu || holder instanceof FavoritesMenu) player.closeInventory();
        }
        holds.clear();
        signCache.clear();
        if (cartBlocks != null) cartBlocks.clear();
        if (trains != null) trains.clear();
        routes.clear();
        saveNetworks(false);
        networks.clear();
        if (rides != null) {
            try { rides.write(rides.takeChanges()); }
            catch (java.io.IOException failure) { getLogger().warning("Could not save riders.yml: " + failure.getMessage()); }
        }
        if (core != null) core.settings().config().unregister(this);
    }

    private boolean registerPanels() {
        try {
            RailsPanels created = new RailsPanels(this);
            created.register();
            panels = created;
            return true;
        } catch (RuntimeException failure) {
            getLogger().fine("Rail panels unavailable; using chest menus: " + failure);
            return false;
        }
    }

    void openNetwork(Player player) { openMenu(player, false); }
    void openFavorites(Player player) { openMenu(player, true); }

    /** Answers with the shared off message when a feature is switched off (1.21.0); true when it is off. */
    boolean off(CommandSender sender, String key) {
        if (RailSwitches.on(getConfig(), key)) return false;
        lang.send(sender, RailSwitches.OFF);
        return true;
    }

    /**
     * For a feature whose own switch is on: answers "This needs <name> (<path>), which is switched off on this
     * server." when the switch {@code key} it depends on is off (1.21.1); true when it is off.
     */
    boolean needs(CommandSender sender, String key) {
        if (RailSwitches.on(getConfig(), key)) return false;
        lang.send(sender, RailSwitches.NEEDS, "switch", lang.plain(sender, RailSwitches.labelKey(key)), "path", key);
        return true;
    }

    /** One-line descriptions for /vadmin features and the older booleans declared as switches (1.21.0). */
    private void describeSwitches() {
        try { declareSwitches(core.settings().config(), this, lang); }
        catch (RuntimeException unavailable) { getLogger().fine("Switch descriptions unavailable: " + unavailable); }
    }

    /** What Core receives for {@code /vadmin features} (FeatureLabelsTest runs exactly this). */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Vystorm.vystorm_core.lang.Lang lang) {
        for (String path : RailSwitches.all()) {
            String description = lang.ref(RailSwitches.descriptionKey(path));
            String label = lang.ref(RailSwitches.labelKey(path));
            if (RailSwitches.DECLARED.contains(path)) config.feature(plugin, path, label, description);
            else config.label(plugin, path, label, description);
        }
        config.activation(plugin, RailSwitches.TUTORIAL, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
    }

    /** Scheduled lines while lines.enabled is on, else null (1.21.0): carts then ride as if no line were set. */
    /** What the rails placeholders read, looked up at call time. */
    RailsPlaceholders.Source placeholderSource() {
        return new RailsPlaceholders.Source() {
            public boolean destinationsOn() { return !destinationsOff; }
            public boolean faresOn() { return faresEnabled(); }
            public boolean favoritesOn() { return RailSwitches.on(getConfig(), RailSwitches.FAVORITES); }
            public boolean stationsOn() { return RailSwitches.on(getConfig(), RailSwitches.STATIONS); }
            public String destination(Player player) {
                return player.getVehicle() instanceof Minecart cart ? RailsPlugin.this.destination(cart) : RailsPlugin.this.destination(player);
            }
            public Network network(World world) { return RailsPlugin.this.network(world); }
            public long ticketUntil(UUID player, long now) { return fares == null ? 0 : fares.ticket(player, now); }
            public double fare(String station) { return fares == null ? 0 : fares.fare(station); }
            public int favorites(Player player) { return Favorites.read(player, player.getWorld().getUID()).size(); }
        };
    }

    TrainLines activeLines() { return linesOff ? null : lines; }

    private void openMenu(Player player, boolean favorites) {
        if (!isEnabled() || !player.hasPermission("vystormrails.use")) return;
        // The favorites page is a view of the network map: with the map off it names that switch (1.21.1).
        if (favorites ? off(player, RailSwitches.FAVORITES) || needs(player, RailSwitches.MAP) : off(player, RailSwitches.MAP)) return;
        if (panels != null) {
            try { if (panels.open(player, favorites)) return; }
            catch (RuntimeException failure) { getLogger().fine("Rail panel could not open; using chest menu: " + failure); }
        }
        if (favorites) FavoritesMenu.open(this, player); else NetworkMenu.open(this, player);
    }

    @EventHandler
    public void onPanelQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (panels != null) panels.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Start and reload read config.yml through here. A file with a YAML error is never saved over: it is reported and
     * left as it is (jar defaults at start, the last good settings on a reload). Returns the problem or null.
     */
    static String readConfig(org.bukkit.plugin.java.JavaPlugin plugin, boolean reload) {
        String problem = YamlCheck.readConfig(plugin, reload);
        if (problem == null && !plugin.getConfig().contains("favorites.max-per-world", true)) {
            plugin.getConfig().set("favorites.max-per-world", 18);
            plugin.saveConfig();
        }
        return problem;
    }

    /** True once config.yml was applied; a later broken file then keeps these settings. */
    private boolean configLoaded;

    String load() {
        List<Boolean> listed = listing(getConfig());
        String problem = readConfig(this, configLoaded);
        if (problem != null && configLoaded) return problem;
        configLoaded = true;
        refreshIfChanged(listed);
        favoritesLimit = Math.clamp(getConfig().getInt("favorites.max-per-world", 18), 0, 54);
        if (lang != null) lang.reload();
        header = getConfig().getString("sign-header", "[rails]").strip();
        cap = Math.clamp(ConfigNumbers.read(getConfig(), "speed.cap", 1.5), 0.1, 4.0);
        defaultSpeed = Math.clamp(ConfigNumbers.read(getConfig(), "speed.default", 0.4), 0.05, cap);
        slowBeforeCurves = getConfig().getBoolean("speed.slow-before-curves", true);
        signsOff = !RailSwitches.on(getConfig(), RailSwitches.SIGNS);
        destinationsOff = !RailSwitches.on(getConfig(), RailSwitches.DESTINATIONS);
        linesOff = !RailSwitches.on(getConfig(), RailSwitches.LINES);
        curveSpeed = Math.clamp(ConfigNumbers.read(getConfig(), "speed.curve-speed", 0.4), 0.05, cap);
        lookahead = Math.clamp(getConfig().getInt("speed.lookahead", 3), 1, 8);
        blockSpeeds.clear();
        var blocks = RailSwitches.on(getConfig(), RailSwitches.SPEED_BLOCKS) ? getConfig().getConfigurationSection("speed.blocks") : null;
        if (blocks != null) {
            for (String key : blocks.getKeys(false)) {
                double speed = blocks.getDouble(key);
                if (!Double.isFinite(speed) || speed <= 0) continue;
                String name = key.toUpperCase(Locale.ROOT);
                if (name.startsWith("*")) {
                    String suffix = name.substring(1);
                    for (Material material : Material.values()) if (material.isBlock() && material.name().endsWith(suffix)) blockSpeeds.put(material, Math.min(speed, cap));
                } else {
                    Material material = Material.matchMaterial(name);
                    if (material != null && material.isBlock()) blockSpeeds.put(material, Math.min(speed, cap));
                    else getLogger().warning("speed.blocks: unknown block " + key);
                }
            }
        }
        pushMobs = getConfig().getBoolean("collisions.push-mobs", true);
        mobDamage = Math.max(0, ConfigNumbers.read(getConfig(), "collisions.mob-damage", 4));
        pushSpeed = Math.max(0, ConfigNumbers.read(getConfig(), "collisions.min-speed", 0.25));
        passPlayers = getConfig().getBoolean("collisions.pass-through-players", true);
        emptyPickup = getConfig().getBoolean("collisions.empty-carts-pick-up-mobs", false);
        returnOnExit = getConfig().getBoolean("pickup.on-exit", true);
        returnOnBreak = getConfig().getBoolean("pickup.on-break", true);
        speedometer = getConfig().getBoolean("speedometer", true);
        arrivalTitle = getConfig().getBoolean("arrival.title", true);
        arrivalClear = getConfig().getBoolean("arrival.clear-destination", true);
        cartBlocks.load(getConfig().getConfigurationSection("cart-blocks"), getLogger());
        if (trains != null) trains.load(getConfig().getConfigurationSection("trains"), cap);
        routing = getConfig().getBoolean("routing.enabled", true);
        routingLimit = Math.clamp(getConfig().getInt("routing.search-limit", 4000), 100, 50_000);
        routes.clear();
        signCache.clear();
        rankingsOff = !RailSwitches.on(getConfig(), RailSwitches.RANKINGS);
        applyRankings();
        return problem;
    }

    // ---- rankings rails:distance and rails:trips (1.22.0) -----------------------------------------------------------

    private RideCounts rides;
    /** rankings.enabled, read in load() because it is asked on every cart move; an "off" flag like the others. */
    private boolean rankingsOff;
    /** True once onEnable registered the placeholders; the first load() declares nothing yet. */
    private boolean rankingsReady;

    /**
     * Declares the rankings on a Core with the capability {@code placeholder-rankings} (0.67.0); on an older Core
     * nothing happens. At start and after every reload, so {@code rankings.enabled} applies without a restart.
     */
    private void applyRankings() {
        if (!rankingsReady || core == null) return;
        boolean has;
        try { has = CoreApi.has("placeholder-rankings"); } catch (RuntimeException | LinkageError old) { has = false; }
        RailsPlaceholders.rankings(core.placeholders(), this, has, !rankingsOff, rides, lang, getLogger());
    }

    /** Blocks ridden by every player in a moving minecart (VehicleMoveEvent: one step per tick). */
    void countRide(Minecart cart, Location from, Location to) {
        if (rankingsOff || rides == null || !(cart instanceof RideableMinecart) || from.getWorld() != to.getWorld()) return;
        List<Entity> passengers = cart.getPassengers();
        if (passengers.isEmpty()) return;
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double blocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        for (Entity rider : passengers) if (rider instanceof Player player) rides.moved(player.getUniqueId(), player.getName(), blocks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void startRide(VehicleEnterEvent event) {
        if (rides != null && event.getVehicle() instanceof Minecart && event.getEntered() instanceof Player player) rides.start(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void endRide(VehicleExitEvent event) {
        if (rides != null && event.getExited() instanceof Player player) rides.end(player.getUniqueId());
    }

    @EventHandler
    public void endRideOnQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (rides != null) rides.end(event.getPlayer().getUniqueId());
    }

    /** At most once a minute: writes riders.yml off the server thread when something changed. */
    private void saveRides() {
        RideCounts store = rides;
        RideCounts.Snapshot changes = store == null ? null : store.takeChanges();
        if (changes == null) return;
        tasks.async(() -> {
            try { store.write(changes); }
            catch (java.io.IOException failure) {
                getLogger().warning("Could not save riders.yml: " + failure.getMessage());
                tasks.later(1L, store::failed);
            }
        });
    }

    // ---- movement -------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        countRide(cart, event.getFrom(), event.getTo());
        if (cartBlocks.busy(cart.getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Block rail = railAt(to.getBlock());
        if (rail == null) return;
        network(rail.getWorld()).add(rail.getX(), rail.getZ());
        Vector velocity = cart.getVelocity();
        BlockFace heading = Track.heading(velocity.getX(), velocity.getZ());
        if (heading == null) heading = Track.heading(to.getX() - from.getX(), to.getZ() - from.getZ());

        // Coupled carts behind the front of a train only follow: no stations, no switches of their own.
        if (trains.trailing(cart.getUniqueId())) {
            for (RailSign sign : signs(rail)) if (sign.kind() == RailSign.Kind.SPEED || sign.kind() == RailSign.Kind.DEST) act(cart, rail, sign, heading);
            return;
        }
        // A switch one block ahead is set before the cart gets there.
        if (heading != null) {
            Block ahead = railAt(rail.getRelative(heading));
            if (ahead != null) route(cart, ahead, heading);
        }
        for (RailSign sign : signs(rail)) act(cart, rail, sign, heading);
        if (heading != null) route(cart, rail, heading);
        cart.setMaxSpeed(maxSpeed(cart, rail, heading));
    }

    /** Top speed here: the block below the rail, else the cart's own speed (signs), else the default; braked before curves. */
    double maxSpeed(Minecart cart, Block rail, BlockFace heading) {
        Double block = blockSpeeds.get(rail.getRelative(BlockFace.DOWN).getType());
        double speed = block != null ? block : cart.getPersistentDataContainer().getOrDefault(speedKey, PersistentDataType.DOUBLE, defaultSpeed);
        speed = Math.min(speed, cap);
        if (slowBeforeCurves && speed > curveSpeed && heading != null && bendAhead(rail, heading)) speed = curveSpeed;
        return speed;
    }

    private boolean bendAhead(Block rail, BlockFace heading) {
        if (rail.getBlockData() instanceof Rail data && (Track.curve(data.getShape()) || Track.slope(data.getShape()))) return true;
        Block at = rail;
        for (int i = 0; i < lookahead; i++) {
            Block next = railAt(at.getRelative(heading));
            if (next == null) {
                next = railAt(at.getRelative(heading).getRelative(BlockFace.UP));
                if (next == null) next = railAt(at.getRelative(heading).getRelative(BlockFace.DOWN));
            }
            if (next == null) return i == 0 && !cartBlocks.launches(at); // track ends right ahead: brake, unless it is a jump
            if (next.getBlockData() instanceof Rail data && (Track.curve(data.getShape()) || Track.slope(data.getShape()))) return true;
            at = next;
        }
        return false;
    }

    private void route(Minecart cart, Block rail, BlockFace heading) {
        if (rail.getType() != Material.RAIL || !(rail.getBlockData() instanceof Rail data)) return;
        BlockFace exit = null;
        boolean signed = false;
        for (RailSign sign : signs(rail)) {
            if (sign.kind() != RailSign.Kind.SWITCH) continue;
            exit = sign.route(destination(cart));
            signed = true;
            break;
        }
        if (!signed) exit = autoRoute(cart, rail, heading);
        Rail.Shape shape = exit == null ? null : Track.junction(heading, exit);
        if (shape != null && data.getShapes().contains(shape) && data.getShape() != shape) {
            data.setShape(shape);
            rail.setBlockData(data, false);
        }
    }

    /** Auto-routing: at a junction without switch sign, the way towards the cart's destination station; null otherwise. */
    private BlockFace autoRoute(Minecart cart, Block rail, BlockFace heading) {
        if (!routing) return null;
        String dest = destination(cart);
        if (dest == null || dest.isBlank()) return null;
        World world = rail.getWorld();
        String key = core.compat().platform().getBlockKey(rail) + ":" + heading + ":" + dest.toLowerCase(Locale.ROOT);
        long revision = network(world).revision();
        Optional<BlockFace> cached = routes.get(world.getUID(), revision, key);
        if (cached != null) return cached.orElse(null);
        Router.Grid grid = new WorldGrid(world);
        BlockFace exit = null;
        if (Router.junction(grid, rail.getX(), rail.getY(), rail.getZ())) {
            List<Router.Target> targets = new ArrayList<>();
            for (Network.Station station : network(world).stations())
                if (station.name().equalsIgnoreCase(dest)) targets.add(new Router.Target(station.x(), station.y(), station.z()));
            exit = Router.exit(grid, rail.getX(), rail.getY(), rail.getZ(), heading, targets, routingLimit);
        }
        routes.put(world.getUID(), revision, key, exit);
        return exit;
    }

    /** The loaded world for the router; never loads chunks. */
    private record WorldGrid(World world) implements Router.Grid {
        @Override public boolean loaded(int x, int z) { return world.isChunkLoaded(x >> 4, z >> 4); }

        @Override public Rail.Shape rail(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return null;
            Block block = world.getBlockAt(x, y, z);
            return isRail(block.getType()) && block.getBlockData() instanceof Rail data ? data.getShape() : null;
        }

        @Override public boolean plain(int x, int y, int z) {
            return y >= world.getMinHeight() && y < world.getMaxHeight() && world.getBlockAt(x, y, z).getType() == Material.RAIL;
        }
    }

    private void act(Minecart cart, Block rail, RailSign sign, BlockFace heading) {
        PersistentDataContainer data = cart.getPersistentDataContainer();
        switch (sign.kind()) {
            case SPEED -> {
                if (sign.speed() <= 0) data.remove(speedKey);
                else data.set(speedKey, PersistentDataType.DOUBLE, Math.min(sign.speed(), cap));
            }
            case DEST -> {
                if (destinationsOff) return;
                data.set(destKey, PersistentDataType.STRING, sign.text());
                for (Entity rider : cart.getPassengers())
                    if (rider instanceof Player player) core.compat().platform().sendActionBar(player, lang.component(player, "dest-set", "dest", sign.text()));
            }
            case COLLECT -> {
                if (cart instanceof RideableMinecart && cart.getPassengers().isEmpty() && !trains.coupled(cart.getUniqueId())) collect(cart);
            }
            case STATION -> {
                network(rail.getWorld()).station(new Network.Station(sign.text(), rail.getX(), rail.getY(), rail.getZ()));
                if (holds.containsKey(cart.getUniqueId())) return;
                BlockFace onwards = sign.direction() != null ? sign.direction() : heading;
                double speed = Math.max(cart.getVelocity().length(), Math.max(defaultSpeed, 0.2));
                cart.setVelocity(new Vector());
                Location center = rail.getLocation().add(0.5, cart.getLocation().getY() - rail.getY(), 0.5);
                center.setYaw(cart.getLocation().getYaw());
                core.compat().passengers().teleportRetainingPassengers(cart, center);
                boolean arrived = arrived(sign.text(), destination(cart));
                long until = System.currentTimeMillis() + sign.seconds() * 1000L;
                TrainLines active = activeLines();
                boolean scheduled = active != null && active.bound(cart);
                long departure = active == null ? -1 : active.arrive(cart, sign.text(), until, onwards, speed);
                if (departure == 0) return;
                holds.put(cart.getUniqueId(), new Hold(departure > 0 ? departure : until, onwards, speed, sign.text()));
                long waitSeconds = Math.max(0, ((departure > 0 ? departure : until) - System.currentTimeMillis() + 999) / 1000);
                for (Entity rider : cart.getPassengers())
                    if (rider instanceof Player player) {
                        String name = sign.text().isBlank() ? lang.plain(player, "station-unnamed") : sign.text();
                        core.compat().platform().sendActionBar(player, lang.component(player, "station-arrive", "station", name, "seconds", Long.toString(waitSeconds)));
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.2f);
                        if (arrived && arrivalTitle) {
                            core.compat().platform().showTitle(player, net.kyori.adventure.title.Title.title(lang.component(player, "arrival-title", "station", name),
                                    lang.component(player, arrivalClear && !scheduled ? "arrival-subtitle-cleared" : "arrival-subtitle", "station", name)));
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.0f);
                        }
                    }
                if (arrived && arrivalClear && !scheduled) clearDestination(cart);
            }
            case FREIGHT -> {
                if (!(cart instanceof org.bukkit.inventory.InventoryHolder holder) || holds.containsKey(cart.getUniqueId()) || !freightEnabled()) return;
                org.bukkit.inventory.Inventory container = freightContainer(rail);
                if (container == null) return;
                int limit = Math.clamp(getConfig().getInt("freight.items-per-stop", 256), 1, 3456);
                int moved = 0;
                if (!sign.text().equals("load")) moved += Freight.move(holder.getInventory(), container, limit);
                if (!sign.text().equals("unload")) moved += Freight.move(container, holder.getInventory(), limit - moved);
                freightMoved += moved;
                double speed = Math.max(cart.getVelocity().length(), Math.max(defaultSpeed, 0.2));
                if (sign.seconds() > 0) {
                    cart.setVelocity(new Vector());
                    holds.put(cart.getUniqueId(), new Hold(System.currentTimeMillis() + sign.seconds() * 1000L, heading, speed));
                }
            }
            case SWITCH -> { }
        }
    }

    private long freightMoved;

    boolean freightEnabled() { return getConfig().getBoolean("freight.enabled", true); }

    /** The nearest chest, barrel, hopper or other container next to the rail or its support block, or null. */
    private org.bukkit.inventory.Inventory freightContainer(Block rail) {
        Block support = rail.getRelative(BlockFace.DOWN);
        for (Block base : new Block[] {rail, support})
            for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.DOWN}) {
                Block near = base.getRelative(face);
                if (!loaded(near)) continue;
                if (core.compat().platform().getState(near, false) instanceof org.bukkit.block.Container container) return container.getInventory();
            }
        return null;
    }

    // ---- tickets and fares ----------------------------------------------------------------------------------------

    boolean faresEnabled() { return fares != null && getConfig().getBoolean("fares.enabled", true); }

    private Vystorm.vystorm_core._integrations.vault.VaultInterface economy() {
        try { var vault = core.vault(); return vault != null && vault.isEconomyAvailable() ? vault : null; }
        catch (RuntimeException | LinkageError none) { return null; }
    }

    /** Riders of a cart leaving a fare station pay (or show a day ticket); who cannot pay stays at the station. */
    private void chargeFares(Minecart cart, String station) {
        if (!faresEnabled()) return;
        double fare = fares.fare(station);
        if (fare <= 0) return;
        var economy = economy();
        if (economy == null) return;
        long now = System.currentTimeMillis();
        String payee = getConfig().getString("fares.payee", "");
        for (Entity rider : List.copyOf(cart.getPassengers())) {
            if (!(rider instanceof Player player) || player.hasPermission("vystormrails.fare.free")) continue;
            if (fares.ticket(player.getUniqueId(), now) > 0) continue;
            if (!economy.has(player, fare) || !economy.withdraw(player, fare).successful()) {
                cart.removePassenger(player);
                lang.send(player, "fare.unpaid", "station", station, "fare", economy.format(fare), "ticket", economy.format(dayTicketPrice()));
                continue;
            }
            if (payee != null && !payee.isBlank()) {
                // Core's platform part: Bukkit.getOfflinePlayerIfCached is Paper API, Spigot has no such method
                org.bukkit.OfflinePlayer to = core.compat().platform().getOfflinePlayerIfCached(payee.strip());
                if (to != null) economy.deposit(to, fare);
            }
            core.compat().platform().sendActionBar(player, lang.component(player, "fare.paid", "fare", economy.format(fare), "station", station));
        }
    }

    private double dayTicketPrice() { double p = getConfig().getDouble("fares.day-ticket", 50); return Double.isFinite(p) ? Math.max(0, p) : 50; }

    /**
     * What the station view of the panel and the station chest show (1.23.0): the departures when scheduled lines are
     * on, the fare and the day ticket when fares are on and the player may buy tickets. Null when there is nothing.
     */
    RailsPanel.Board board(Player player, String station) {
        List<TrainLines.Departure> departures = lines != null && RailSwitches.on(getConfig(), RailSwitches.LINES)
                ? lines.board(player.getWorld().getUID(), station, System.currentTimeMillis()) : null;
        boolean tickets = faresEnabled() && player.hasPermission("vystormrails.ticket");
        if (departures == null && !tickets) return null;
        var economy = economy();
        double fare = tickets ? fares.fare(station) : 0;
        long now = System.currentTimeMillis(), until = tickets ? fares.ticket(player.getUniqueId(), now) : 0;
        return new RailsPanel.Board(departures, tickets,
                fare <= 0 ? null : economy == null ? String.valueOf(fare) : economy.format(fare),
                economy == null ? "-" : economy.format(dayTicketPrice()),
                until > 0 ? Math.max(1, (until - now + 3_599_999) / 3_600_000) : 0);
    }

    /** {@code /rails ticket [buy]}: shows the day ticket or buys one. */
    void ticket(Player player, boolean buy) {
        if (!faresEnabled()) { lang.send(player, RailSwitches.OFF); return; }
        var economy = economy();
        long now = System.currentTimeMillis();
        long until = fares.ticket(player.getUniqueId(), now);
        if (!buy) {
            if (until > 0) lang.send(player, "fare.ticket-valid", "hours", Math.max(1, (until - now + 3_599_999) / 3_600_000));
            else lang.send(player, "fare.ticket-none", "price", economy == null ? "-" : economy.format(dayTicketPrice()));
            return;
        }
        double price = dayTicketPrice();
        if (price > 0 && economy == null) { lang.send(player, "fare.no-economy"); return; }
        if (price > 0 && (!economy.has(player, price) || !economy.withdraw(player, price).successful())) { lang.send(player, "fare.too-poor", "price", economy.format(price)); return; }
        try { until = fares.buy(player.getUniqueId(), now, 24); }
        catch (java.io.IOException failure) {
            if (price > 0) economy.deposit(player, price);
            getLogger().warning("fares.yml not saved: " + failure.getMessage()); lang.send(player, "fare.storage"); return;
        }
        lang.send(player, "fare.ticket-bought", "price", price > 0 ? economy.format(price) : "0", "hours", Math.max(1, (until - now + 3_599_999) / 3_600_000));
    }

    /** {@code /rails fare [station] [amount|off]}: lists fares or sets one (admins). */
    void fare(org.bukkit.command.CommandSender sender, String station, String amountText) {
        if (fares == null) return;
        var economy = economy();
        if (station == null) {
            if (fares.all().isEmpty()) { lang.send(sender, "fare.list-none"); return; }
            fares.all().forEach((name, amount) -> lang.send(sender, "fare.row", "station", name, "fare", economy == null ? String.valueOf(amount) : economy.format(amount)));
            return;
        }
        if (amountText == null) { lang.send(sender, "fare.row", "station", station, "fare", economy == null ? String.valueOf(fares.fare(station)) : economy.format(fares.fare(station))); return; }
        double amount;
        if (amountText.equalsIgnoreCase("off") || amountText.equals("0")) amount = 0;
        else try { amount = Double.parseDouble(amountText.replace(',', '.')); } catch (NumberFormatException bad) { lang.send(sender, "fare.usage"); return; }
        if (!Double.isFinite(amount) || amount < 0 || amount > 1_000_000) { lang.send(sender, "fare.usage"); return; }
        try { fares.set(station, amount); }
        catch (java.io.IOException failure) { lang.send(sender, "fare.storage"); return; }
        lang.send(sender, amount > 0 ? "fare.set" : "fare.cleared", "station", station, "fare", economy == null ? String.valueOf(amount) : economy.format(amount));
    }

    private void launch(Minecart cart, BlockFace direction, double speed) {
        if (direction == null) return;
        double value = Double.isNaN(speed) || speed <= 0 ? Math.max(cart.getMaxSpeed(), defaultSpeed) : Math.min(speed, cap);
        if (!Double.isNaN(speed) && speed > 0) cart.setMaxSpeed(Math.max(cart.getMaxSpeed(), value));
        cart.setVelocity(direction.getDirection().multiply(value));
    }

    private void tickHolds() {
        if (holds.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Hold>> it = holds.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Entity entity = getServer().getEntity(entry.getKey());
            if (!(entity instanceof Minecart cart) || !cart.isValid()) { it.remove(); continue; }
            Hold hold = entry.getValue();
            if (now < hold.until()) {
                cart.setVelocity(new Vector());
                long left = (hold.until() - now + 999) / 1000;
                if (now / 50 % 20 == 0)
                    for (Entity rider : cart.getPassengers())
                        if (rider instanceof Player player) core.compat().platform().sendActionBar(player, lang.component(player, "station-countdown", "seconds", Long.toString(left)));
                continue;
            }
            it.remove();
            if (hold.station() != null) chargeFares(cart, hold.station());
            if (activeLines() != null && !activeLines().depart(cart)) continue;
            launch(cart, hold.direction(), hold.speed());
            for (Entity rider : cart.getPassengers())
                if (rider instanceof Player player) player.playSound(player.getLocation(), Sound.ENTITY_MINECART_RIDING, 0.4f, 1.4f);
        }
    }

    private void tickSpeedometer() {
        if (!speedometer) return;
        for (Player player : getServer().getOnlinePlayers()) {
            if (!(player.getVehicle() instanceof Minecart cart) || holds.containsKey(cart.getUniqueId())) continue;
            double speed = cart.getVelocity().length();
            if (speed < 0.02) continue;
            String dest = destination(cart), kmh = Integer.toString(Track.kmh(speed)), max = Integer.toString(Track.kmh(cart.getMaxSpeed()));
            Location at = cart.getLocation();
            Network.Station target = dest == null ? null : network(cart.getWorld()).nearest(dest, at.getBlockX(), at.getBlockZ());
            core.compat().platform().sendActionBar(player, dest == null ? lang.component(player, "speedometer", "kmh", kmh, "max", max)
                    : target == null ? lang.component(player, "speedometer-dest", "kmh", kmh, "max", max, "dest", dest)
                    : lang.component(player, "speedometer-dest-distance", "kmh", kmh, "max", max, "dest", dest,
                            "distance", Integer.toString(Network.distance(target, at.getBlockX(), at.getBlockZ()))));
        }
    }

    // ---- signs ----------------------------------------------------------------------------------------------------

    /** The rail at this block, or the one in the block above when a cart's position rounds down into the support. */
    static Block railAt(Block block) {
        if (!loaded(block)) return null;
        if (isRail(block.getType())) return block;
        Block up = block.getRelative(BlockFace.UP);
        return isRail(up.getType()) ? up : null;
    }

    static boolean loaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    static boolean isRail(Material material) {
        return material == Material.RAIL || material == Material.POWERED_RAIL || material == Material.DETECTOR_RAIL || material == Material.ACTIVATOR_RAIL;
    }

    /** Signs for a rail: two blocks below it, or on a side of the block that carries it. */
    private List<RailSign> signs(Block rail) {
        if (signsOff) return List.of();   // signs.enabled false (1.21.0): no sign acts on carts
        List<RailSign> found = new ArrayList<>(1);
        Block support = rail.getRelative(BlockFace.DOWN);
        sign(support.getRelative(BlockFace.DOWN)).ifPresent(found::add);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Block side = support.getRelative(face);
            if (loaded(side) && side.getBlockData() instanceof org.bukkit.block.data.type.WallSign wall && wall.getFacing() == face) sign(side).ifPresent(found::add);
        }
        return found;
    }

    private Optional<RailSign> sign(Block block) {
        if (!loaded(block)) return Optional.empty();
        SignKey key = SignKey.of(core.compat().platform(), block);
        Optional<RailSign> cached = signCache.get(key);
        if (cached != null) return cached;
        Optional<RailSign> parsed = Optional.empty();
        if (core.compat().platform().getState(block, false) instanceof Sign sign) {
            String[] text = SignText.plainLines(sign.getSide(Side.FRONT));
            parsed = Optional.ofNullable(RailSign.parse(header, text));
        }
        if (signCache.size() > 50_000) signCache.clear();
        signCache.put(key, parsed);
        return parsed;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        signCache.remove(SignKey.of(core.compat().platform(), event.getBlock()));
        if (signsOff) return;
        String first = PlainTextComponentSerializer.plainText().serialize(Optional.ofNullable(SignText.line(event, 0)).orElse(Component.empty())).strip();
        if (!header.equalsIgnoreCase(first)) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("vystormrails.sign")) {
            event.setCancelled(true);
            lang.send(player, "sign-denied");
            return;
        }
        String[] text = new String[4];
        for (int i = 0; i < 4; i++) text[i] = PlainTextComponentSerializer.plainText().serialize(Optional.ofNullable(SignText.line(event, i)).orElse(Component.empty()));
        RailSign sign = RailSign.parse(header, text);
        if (sign == null) { lang.send(player, "sign-invalid"); return; }
        SignText.line(core.compat().platform(), event, 0, lang.component(player, "sign-header", "header", header));
        Block rail = railForSign(event.getBlock());
        if (sign.kind() == RailSign.Kind.STATION && rail != null)
            network(rail.getWorld()).station(new Network.Station(sign.text(), rail.getX(), rail.getY(), rail.getZ()));
        lang.send(player, "sign-created", "action", sign.kind().name().toLowerCase(Locale.ROOT));
        getServer().getScheduler().runTask(this, () -> signCache.remove(SignKey.of(core.compat().platform(), event.getBlock())));
    }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins. Both
     * only keep the rail network and the sign cache up to date: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(EventStages stages) {
        stages.handle(this, BlockBreakEvent.class, EventStage.OBSERVE, this::onBreak);
        stages.handle(this, BlockPlaceEvent.class, EventStage.OBSERVE, this::onPlace);
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        signCache.remove(SignKey.of(core.compat().platform(), block));
        if (isRail(block.getType())) { network(block.getWorld()).remove(block.getX(), block.getZ()); routes.clear(); }
        Block rail = railForSign(block);
        if (rail != null) network(block.getWorld()).removeStation(rail.getX(), rail.getY(), rail.getZ());
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        signCache.remove(SignKey.of(core.compat().platform(), block));
        if (isRail(block.getType())) { network(block.getWorld()).add(block.getX(), block.getZ()); routes.clear(); }
    }

    /** The rail a sign belongs to: two blocks above a standing sign, above the block a wall sign hangs on; null otherwise. */
    static Block railForSign(Block sign) {
        if (sign.getBlockData() instanceof org.bukkit.block.data.type.WallSign wall) return sign.getRelative(wall.getFacing().getOppositeFace()).getRelative(BlockFace.UP);
        if (sign.getBlockData() instanceof org.bukkit.block.data.type.Sign) return sign.getRelative(BlockFace.UP, 2);
        return null;
    }

    // ---- network map ----------------------------------------------------------------------------------------------

    Lang lang() { return lang; }

    Network network(World world) {
        return networks.computeIfAbsent(world.getUID(), uid -> {
            Path file = networkFile(uid);
            if (Files.isRegularFile(file)) {
                Network read = Network.read(file, getLogger()::severe);
                if (read != null) return read;
                brokenNetworks.add(uid); // never save over the unreadable map
            }
            return new Network();
        });
    }

    private Path networkFile(UUID world) { return getDataFolder().toPath().resolve("network").resolve(world + ".bin"); }

    private void saveNetworks(boolean async) {
        for (var entry : networks.entrySet()) {
            if (!entry.getValue().dirty() || brokenNetworks.contains(entry.getKey())) continue;
            Network network = entry.getValue();
            byte[] bytes = network.save();
            long revision = network.revision();
            Path file = networkFile(entry.getKey());
            Runnable write = () -> {
                try { network.write(core.data().files(), file, revision, bytes); }
                catch (IOException failed) { getLogger().warning("Could not save " + file.getFileName() + ": " + failed.getMessage()); }
            };
            if (async) getServer().getScheduler().runTaskAsynchronously(this, write); else write.run();
        }
    }

    /** Loaded station signs must still match; removed stations are forgotten and renamed ones refreshed. */
    boolean stationStillThere(World world, Network.Station station) {
        if (!world.isChunkLoaded(station.x() >> 4, station.z() >> 4)) return true;
        for (RailSign sign : signs(world.getBlockAt(station.x(), station.y(), station.z())))
            if (sign.kind() == RailSign.Kind.STATION) {
                if (!sign.text().equals(station.name())) {
                    network(world).station(new Network.Station(sign.text(), station.x(), station.y(), station.z()));
                    return false;
                }
                return true;
            }
        network(world).removeStation(station.x(), station.y(), station.z());
        return false;
    }

    String destination(Player player) { return destinationsOff ? null : player.getPersistentDataContainer().get(destKey, PersistentDataType.STRING); }

    /** The named station closest to the player in their world, or null when there is none. */
    Network.Station nearestStation(Player player) {
        World world = player.getWorld();
        Network network = network(world);
        Network.Station station;
        while ((station = network.nearest(player.getLocation().getBlockX(), player.getLocation().getBlockZ())) != null && !stationStillThere(world, station)) { }
        return station;
    }

    /** Sets or (null) clears the player's destination and that of the cart they ride. */
    void setDestination(Player player, String dest) {
        if (off(player, RailSwitches.DESTINATIONS)) return;
        Minecart cart = player.getVehicle() instanceof Minecart riding ? riding : null;
        if (dest == null) {
            player.getPersistentDataContainer().remove(destKey);
            if (cart != null) cart.getPersistentDataContainer().remove(destKey);
            lang.send(player, "dest-cleared");
            return;
        }
        player.getPersistentDataContainer().set(destKey, PersistentDataType.STRING, dest);
        if (cart != null) cart.getPersistentDataContainer().set(destKey, PersistentDataType.STRING, dest);
        lang.send(player, "dest-set", "dest", dest);
    }

    // ---- carts and riders -----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCartPlace(EntityPlaceEvent event) {
        if (event.getEntity() instanceof Minecart cart && event.getPlayer() != null)
            cart.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !(event.getEntered() instanceof Player player)) return;
        if (activeLines() != null && activeLines().bound(cart)) return;
        String dest = player.getPersistentDataContainer().get(destKey, PersistentDataType.STRING);
        if (dest != null) cart.getPersistentDataContainer().set(destKey, PersistentDataType.STRING, dest);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (!returnOnExit || !(event.getVehicle() instanceof RideableMinecart cart) || !(event.getExited() instanceof Player player)) return;
        if (!survival(player) || !player.hasPermission("vystormrails.use") || !ownedBy(cart, player) || trains.coupled(cart.getUniqueId())) return;
        getServer().getScheduler().runTask(this, () -> {
            if (cart.isValid() && cart.getPassengers().isEmpty() && player.isOnline()) {
                holds.remove(cart.getUniqueId());
                cart.remove();
                give(player, cart);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        if (!returnOnBreak || !(event.getVehicle() instanceof RideableMinecart cart) || !(event.getAttacker() instanceof Player player)) return;
        if (!survival(player) || !player.hasPermission("vystormrails.use")) return;
        event.setCancelled(true);
        cart.eject();
        holds.remove(cart.getUniqueId());
        cart.remove();
        give(player, cart);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCollide(VehicleEntityCollisionEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) return;
        Entity other = event.getEntity();
        if (other instanceof Minecart && trains.sameTrain(cart.getUniqueId(), other.getUniqueId())) { event.setCollisionCancelled(true); return; }
        boolean ridden = !cart.getPassengers().isEmpty();
        if (other instanceof Player) {
            if (passPlayers && ridden) { event.setCollisionCancelled(true); event.setPickupCancelled(true); }
            return;
        }
        if (!ridden) {
            if (!emptyPickup && other instanceof LivingEntity) event.setPickupCancelled(true);
            return;
        }
        if (!pushMobs || !(other instanceof LivingEntity living) || other instanceof Minecart) return;
        Vector velocity = cart.getVelocity();
        if (velocity.length() < pushSpeed) return;
        event.setCollisionCancelled(true);
        event.setPickupCancelled(true);
        Vector side = new Vector(-velocity.getZ(), 0, velocity.getX()).normalize();
        if (side.dot(living.getLocation().toVector().subtract(cart.getLocation().toVector())) < 0) side.multiply(-1);
        living.setVelocity(side.multiply(0.6).add(velocity.clone().multiply(0.5)).setY(0.35));
        if (mobDamage > 0) {
            Entity rider = cart.getPassengers().getFirst();
            living.damage(mobDamage, rider);
        }
    }

    private void collect(Minecart cart) {
        holds.remove(cart.getUniqueId());
        String owner = cart.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        Player player = null;
        try { if (owner != null) player = getServer().getPlayer(UUID.fromString(owner)); } catch (IllegalArgumentException ignored) { }
        Location at = cart.getLocation();
        cart.remove();
        if (player != null && survival(player)) give(player, cart);
        else at.getWorld().dropItemNaturally(at, new ItemStack(cartMaterial(cart)));
    }

    private static Material cartMaterial(Minecart cart) {
        return switch (cart.getType().name()) {
            case "CHEST_MINECART", "MINECART_CHEST" -> Material.CHEST_MINECART;
            case "FURNACE_MINECART", "MINECART_FURNACE" -> Material.FURNACE_MINECART;
            case "HOPPER_MINECART", "MINECART_HOPPER" -> Material.HOPPER_MINECART;
            case "TNT_MINECART", "MINECART_TNT" -> Material.TNT_MINECART;
            case "COMMAND_BLOCK_MINECART", "MINECART_COMMAND" -> Material.COMMAND_BLOCK_MINECART;
            default -> Material.MINECART;
        };
    }

    private void give(Player player, Minecart cart) {
        ItemStack item = new ItemStack(cartMaterial(cart));
        for (ItemStack rest : player.getInventory().addItem(item).values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1f);
    }

    private boolean ownedBy(Minecart cart, Player player) {
        return player.getUniqueId().toString().equals(cart.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
    }

    boolean lineAssignable(Minecart cart, Player player) {
        return !trains.trailing(cart.getUniqueId()) && (ownedBy(cart, player) || player.hasPermission("vystormrails.admin"));
    }
    void lineHold(Minecart cart, long until, BlockFace direction, double speed) {
        holds.put(cart.getUniqueId(), new Hold(until, direction, speed));
    }
    void cancelLineHold(Minecart cart) { holds.remove(cart.getUniqueId()); }
    void cartDestination(Minecart cart, String destination) {
        if (destination == null) cart.getPersistentDataContainer().remove(destKey);
        else cart.getPersistentDataContainer().set(destKey, PersistentDataType.STRING, destination);
    }

    private static boolean survival(Player player) { return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE; }

    /** The cart's destination, else that of a rider; in a train the first one found along the train. */
    String destination(Minecart cart) {
        if (destinationsOff) return null;   // destinations.enabled false (1.21.0): carts ride without a destination
        String dest = ownDestination(cart);
        if (dest == null && trains.coupled(cart.getUniqueId()))
            for (UUID id : trains.train(cart.getUniqueId()))
                if (!id.equals(cart.getUniqueId()) && getServer().getEntity(id) instanceof Minecart other && (dest = ownDestination(other)) != null) break;
        return dest;
    }

    /** True when a station with this name is the destination (names ignore case and surrounding spaces). */
    static boolean arrived(String station, String destination) {
        return station != null && destination != null && !station.isBlank() && station.strip().equalsIgnoreCase(destination.strip());
    }

    /** Removes the destination from the cart, every cart of its train and their riders: the journey is over. */
    private void clearDestination(Minecart cart) {
        List<UUID> ids = trains.coupled(cart.getUniqueId()) ? new ArrayList<>(trains.train(cart.getUniqueId())) : List.of(cart.getUniqueId());
        for (UUID id : ids) {
            if (!(getServer().getEntity(id) instanceof Minecart member)) continue;
            member.getPersistentDataContainer().remove(destKey);
            for (Entity rider : member.getPassengers())
                if (rider instanceof Player player) player.getPersistentDataContainer().remove(destKey);
        }
    }

    private String ownDestination(Minecart cart) {
        String dest = cart.getPersistentDataContainer().get(destKey, PersistentDataType.STRING);
        if (dest == null)
            for (Entity rider : cart.getPassengers())
                if (rider instanceof Player player) dest = player.getPersistentDataContainer().get(destKey, PersistentDataType.STRING);
        return dest;
    }

    // ---- command --------------------------------------------------------------------------------------------------

    /**
     * {@code /rails} as a Vystorm Core command tree: names, aliases, usage and help come from {@code commands.rails.*}
     * in the language files (e.g. {@code /schienen ziel}, {@code /铁轨 目的地}); English always works.
     */
    /**
     * Lists a subcommand in tab completion and help only while its switch is on (1.21.0). Core asks at every
     * completion and reads the current config, so a change applies at once; the command still answers the off
     * message. Without a plugin (tests) the node is unchanged.
     */
    private static CommandSpec.Builder hide(RailsPlugin p, CommandSpec.Builder sub, String key) {
        return p == null ? sub : sub.visibleWhen(() -> RailSwitches.on(p.getConfig(), key));
    }

    /** The switches that decide what {@code /rails} lists in tab completion and help. */
    static final List<String> LISTED = List.of(RailSwitches.DESTINATIONS, RailSwitches.MAP, RailSwitches.LINES,
            RailSwitches.STATIONS, RailSwitches.FAVORITES, "fares.enabled", RailSwitches.INFO, "trains.enabled");

    static List<Boolean> listing(org.bukkit.configuration.ConfigurationSection config) {
        return LISTED.stream().map(key -> RailSwitches.on(config, key)).toList();
    }

    /** After a reload: sends players their command list again when a listed switch changed (Core 0.65.0). */
    void refreshIfChanged(List<Boolean> before) {
        if (core != null && !before.equals(listing(getConfig()))) core.command().refresh();
    }

    static CommandSpec commandSpec(RailsPlugin p) {
        return CommandSpec.command("rails")
                .permission("vystormrails.use")
                .aliases("vrails", "cart")
                .executes(ctx -> { if (ctx.sender() instanceof Player player) p.openNetwork(player); else ctx.sender().sendMessage("/rails help"); })
                .sub("dest", s -> hide(p, s, RailSwitches.DESTINATIONS).aliases("destination").playerOnly()
                        .arg(Arg.greedy("name").optional().suggests((ctx, prefix) -> {
                            List<String> options = new ArrayList<>(List.of(ctx.valueName("clear")));
                            for (Network.Station station : p.network(ctx.player().getWorld()).stations())
                                if (!station.name().isBlank() && !options.contains(station.name())) options.add(station.name());
                            return options;
                        }))
                        .executes(ctx -> p.destination(ctx.player(), ctx.get("name"), ctx.keyword(ctx.get("name"), "clear") != null)))
                .sub("map", s -> hide(p, s, RailSwitches.MAP).playerOnly().executes(ctx -> p.openNetwork(ctx.player())))
                .sub("line", s -> hide(p, s, RailSwitches.LINES).playerOnly().arg(Arg.greedy("action").optional().suggests((ctx, prefix) -> {
                    String[] raw = ctx.rawArgs();
                    return TrainLines.suggest(raw.length - 2, raw.length > 2 ? raw[1] : null, p.lines == null ? List.of() : p.lines.lineIds(ctx.player().getWorld()));
                })).executes(ctx -> {
                    if (p.off(ctx.player(), RailSwitches.LINES)) return;
                    if (p.lines == null) ctx.reply("line-save-failed"); else p.lines.command(ctx.player(), ctx.get("action"));
                }))
                .sub("departures", s -> hide(p, s, RailSwitches.LINES).playerOnly().arg(Arg.greedy("station").optional().suggests((ctx, prefix) ->
                        p.network(ctx.player().getWorld()).stations().stream().map(Network.Station::name)
                                .filter(name -> !name.isBlank()).distinct().toList())).executes(ctx -> {
                    if (p.off(ctx.player(), RailSwitches.LINES)) return;
                    if (p.lines == null) ctx.reply("line-save-failed"); else p.lines.departures(ctx.player(), ctx.get("station"));
                }))
                .sub("stations", s -> hide(p, s, RailSwitches.STATIONS).playerOnly().arg(Arg.greedy("search").optional())
                        .executes(ctx -> p.stations(ctx.player(), ctx.get("search"))))
                .sub("favorite", s -> hide(p, s, RailSwitches.FAVORITES).playerOnly().arg(Arg.greedy("name").suggests((ctx, prefix) ->
                        p.network(ctx.player().getWorld()).stations().stream().map(Network.Station::name)
                                .filter(name -> !name.isBlank()).distinct().toList()))
                        .executes(ctx -> p.favorite(ctx.player(), ctx.get("name"))))
                .sub("favorites", s -> hide(p, s, RailSwitches.FAVORITES).playerOnly().executes(ctx -> p.openFavorites(ctx.player())))
                .sub("ticket", s -> hide(p, s, "fares.enabled").permission("vystormrails.ticket").playerOnly().executes(ctx -> p.ticket(ctx.player(), false))
                        .sub("buy", b -> b.executes(ctx -> p.ticket(ctx.player(), true))))
                .sub("fare", s -> s.permission("vystormrails.fare").arg(Arg.word("station").optional().suggests((ctx, prefix) -> ctx.sender() instanceof Player player
                                ? p.network(player.getWorld()).stations().stream().map(Network.Station::name).filter(name -> !name.isBlank() && !name.contains(" ")).distinct().toList() : List.of()))
                        .arg(Arg.word("amount").optional().suggests(List.of("off", "5", "10")))
                        .executes(ctx -> p.fare(ctx.sender(), ctx.get("station"), ctx.get("amount"))))
                .sub("info", s -> hide(p, s, RailSwitches.INFO).playerOnly().executes(ctx -> p.info(ctx.player())))
                .sub("blocks", s -> hide(p, s, RailSwitches.INFO).executes(ctx -> p.blocks(ctx.sender())))
                .sub("uncouple", s -> hide(p, s, "trains.enabled").permission("vystormrails.train").playerOnly().executes(ctx -> {
                    if (p.off(ctx.player(), "trains.enabled")) return;
                    Minecart cart = p.cartOf(ctx.player());
                    if (cart == null) throw ctx.fail("no-cart");
                    ctx.reply(p.trains.uncouple(cart) ? "train-uncoupled" : "train-not-coupled");
                }))
                .sub("reload", s -> s.permission("vystormrails.admin").executes(ctx -> {
                    String problem = p.load();
                    if (problem != null) ctx.reply("config-error", "problem", problem);
                    else ctx.reply("reloaded", "blocks", Integer.toString(p.blockSpeeds.size()));
                }))
                .build();
    }

    Network.Station favoriteStation(Player player, String name) {
        Network network = network(player.getWorld());
        Location at = player.getLocation();
        for (Network.Station station : network.search(name, at.getBlockX(), at.getBlockZ())) {
            if (!station.name().equalsIgnoreCase(name.strip())) continue;
            if (stationStillThere(player.getWorld(), station) && network.stations().contains(station)) return station;
        }
        return null;
    }

    void favorite(Player player, String name) {
        if (off(player, RailSwitches.FAVORITES)) return;
        Network.Station station = favoriteStation(player, name);
        if (station == null) { lang.send(player, "favorites-missing"); return; }
        UUID world = player.getWorld().getUID();
        List<String> names = Favorites.read(player, world);
        Favorites.Result result = Favorites.add(names, station.name(), favoritesLimit);
        if (result == Favorites.Result.ADDED) Favorites.save(player, world, names);
        lang.send(player, switch (result) {
            case ADDED -> "favorites-added";
            case EXISTS -> "favorites-exists";
            case FULL -> "favorites-full";
        }, "station", station.name(), "limit", Integer.toString(favoritesLimit));
    }

    void stations(Player player, String query) {
        if (off(player, RailSwitches.STATIONS)) return;
        Location location = player.getLocation();
        List<Network.Station> found = network(player.getWorld()).search(query, location.getBlockX(), location.getBlockZ());
        if (found.isEmpty()) { lang.send(player, "stations-empty"); return; }
        lang.send(player, "stations-header", "shown", Integer.toString(Math.min(10, found.size())), "total", Integer.toString(found.size()));
        for (Network.Station station : found.subList(0, Math.min(10, found.size())))
            lang.send(player, "stations-entry", "station", station.name(),
                    "distance", Integer.toString(Network.distance(station, location.getBlockX(), location.getBlockZ())),
                    "x", Integer.toString(station.x()), "y", Integer.toString(station.y()), "z", Integer.toString(station.z()));
        lang.send(player, "stations-hint");
    }

    private void destination(Player player, String input, boolean clear) {
        if (off(player, RailSwitches.DESTINATIONS)) return;
        if (input == null) {
            String dest = player.getPersistentDataContainer().get(destKey, PersistentDataType.STRING);
            lang.send(player, dest == null ? "dest-none" : "dest-show", "dest", dest == null ? "" : dest);
            return;
        }
        if (clear || input.strip().equals("-")) { setDestination(player, null); return; }
        String dest = input.strip();
        setDestination(player, dest);
    }

    Minecart cartOf(Player player) {
        return player.getVehicle() instanceof Minecart riding ? riding
                : core.compat().gameplay().getTargetEntity(player, 6) instanceof Minecart looked ? looked : null;
    }

    private void info(Player player) {
        if (off(player, RailSwitches.INFO)) return;
        Minecart cart = cartOf(player);
        if (cart == null) { lang.send(player, "no-cart"); return; }
        String owner = cart.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        String ownerName = "-";
        try { if (owner != null) ownerName = Optional.ofNullable(getServer().getOfflinePlayer(UUID.fromString(owner)).getName()).orElse("?"); } catch (IllegalArgumentException ignored) { }
        Double own = cart.getPersistentDataContainer().get(speedKey, PersistentDataType.DOUBLE);
        lang.send(player, "info", "kmh", Integer.toString(Track.kmh(cart.getVelocity().length())), "max", Integer.toString(Track.kmh(cart.getMaxSpeed())),
                "speed", own == null ? "-" : Track.kmh(own) + " km/h", "dest", Optional.ofNullable(destination(cart)).orElse("-"), "owner", ownerName);
        if (trains.coupled(cart.getUniqueId())) lang.send(player, "info-train", "cars", Integer.toString(trains.length(cart.getUniqueId())));
    }

    private void blocks(CommandSender sender) {
        if (off(sender, RailSwitches.INFO)) return;
        lang.send(sender, "blocks-header");
        var speeds = getConfig().getConfigurationSection("speed.blocks");
        if (speeds != null)
            for (String key : speeds.getKeys(false)) {
                double speed = speeds.getDouble(key);
                if (!Double.isFinite(speed) || speed <= 0) continue;
                speed = Math.min(speed, cap);
                lang.send(sender, "blocks-speed", "block", CartBlocks.name(key), "kmh", Integer.toString(Track.kmh(speed)));
            }
        for (String[] line : cartBlocks.describe()) lang.send(sender, line[0], "block", line[1]);
    }
}
