package at.esoren.vystormrails;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TrainLineTest {
    @TempDir Path folder;
    private TrainLine line(UUID owner, UUID world) {
        return new TrainLine("metro", owner, world, 600, TrainLine.parseStops("Central Hall@0 | Mine@120 | Port@400"));
    }
    @Test void orderedStopsHaveRealClockDeparturesAndNeverCatchUpMissedCycles() {
        TrainLine line = line(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(120000, line.departure(1, 120000));
        assertEquals(720000, line.departure(1, 120001));
        assertEquals(400000, line.departure(2, 200000));
        assertEquals(0, line.next(2));
        assertEquals(1, line.next(0));
        assertEquals("Central Hall", line.stops().getFirst().station());
    }
    @Test void malformedOrAmbiguousSchedulesAreRejected() {
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        for (String stops : List.of("Mine@0|mine@1", "Mine@100|Port@50", "Mine@0|Port@600", "Mine@-1|Port@10"))
            assertThrows(IllegalArgumentException.class, () -> new TrainLine("metro", owner, world, 600, TrainLine.parseStops(stops)));
        assertThrows(IllegalArgumentException.class, () -> TrainLine.parseStops("NoOffset"));
        assertThrows(IllegalArgumentException.class, () -> new TrainLine("../escape", owner, world, 600, TrainLine.parseStops("A@0|B@20")));
    }
    @Test void definitionsSurviveRestartAndRemainWorldScopedWithOwnerAuthorization() throws Exception {
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID(), other = UUID.randomUUID();
        TrainLineStore store = new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        TrainLine first = line(owner, world), second = line(owner, other);
        store.put(first); store.put(second);
        TrainLineStore restarted = new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(first, restarted.get(world, "metro")); assertEquals(second, restarted.get(other, "metro"));
        assertTrue(first.editable(owner, false)); assertFalse(first.editable(UUID.randomUUID(), false));
        assertTrue(first.editable(UUID.randomUUID(), true));
        restarted.remove(first);
        assertNull(new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(world, "metro"));
        assertEquals(second, new TrainLineStore(folder.resolve("lines.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(other, "metro"));
    }
    @Test void corruptFileIsPreservedAndFailedSaveDoesNotPublishAnInMemoryLine() throws Exception {
        Path file = folder.resolve("lines.yml"); Files.writeString(file, "schema: 99\n");
        assertThrows(Exception.class, () -> new TrainLineStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT)); assertEquals("schema: 99\n", Files.readString(file));
        Files.delete(file); TrainLineStore store = new TrainLineStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT); Files.createDirectory(file);
        TrainLine line = line(UUID.randomUUID(), UUID.randomUUID());
        assertThrows(java.io.IOException.class, () -> store.put(line)); assertNull(store.get(line.world(), line.id()));
    }
}
