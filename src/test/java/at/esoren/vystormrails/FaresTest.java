package at.esoren.vystormrails;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FaresTest {
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void givenFares_whenSetAndReloaded_thenNamesIgnoreCaseAndOffClears(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fares.yml");
        Fares fares = new Fares(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        fares.set("Central St.", 5);
        fares.set("Harbour", 2.5);
        Fares back = new Fares(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(5, back.fare("central st."));
        assertEquals(2.5, back.fare(" HARBOUR "));
        assertEquals(0, back.fare("Nowhere"));
        back.set("harbour", 0);
        assertEquals(0, new Fares(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).fare("Harbour"));
    }

    @Test
    void givenTicket_whenBought_thenValidForADayAndExtends(@TempDir Path dir) throws Exception {
        Fares fares = new Fares(dir.resolve("f.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(0, fares.ticket(PLAYER, 1000));
        long until = fares.buy(PLAYER, 1000, 24);
        assertEquals(1000 + 24 * 3_600_000L, until);
        assertEquals(until, fares.ticket(PLAYER, until - 1));
        assertEquals(0, fares.ticket(PLAYER, until));
        assertEquals(until + 24 * 3_600_000L, fares.buy(PLAYER, 2000, 24), "a second ticket adds a day");
        assertEquals(until + 24 * 3_600_000L, new Fares(dir.resolve("f.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).ticket(PLAYER, 5000));
    }
}
