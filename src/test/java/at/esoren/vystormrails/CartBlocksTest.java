package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class CartBlocksTest {
    @Test void turnsLeftAndRight() {
        Vector north = new Vector(0, 0.1, -1);
        Vector left = CartBlocks.turn(north, 90);
        assertEquals(-1, left.getX(), 1e-9);
        assertEquals(0, left.getZ(), 1e-9);
        assertEquals(0.1, left.getY(), 1e-9);
        Vector right = CartBlocks.turn(north, -90);
        assertEquals(1, right.getX(), 1e-9);
        assertEquals(1, CartBlocks.turn(new Vector(0.6, 0, 0.8), 37).length(), 1e-9);
    }

    @Test void namesBlocksReadably() {
        assertEquals("redstone lamp", CartBlocks.name("REDSTONE_LAMP"));
        assertEquals("…copper", CartBlocks.name("*COPPER"));
        assertEquals("gold block", CartBlocks.name("gold_block"));
    }

    @Test void listsSwitchedOnBlocksOnly() {
        List<String[]> lines = new ArrayList<>();
        CartBlocks.add(lines, "blocks-slide", new TreeSet<>(EnumSet.of(Material.BLUE_ICE, Material.ICE)));
        CartBlocks.add(lines, "blocks-float", new TreeSet<>());
        assertEquals(1, lines.size());
        assertEquals("blocks-slide", lines.getFirst()[0]);
        assertEquals("blue ice, ice", lines.getFirst()[1]);
    }
}
