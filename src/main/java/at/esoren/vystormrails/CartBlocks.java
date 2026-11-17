package at.esoren.vystormrails;


import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

/**
 * What blocks do to carts besides speed:
 * <ul>
 *   <li>ground blocks off the track: ice slides (rider steers with A/D, brakes with S), honey catches a cart</li>
 *   <li>jump: the last rail on a slime block throws the cart into the air</li>
 *   <li>bounce: a slime block behind the end of the track sends the cart back</li>
 *   <li>shift: a slime block beside the rail moves the cart onto the parallel track on the other side</li>
 *   <li>spin: a rail on magma spins the cart once and sends it back</li>
 *   <li>elevator: a column of scaffolding at the end of the track lifts or lowers the cart to the track at its other end</li>
 *   <li>float: on lava the cart swims like a boat (W/A/S/D), rider and cart do not burn</li>
 *   <li>signal: a rail on the signal block holds carts while the block is powered or a cart is on the track ahead</li>
 * </ul>
 * Vanilla off-track physics (0.5 on the ground, 0.95 in the air) is restored once the cart is back on normal ground or
 * on rails.
 */
final class CartBlocks implements Listener {
    private final Vystorm.vystorm_core.compat.CompatInterface compat;
    private final CartCompat.Controls controls;

    /** @param compat Core's compatibility manager, {@code api.compat()} */
    CartBlocks(Vystorm.vystorm_core.compat.CompatInterface compat) {
        this.compat = compat;
        this.controls = CartCompat.controls(compat.server(), Player.class);
    }

    private final CartCompat.Fire fire = CartCompat.fire(Entity.class);

    void reportCompatibility(Logger logger) {
        if (!CartCompat.hasMethod(Player.class, "getCurrentInput"))
            logger.info("Player input API unavailable: W/A/S/D cart steering disabled; ice sliding and lava floating remain enabled.");
        if (!CartCompat.hasMethod(Entity.class, "setVisualFire", net.kyori.adventure.util.TriState.class))
            logger.info("TriState visual fire API unavailable: lava carts retain vanilla flames (visual-fire: false has no effect); heat protection remains enabled.");
    }
    private static final Vector GROUND = new Vector(0.5, 0.5, 0.5), AIR = new Vector(0.95, 0.95, 0.95);
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final Set<EntityDamageEvent.DamageCause> HEAT = EnumSet.of(EntityDamageEvent.DamageCause.LAVA,
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.HOT_FLOOR);

    private record Spin(int ticksLeft, BlockFace onwards, double speed) {}
    private record Lift(int x, int z, double targetY, double step, Location exit, BlockFace onwards, double speed) {}
    private record Signal(Block rail, BlockFace onwards, double speed, int waited) {}

    private final Map<Material, Double> ground = new EnumMap<>(Material.class);
    private final Set<Material> floatOn = EnumSet.noneOf(Material.class);
    private final Set<UUID> changed = new HashSet<>(), floating = new HashSet<>();
    private final Map<UUID, Spin> spins = new HashMap<>();
    private final Map<UUID, Lift> lifts = new HashMap<>();
    private final Map<UUID, Signal> signals = new HashMap<>();
    private BiConsumer<Player, String> notice = (player, key) -> { };
    private Trains trains;
    private Material jumpBlock, bounceBlock, shiftBlock, spinBlock, liftBlock, signalBlock;
    private double jumpPower = 0.5, jumpFactor = 0.4, jumpMin = 0.2, airKeep = 0.99, bounceKeep = 0.8;
    private double steerDegrees = 4, pushSpeed = 0.3, liftSpeed = 0.25, floatSpeed = 0.35;
    private int shiftRange = 4, spinTicks = 20, liftHeight = 64, clearAhead = 8;
    private boolean steer = true, spinReverse = true, visualFire = true;

    /** Action bar text for riders, by language key ("signal-red", "signal-occupied"). */
    void notices(BiConsumer<Player, String> notice) { this.notice = notice; }

    /** Coupled carts: signals ignore the own train, the other cart blocks leave trains alone. */
    void trains(Trains trains) { this.trains = trains; }

