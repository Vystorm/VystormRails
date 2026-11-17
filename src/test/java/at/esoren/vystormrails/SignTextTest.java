package at.esoren.vystormrails;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.BlockFace;
import org.bukkit.block.sign.SignSide;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignTextTest {
    @Test void savedBukkitColoredStationRemainsRecognizedAfterCacheRefresh() {
        SignSide side=mock(SignSide.class);
        when(side.line(anyInt())).thenThrow(new NoSuchMethodError("Bukkit"));
        when(side.getLine(0)).thenReturn("\u00a73[rails]");
        when(side.getLine(1)).thenReturn("\u00a7lstation");
        when(side.getLine(2)).thenReturn("\u00a7e8");
        when(side.getLine(3)).thenReturn("\u00a7ae Farm Hub");
        RailSign sign=RailSign.parse("[rails]",SignText.plainLines(side));
        assertNotNull(sign);assertEquals(RailSign.Kind.STATION,sign.kind());
        assertEquals(8,sign.seconds());assertEquals(BlockFace.EAST,sign.direction());assertEquals("Farm Hub",sign.text());
    }
    @Test void paperComponentSignsKeepTheirPlainDestination() {
        SignSide side=mock(SignSide.class);
        when(side.line(0)).thenReturn(Component.text("[rails]",NamedTextColor.DARK_AQUA));
        when(side.line(1)).thenReturn(Component.text("dest"));
        when(side.line(2)).thenReturn(Component.text("Farm Hub",NamedTextColor.GREEN));
        when(side.line(3)).thenReturn(Component.empty());
        RailSign sign=RailSign.parse("[rails]",SignText.plainLines(side));
        assertNotNull(sign);assertEquals("Farm Hub",sign.text());verify(side,never()).getLine(anyInt());
    }
}
