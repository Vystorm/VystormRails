package at.esoren.vystormrails;

import net.kyori.adventure.util.TriState;
import org.bukkit.entity.Entity;

public final class ModernFire implements CartCompat.Fire {
    @Override
    public void suppress(Entity entity, boolean suppress) {
        entity.setVisualFire(suppress ? TriState.FALSE : TriState.NOT_SET);
    }
}
