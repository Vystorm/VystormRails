package at.esoren.vystormrails;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Moves items between a freight cart and a container, stack by stack, up to a limit; nothing is ever lost. */
final class Freight {
    private Freight() {}

    /**
     * Moves up to {@code limit} items from {@code from} to {@code to}. Only what fits is taken out; a partly fitting
     * stack leaves the rest in place. Returns the number of items moved.
     */
    static int move(Inventory from, Inventory to, int limit) {
        if (from == null || to == null || limit <= 0) return 0;
        int moved = 0;
        ItemStack[] contents = from.getStorageContents();
        for (int slot = 0; slot < contents.length && moved < limit; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getAmount() <= 0 || stack.getType().isAir()) continue;
            int take = Math.min(stack.getAmount(), limit - moved);
            ItemStack offer = stack.clone();
            offer.setAmount(take);
            int left = 0;
            for (ItemStack rest : to.addItem(offer).values()) left += rest.getAmount();
            int done = take - left;
            if (done <= 0) continue;
            moved += done;
            int remaining = stack.getAmount() - done;
            if (remaining <= 0) from.setItem(slot, null);
            else { ItemStack keep = stack.clone(); keep.setAmount(remaining); from.setItem(slot, keep); }
        }
        return moved;
    }
}