    void load(ConfigurationSection config, Logger logger) {
        ground.clear();
        floatOn.clear();
        jumpBlock = bounceBlock = shiftBlock = spinBlock = liftBlock = signalBlock = null;
        if (config == null) return;
        ConfigurationSection grounds = config.getConfigurationSection("ground");
        if (grounds != null)
            for (String key : grounds.getKeys(false)) {
                Material material = block(key, logger);
                if (material != null) ground.put(material, Math.clamp(ConfigNumbers.read(grounds, key, 0.5), 0.0, 0.999));
            }
        steer = config.getBoolean("steer.enabled", true);
        steerDegrees = Math.clamp(ConfigNumbers.read(config, "steer.degrees-per-tick", 4), 0, 20);
        pushSpeed = Math.clamp(ConfigNumbers.read(config, "steer.push-up-to", 0.3), 0, 1);
        jumpBlock = block(config.getString("jump.block", "SLIME_BLOCK"), logger);
        jumpPower = Math.clamp(ConfigNumbers.read(config, "jump.power", 0.5), 0, 3);
        jumpFactor = Math.clamp(ConfigNumbers.read(config, "jump.speed-factor", 0.4), 0, 3);
        jumpMin = Math.max(0, ConfigNumbers.read(config, "jump.min-speed", 0.2));
        airKeep = Math.clamp(ConfigNumbers.read(config, "jump.air-keep", 0.99), 0.5, 1.0);
        bounceBlock = block(config.getString("bounce.block", "SLIME_BLOCK"), logger);
        bounceKeep = Math.clamp(ConfigNumbers.read(config, "bounce.keep", 0.8), 0, 1.5);
        shiftBlock = block(config.getString("shift.block", "SLIME_BLOCK"), logger);
        shiftRange = Math.clamp(config.getInt("shift.range", 4), 1, 8);
        spinBlock = block(config.getString("spin.block", "MAGMA_BLOCK"), logger);
        spinTicks = Math.clamp(config.getInt("spin.ticks", 20), 4, 200);
        spinReverse = config.getBoolean("spin.reverse", true);
        liftBlock = block(config.getString("elevator.block", "SCAFFOLDING"), logger);
        liftSpeed = Math.clamp(ConfigNumbers.read(config, "elevator.speed", 0.25), 0.05, 2);
        liftHeight = Math.clamp(config.getInt("elevator.max-height", 64), 2, 384);
        for (String name : config.getStringList("float.fluids")) {
            Material material = block(name, logger);
            if (material != null) floatOn.add(material);
        }
        floatSpeed = Math.clamp(ConfigNumbers.read(config, "float.speed", 0.35), 0.05, 1);
        visualFire = config.getBoolean("float.visual-fire", true);
        signalBlock = block(config.getString("signal.block", "REDSTONE_LAMP"), logger);
        clearAhead = Math.clamp(config.getInt("signal.clear-ahead", 8), 0, 32);
    }

