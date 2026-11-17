package at.esoren.vystormrails;

import Vystorm.vystorm_core.compat.ServerCompat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class CartCompat {
    public record Input(boolean isLeft, boolean isRight, boolean isBackward, boolean isForward) {}
    public interface Controls { Input read(Player player); }
    public interface Fire { void suppress(Entity entity, boolean suppress); }
    static final Input NONE = new Input(false, false, false, false);

    private CartCompat() {}

    static boolean hasMethod(Class<?> type, String name, Class<?>... parameters) {
        try { type.getMethod(name, parameters); return true; }
        catch (NoSuchMethodException | LinkageError absent) { return false; }
    }

    /** @param server Core's server part, {@code api.compat().server()} */
    static Controls controls(Vystorm.vystorm_core.compat.ServerCompatInterface server, Class<?> playerType) {
        if (!server.hasClass("org.bukkit.Input") || !hasMethod(playerType, "getCurrentInput")) return player -> NONE;
        return adapter("ModernControls", Controls.class);
    }

    static Fire fire(Class<?> entityType) {
        if (!hasMethod(entityType, "setVisualFire", net.kyori.adventure.util.TriState.class)) {
            // The old boolean API cannot suppress real fire; retain vanilla flames.
            return (entity, suppress) -> { };
        }
        return adapter("ModernFire", Fire.class);
    }

    private static <T> T adapter(String name, Class<T> type) {
        try {
            return type.cast(Class.forName("at.esoren.vystormrails." + name).getConstructor().newInstance());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot load cart compatibility adapter " + name, failure);
        }
    }
}
