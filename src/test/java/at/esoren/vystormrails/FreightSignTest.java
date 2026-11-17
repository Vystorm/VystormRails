package at.esoren.vystormrails;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreightSignTest {
    private static RailSign parse(String... lines) { return RailSign.parse("[rails]", lines); }

    @Test
    void givenFreightSigns_whenParsed_thenModeAndSecondsAreRead() {
        RailSign sign = parse("[rails]", "freight", "unload", "5s");
        assertEquals(RailSign.Kind.FREIGHT, sign.kind());
        assertEquals("unload", sign.text());
        assertEquals(5, sign.seconds());
        assertEquals("both", parse("[rails]", "cargo", "", "").text());
        assertEquals(3, parse("[rails]", "freight", "load", "").seconds());
        assertEquals(60, parse("[rails]", "freight", "load", "999").seconds());
        assertNull(parse("[rails]", "freight", "sell", ""));
        assertNull(parse("[rails]", "freight", "load", "soon"));
    }
}