    private static Material block(String name, Logger logger) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return null;
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) { logger.warning("cart-blocks: unknown block " + name); return null; }
        return material;
    }

    /** The cart blocks switched on, in order, as {language key, block names} for {@code /rails blocks}. */
    List<String[]> describe() {
        List<String[]> lines = new ArrayList<>();
        Set<Material> slide = new TreeSet<>(), stick = new TreeSet<>();
        ground.forEach((material, keep) -> (keep >= 0.5 ? slide : stick).add(material));
        add(lines, "blocks-slide", slide);
        add(lines, "blocks-catch", stick);
        add(lines, "blocks-jump", jumpBlock);
        add(lines, "blocks-bounce", bounceBlock);
        add(lines, "blocks-shift", shiftBlock);
        add(lines, "blocks-spin", spinBlock);
        add(lines, "blocks-elevator", liftBlock);
        add(lines, "blocks-signal", signalBlock);
        add(lines, "blocks-float", new TreeSet<>(floatOn));
        return lines;
    }

    private static void add(List<String[]> lines, String key, Material material) {
        if (material != null) add(lines, key, Set.of(material));
    }

    static void add(List<String[]> lines, String key, Set<Material> materials) {
        if (materials.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (Material material : materials) names.append(names.isEmpty() ? "" : ", ").append(name(material.name()));
        lines.add(new String[] {key, names.toString()});
    }

    /** SLIME_BLOCK -> "slime block", *COPPER -> "…copper". */
    static String name(String material) {
        String name = material.toLowerCase(Locale.ROOT).replace('_', ' ');
        return name.startsWith("*") ? "…" + name.substring(1) : name;
    }

    /** True when this rail sits on the jump block, so carts may leave the track there at full speed. */
    boolean launches(Block rail) { return jumpBlock != null && rail.getRelative(BlockFace.DOWN).getType() == jumpBlock; }

    /** True while the cart is spinning or riding an elevator; stations and speed logic leave it alone. */
    boolean busy(UUID cart) { return spins.containsKey(cart) || lifts.containsKey(cart) || signals.containsKey(cart); }

    // ---- entering blocks ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || busy(cart.getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Block rail = RailsPlugin.railAt(to.getBlock());
        Vector velocity = cart.getVelocity();
        if (trains != null && trains.coupled(cart.getUniqueId())) {
            // Trains only obey signals (at the front); jump, bounce, shift, spin, elevator and float are for single carts.
            BlockFace heading = Track.heading(velocity.getX(), velocity.getZ());
            if (rail != null && heading != null && !trains.trailing(cart.getUniqueId())) signal(cart, rail, heading, Math.hypot(velocity.getX(), velocity.getZ()));
            return;
        }
        if (rail != null) {
            if (changed.remove(cart.getUniqueId())) restore(cart);
            BlockFace heading = Track.heading(velocity.getX(), velocity.getZ());
            if (heading == null) heading = Track.heading(to.getX() - from.getX(), to.getZ() - from.getZ());
            if (heading != null) onRail(cart, rail, heading, velocity);
            return;
        }
        // Leaving the track over a jump block: up into the air, and far.
        Block left = RailsPlugin.railAt(from.getBlock());
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        if (left != null && launches(left) && horizontal >= jumpMin) {
            cart.setVelocity(velocity.setY(Math.max(velocity.getY(), 0) + jumpPower + horizontal * jumpFactor));
            cart.setFlyingVelocityMod(new Vector(airKeep, 0.98, airKeep));
            changed.add(cart.getUniqueId());
            to.getWorld().playSound(to, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.8f, 0.8f);
            return;
        }
        if (fluid(to.getBlock()) || fluid(to.getBlock().getRelative(BlockFace.DOWN))) {
            if (floating.add(cart.getUniqueId())) {
                cart.setGravity(false);
                cart.setMaxSpeed(Math.max(cart.getMaxSpeed(), floatSpeed * 2));
                if (!visualFire) fire.suppress(cart, true);
                to.getWorld().playSound(to, Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.4f);
            }
            return;
        }
        Double keep = ground.get(to.getBlock().getRelative(BlockFace.DOWN).getType());
        if (keep == null) keep = ground.get(to.getBlock().getType()); // carts sink a little into some blocks
        if (keep != null) {
            cart.setDerailedVelocityMod(new Vector(keep, keep, keep));
            changed.add(cart.getUniqueId());
        } else if (cart.isOnGround() && changed.remove(cart.getUniqueId())) {
            restore(cart);
        }
    }

    private void onRail(Minecart cart, Block rail, BlockFace heading, Vector velocity) {
        Block ahead = rail.getRelative(heading);
        boolean end = RailsPlugin.loaded(ahead) && RailsPlugin.railAt(ahead) == null && RailsPlugin.railAt(ahead.getRelative(BlockFace.DOWN)) == null;
        double speed = Math.hypot(velocity.getX(), velocity.getZ());
        if (end && bounceBlock != null && ahead.getType() == bounceBlock) {
            cart.setVelocity(velocity.multiply(-bounceKeep));
            rail.getWorld().playSound(rail.getLocation(), Sound.BLOCK_SLIME_BLOCK_FALL, 0.8f, 1.2f);
            return;
        }
        if (end && liftBlock != null && ahead.getType() == liftBlock && startLift(cart, rail, ahead, heading, speed)) return;
        if (signal(cart, rail, heading, speed)) return;
        if (spinBlock != null && rail.getRelative(BlockFace.DOWN).getType() == spinBlock) {
            hold(cart, rail);
            spins.put(cart.getUniqueId(), new Spin(spinTicks, spinReverse ? heading.getOppositeFace() : heading, Math.max(speed, 0.3)));
            rail.getWorld().playSound(rail.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.4f, 1.6f);
            return;
        }
        if (shiftBlock != null) {
            for (BlockFace side : new BlockFace[] {left(heading), left(heading).getOppositeFace()}) {
                if (!RailsPlugin.loaded(rail.getRelative(side)) || rail.getRelative(side).getType() != shiftBlock) continue;
                BlockFace away = side.getOppositeFace();
                for (int n = 1; n <= shiftRange; n++) {
                    Block target = rail.getRelative(away, n);
                    if (!RailsPlugin.loaded(target)) break;
                    if (!RailsPlugin.isRail(target.getType())) {
                        if (target.getType().isSolid()) break;
                        continue;
                    }
                    Location to = cart.getLocation().add(away.getModX() * n, 0, away.getModZ() * n);
                    compat.passengers().teleportRetainingPassengers(cart, to);
                    cart.setVelocity(velocity);
                    rail.getWorld().playSound(to, Sound.BLOCK_SLIME_BLOCK_PLACE, 0.8f, 1.4f);
                    rail.getWorld().spawnParticle(Particle.ITEM_SLIME, rail.getLocation().add(0.5, 0.3, 0.5), 8, 0.3, 0.1, 0.3);
                    return;
                }
            }
        }
    }

    /** Signal rail: the cart waits while the signal says stop. False when this rail is no signal. */
    private boolean signal(Minecart cart, Block rail, BlockFace heading, double speed) {
        if (signalBlock == null || rail.getRelative(BlockFace.DOWN).getType() != signalBlock) return false;
        BlockFace onwards = rail.getBlockData() instanceof Rail data ? Track.onwards(data.getShape(), heading) : heading;
        String stop = onwards == null ? null : signalStop(cart, rail, onwards);
        if (stop != null) {
            hold(cart, rail);
            signals.put(cart.getUniqueId(), new Signal(rail, onwards, Math.max(speed, 0.3), 0));
            tellRiders(cart, stop);
            rail.getWorld().playSound(rail.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
        }
        return true;
    }

    private static BlockFace left(BlockFace heading) {
        return switch (heading) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    /** Elevator: the track at the far end of the scaffolding column (above or below), carts ride there. */
    private boolean startLift(Minecart cart, Block rail, Block column, BlockFace heading, double speed) {
        Block best = null;
        BlockFace bestFace = null;
        for (int direction : new int[] {1, -1}) {
            for (int dy = direction; Math.abs(dy) <= liftHeight; dy += direction) {
                Block level = column.getRelative(0, dy, 0);
                if (level.getType() != liftBlock) break;
                for (BlockFace face : SIDES) {
                    if (face == heading.getOppositeFace()) continue;
                    Block exit = level.getRelative(face);
                    if (!RailsPlugin.loaded(exit)) continue;
                    if (!RailsPlugin.isRail(exit.getType())) continue;
                    int distance = Math.abs(exit.getY() - rail.getY());
                    int bestDistance = best == null ? -1 : Math.abs(best.getY() - rail.getY());
                    if (distance > bestDistance || distance == bestDistance && face == heading) { best = exit; bestFace = face; }
                }
            }
        }
        if (best == null) return false;
        hold(cart, rail);
        compat.passengers().teleportRetainingPassengers(cart, column.getLocation().add(0.5, cart.getLocation().getY() - rail.getY(), 0.5).setDirection(cart.getLocation().getDirection()));
        cart.setGravity(false);
        double targetY = best.getY() + 0.0625;
        lifts.put(cart.getUniqueId(), new Lift(column.getX(), column.getZ(), targetY, targetY > cart.getLocation().getY() ? liftSpeed : -liftSpeed,
                best.getLocation().add(0.5, 0.0625, 0.5), bestFace, Math.max(speed, 0.3)));
        rail.getWorld().playSound(rail.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.8f);
        return true;
    }

    /** Why a cart must wait at this signal: "signal-red" while the signal block is powered, "signal-occupied" while
     *  another cart is on the next rails ahead; null when the way is clear. */
    private String signalStop(Minecart cart, Block rail, BlockFace onwards) {
        Block signal = rail.getRelative(BlockFace.DOWN);
        if (signal.isBlockPowered() || signal.isBlockIndirectlyPowered()) return "signal-red";
        Block at = rail;
        BlockFace heading = onwards;
        for (int i = 0; i < clearAhead && heading != null; i++) {
            Block side = at.getRelative(heading);
            if (!side.getWorld().isChunkLoaded(side.getX() >> 4, side.getZ() >> 4)) break;
            Block next = RailsPlugin.railAt(side);
            if (next == null) next = RailsPlugin.railAt(side.getRelative(BlockFace.UP));
            if (next == null) next = RailsPlugin.railAt(side.getRelative(BlockFace.DOWN));
            if (next == null) break;
            for (Minecart other : compat.platform().getNearbyEntitiesByType(next.getWorld(), Minecart.class, next.getLocation().add(0.5, 0.5, 0.5), 0.4, 0.8, 0.4))
                if (!other.getUniqueId().equals(cart.getUniqueId()) && (trains == null || !trains.sameTrain(cart.getUniqueId(), other.getUniqueId())))
                    return "signal-occupied";
            heading = next.getBlockData() instanceof Rail data ? Track.onwards(data.getShape(), heading) : heading;
            at = next;
        }
        return null;
    }

    private void tellRiders(Minecart cart, String key) {
        for (Entity rider : cart.getPassengers()) if (rider instanceof Player player) notice.accept(player, key);
    }

    private void hold(Minecart cart, Block rail) {
        cart.setVelocity(new Vector());
        Location center = rail.getLocation().add(0.5, cart.getLocation().getY() - rail.getY(), 0.5);
        center.setYaw(cart.getLocation().getYaw());
        compat.passengers().teleportRetainingPassengers(cart, center);
    }

    private static void restore(Minecart cart) {
        cart.setDerailedVelocityMod(GROUND);
        cart.setFlyingVelocityMod(AIR);
    }

    private boolean fluid(Block block) {
        if (floatOn.isEmpty()) return false;
        Material type = block.getType();
        if (floatOn.contains(type)) return true;
        return floatOn.contains(Material.WATER) && (type == Material.BUBBLE_COLUMN || block.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged());
    }

    private static void launch(Minecart cart, BlockFace direction, double speed) {
        cart.setGravity(true);
        cart.setMaxSpeed(Math.max(cart.getMaxSpeed(), speed));
        cart.setVelocity(direction.getDirection().multiply(speed));
    }

    // ---- every tick -----------------------------------------------------------------------------------------------

    void tick(Collection<? extends Player> players) {
        if (!spins.isEmpty()) tickSpins();
        if (!lifts.isEmpty()) tickLifts();
        if (!floating.isEmpty()) tickFloating();
        if (!signals.isEmpty()) tickSignals();
        if (steer && !changed.isEmpty()) tickSteering(players);
    }

    private void tickSpins() {
        for (Iterator<Map.Entry<UUID, Spin>> it = spins.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(Bukkit.getEntity(entry.getKey()) instanceof Minecart cart) || !cart.isValid()) { it.remove(); continue; }
            Spin spin = entry.getValue();
            if (spin.ticksLeft() <= 0) {
                it.remove();
                launch(cart, spin.onwards(), spin.speed());
                continue;
            }
            cart.setVelocity(new Vector());
            cart.setRotation(cart.getLocation().getYaw() + 360f / spinTicks, cart.getLocation().getPitch());
            Location at = cart.getLocation();
            at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.2, 0), 2, 0.4, 0.05, 0.4, 0.01);
            entry.setValue(new Spin(spin.ticksLeft() - 1, spin.onwards(), spin.speed()));
        }
    }

    private void tickLifts() {
        for (Iterator<Map.Entry<UUID, Lift>> it = lifts.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(Bukkit.getEntity(entry.getKey()) instanceof Minecart cart) || !cart.isValid()) { it.remove(); continue; }
            Lift lift = entry.getValue();
            Location at = cart.getLocation();
            double y = at.getY() + lift.step();
            if (lift.step() > 0 ? y >= lift.targetY() : y <= lift.targetY()) {
                it.remove();
                Location exit = lift.exit().clone();
                exit.setYaw(at.getYaw());
                compat.passengers().teleportRetainingPassengers(cart, exit);
                launch(cart, lift.onwards(), lift.speed());
                exit.getWorld().playSound(exit, Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.2f);
                continue;
            }
            cart.setVelocity(new Vector());
            compat.passengers().teleportRetainingPassengers(cart, new Location(at.getWorld(), lift.x() + 0.5, y, lift.z() + 0.5, at.getYaw(), at.getPitch()));
        }
    }

    /** Waiting carts check their signal every 5 ticks and leave once it is clear (or the signal block is gone). */
    private void tickSignals() {
        for (Iterator<Map.Entry<UUID, Signal>> it = signals.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(Bukkit.getEntity(entry.getKey()) instanceof Minecart cart) || !cart.isValid()) { it.remove(); continue; }
            Signal signal = entry.getValue();
            int waited = signal.waited() + 1;
            if (waited % 5 == 0) {
                Block rail = signal.rail();
                boolean gone = signalBlock == null || !RailsPlugin.isRail(rail.getType()) || rail.getRelative(BlockFace.DOWN).getType() != signalBlock;
                String stop = gone ? null : signalStop(cart, rail, signal.onwards());
                if (stop == null) {
                    it.remove();
                    launch(cart, signal.onwards(), signal.speed());
                    rail.getWorld().playSound(rail.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.4f);
                    continue;
                }
                if (waited % 20 == 0) tellRiders(cart, stop);
            }
            cart.setVelocity(new Vector());
            entry.setValue(new Signal(signal.rail(), signal.onwards(), signal.speed(), waited));
        }
    }

    private void tickFloating() {
        for (Iterator<UUID> it = floating.iterator(); it.hasNext(); ) {
            if (!(Bukkit.getEntity(it.next()) instanceof Minecart cart) || !cart.isValid()) { it.remove(); continue; }
            Block at = cart.getLocation().getBlock();
            Block fluid = fluid(at) ? at : fluid(at.getRelative(BlockFace.DOWN)) ? at.getRelative(BlockFace.DOWN) : null;
            if (fluid == null || RailsPlugin.railAt(at) != null) {
                it.remove();
                cart.setGravity(true);
                cart.setFireTicks(0);
                fire.suppress(cart, false);
                for (Entity rider : cart.getPassengers()) rider.setFireTicks(0);
                continue;
            }
            for (int i = 0; i < 8 && fluid(fluid.getRelative(BlockFace.UP)); i++) fluid = fluid.getRelative(BlockFace.UP);
            double surface = fluid.getY() + 0.875;
            Vector velocity = cart.getVelocity();
            velocity.setX(velocity.getX() * 0.96).setZ(velocity.getZ() * 0.96);
            velocity.setY(Math.clamp((surface - 0.3 - cart.getLocation().getY()) * 0.25, -0.15, 0.1));
            for (Entity rider : cart.getPassengers()) {
                rider.setFireTicks(0);
                if (rider instanceof Player player) velocity = paddle(player, velocity);
            }
            cart.setFireTicks(0);
            cart.setVelocity(velocity);
        }
    }

    /** Boat controls: A/D turn, W speeds up to the float speed, S brakes. */
    private Vector paddle(Player player, Vector velocity) {
        CartCompat.Input input = controls.read(player);
        double speed = Math.hypot(velocity.getX(), velocity.getZ());
        if (input.isLeft() != input.isRight()) {
            if (speed > 0.01) velocity = turn(velocity, input.isLeft() ? 5 : -5);
        }
        if (input.isBackward()) velocity.setX(velocity.getX() * 0.85).setZ(velocity.getZ() * 0.85);
        else if (input.isForward() && speed < floatSpeed) {
            Vector push = speed > 0.02 ? velocity.clone().setY(0).normalize() : player.getLocation().getDirection().setY(0).normalize();
            velocity.add(push.multiply(0.03));
        }
        return velocity;
    }

    /** Riders of sliding carts steer with A/D, brake with S and push a little with W. */
    private void tickSteering(Collection<? extends Player> players) {
        for (Player player : players) {
            if (!(player.getVehicle() instanceof Minecart cart) || !changed.contains(cart.getUniqueId()) || !cart.isOnGround()) continue;
            if (RailsPlugin.railAt(cart.getLocation().getBlock()) != null || floating.contains(cart.getUniqueId())) continue;
            CartCompat.Input input = controls.read(player);
            Vector velocity = cart.getVelocity();
            double speed = Math.hypot(velocity.getX(), velocity.getZ());
            if (input.isLeft() != input.isRight() && speed > 0.01) velocity = turn(velocity, input.isLeft() ? steerDegrees : -steerDegrees);
            if (input.isBackward()) velocity.multiply(0.9);
            else if (input.isForward() && speed < pushSpeed) {
                Vector push = speed > 0.01 ? velocity.clone().setY(0).normalize() : player.getLocation().getDirection().setY(0).normalize();
                velocity.add(push.multiply(0.015));
            }
            cart.setVelocity(velocity);
        }
    }

    /** Rotates the horizontal part; positive degrees turn left as seen by someone moving along the vector. */
    static Vector turn(Vector velocity, double degrees) {
        double radians = Math.toRadians(degrees), cos = Math.cos(radians), sin = Math.sin(radians);
        double x = velocity.getX(), z = velocity.getZ();
        return new Vector(x * cos + z * sin, velocity.getY(), -x * sin + z * cos);
    }

    // ---- no burning on lava ---------------------------------------------------------------------------------------

    private boolean onFluid(Entity cart) {
        return floating.contains(cart.getUniqueId()) || fluid(cart.getLocation().getBlock());
    }

    /**
     * Damage goes through Core's stages, so Core decides when this runs among all Vystorm plugins. Riders of a
     * floating cart take no heat damage, a rule of this plugin: ADJUST, the same Bukkit priority (NORMAL) as before,
     * after every protection. It does not see cancelled damage.
     */
    void handleSharedEvents(org.bukkit.plugin.Plugin owner, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages) {
        stages.handle(owner, EntityDamageEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.ADJUST, this::onRiderHeat);
    }

    /** Stage ADJUST, registered in {@link #handleSharedEvents}. */
    public void onRiderHeat(EntityDamageEvent event) {
        if (!HEAT.contains(event.getCause()) || !(event.getEntity().getVehicle() instanceof Minecart cart) || !onFluid(cart)) return;
        event.setCancelled(true);
        event.getEntity().setFireTicks(0);
    }

    /** Riders never catch fire on a floating cart. */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Minecart) && entity.getVehicle() instanceof Minecart cart && onFluid(cart)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartHeat(VehicleDamageEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getAttacker() == null && onFluid(cart)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCartBurn(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getAttacker() == null && onFluid(cart)) event.setCancelled(true);
    }

    @EventHandler
    public void onUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Minecart cart) release(cart);
    }

    /** A cart left the world (destroyed or unloaded); see {@link CartRemovals}. */
    void removed(Minecart cart) { release(cart); }

    private void release(Minecart cart) {
        UUID id = cart.getUniqueId();
        if (changed.remove(id)) restore(cart);
        boolean wasFloating = floating.remove(id), wasLifting = lifts.remove(id) != null;
        if (wasFloating || wasLifting) cart.setGravity(true);
        if (wasFloating) fire.suppress(cart, false);
        spins.remove(id);
        signals.remove(id);
    }

    void clear() {
        Set<UUID> modified = new HashSet<>(changed);
        modified.addAll(floating);
        modified.addAll(lifts.keySet());
        for (UUID id : modified) {
            if (!(Bukkit.getEntity(id) instanceof Minecart cart)) continue;
            release(cart);
        }
        changed.clear();
        floating.clear();
        spins.clear();
        lifts.clear();
        signals.clear();
    }
}
