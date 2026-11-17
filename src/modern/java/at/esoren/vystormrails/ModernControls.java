package at.esoren.vystormrails;

import org.bukkit.entity.Player;

public final class ModernControls implements CartCompat.Controls {
    @Override
    public CartCompat.Input read(Player player) {
        var input = player.getCurrentInput();
        return new CartCompat.Input(input.isLeft(), input.isRight(), input.isBackward(), input.isForward());
    }
}
