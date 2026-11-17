package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CouplingsTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3), D = new UUID(0, 4);

    @Test void trainsAreLinesInOrder() {
        Couplings couplings = new Couplings();
        couplings.link(A, B);
        couplings.link(C, B);
        couplings.link(C, D);
        List<UUID> train = couplings.train(B);
        assertEquals(4, train.size());
        assertTrue(train.equals(List.of(A, B, C, D)) || train.equals(List.of(D, C, B, A)), train.toString());
        List<UUID> fromD = couplings.train(D);
        assertTrue(fromD.equals(train) || fromD.equals(train.reversed()));
        assertEquals(1, couplings.trains().size());
        assertTrue(couplings.sameTrain(A, D));
        assertEquals(List.of(new UUID(9, 9)), couplings.train(new UUID(9, 9)));
    }

    @Test void refusesLoopsFullCartsAndLongTrains() {
        Couplings couplings = new Couplings();
        assertEquals(Couplings.Refusal.SAME, couplings.check(A, A, 8));
        assertNull(couplings.check(A, B, 8));
        couplings.link(A, B);
        assertEquals(Couplings.Refusal.ALREADY, couplings.check(B, A, 8));
        couplings.link(B, C);
        assertEquals(Couplings.Refusal.FULL, couplings.check(B, D, 8));
        assertEquals(Couplings.Refusal.LOOP, couplings.check(A, C, 8));
        assertEquals(Couplings.Refusal.TOO_LONG, couplings.check(C, D, 3));
        assertNull(couplings.check(C, D, 4));
    }

    @Test void uncouplingSplitsTheTrain() {
        Couplings couplings = new Couplings();
        couplings.link(A, B);
        couplings.link(B, C);
        assertEquals(2, couplings.unlinkAll(B).size());
        assertFalse(couplings.coupled(A));
        assertFalse(couplings.coupled(C));
        assertEquals(0, couplings.carts());
        assertTrue(couplings.unlinkAll(B).isEmpty());
        couplings.link(A, B);
        couplings.link(B, C);
        assertTrue(couplings.unlink(A, B));
        assertFalse(couplings.sameTrain(A, C));
        assertTrue(couplings.sameTrain(B, C));
    }

    @Test void storedFormRoundTripsAndSkipsJunk() {
        assertEquals("", Couplings.format(List.of()));
        assertEquals(List.of(A, B), Couplings.parse(Couplings.format(List.of(A, B))));
        assertEquals(List.of(A), Couplings.parse("nonsense," + A + "," + A));
        assertTrue(Couplings.parse(null).isEmpty());
        assertEquals(2, Couplings.parse(A + "," + B + "," + C).size());
    }

    @Test void aCartRemovedWhileItsPartnerWasUnloadedIsDroppedWhenThePartnerLoads() {
        Couplings couplings = new Couplings();
        couplings.link(A, B);                       // B's chunk unloads, the link stays in memory and on both carts
        assertEquals(List.of(B), couplings.removed(A)); // A is broken; B cannot be written (not loaded)
        assertFalse(couplings.coupled(B));
        assertTrue(couplings.gone(A));
        assertTrue(couplings.restore(B, List.of(A)), "B's stored link to A is stale and is written again");
        assertFalse(couplings.coupled(B));
        assertFalse(couplings.restore(C, List.of(D)));
        assertTrue(couplings.sameTrain(C, D));
    }

    @Test void restoringIsIdempotentAndRefusesFullPartners() {
        Couplings couplings = new Couplings();
        couplings.link(A, B);
        assertFalse(couplings.restore(B, List.of(A)), "the partner loaded first and linked already");
        assertEquals(List.of(A), couplings.neighbours(B));
        couplings.link(B, C);
        assertTrue(couplings.restore(D, List.of(B)), "B has two neighbours already: D's link is stale");
        assertFalse(couplings.coupled(D));
        assertTrue(couplings.restore(D, List.of(D)));
    }

    @Test void aMissingPartnerIsOnlyDroppedAfterTheGraceWithItsAreaLoaded() {
        Couplings couplings = new Couplings();
        couplings.restore(B, List.of(A));           // after a restart: B loads, A does not show up
        for (int i = 0; i < 100; i++) assertTrue(couplings.missing(A, false, 40).isEmpty(), "area not loaded: wait");
        for (int i = 0; i < 39; i++) assertTrue(couplings.missing(A, true, 40).isEmpty());
        assertTrue(couplings.missing(A, false, 40).isEmpty(), "unloaded again: starts over");
        for (int i = 0; i < 39; i++) assertTrue(couplings.missing(A, true, 40).isEmpty());
        couplings.present(A);                        // found after all
        for (int i = 0; i < 39; i++) assertTrue(couplings.missing(A, true, 40).isEmpty());
        assertEquals(List.of(B), couplings.missing(A, true, 40));
        assertFalse(couplings.coupled(B));
        assertFalse(couplings.gone(A), "not seen is not proof of removal: A may come back and link again");
    }

    @Test void theAreaAroundALastPlaceCoversNeighbourChunks() {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        assertTrue(Couplings.areaLoaded(8, 8, 6, (x, z) -> seen.add(((long) x << 32) | (z & 0xffffffffL))));
        assertEquals(1, seen.size(), "6 blocks around the middle of a chunk stay in that chunk");
        assertFalse(Couplings.areaLoaded(15.5, 0.5, 6, (x, z) -> x == 0 && z == 0), "next to a chunk border the neighbours count");
        assertTrue(Couplings.areaLoaded(-0.5, -0.5, 1, (x, z) -> x >= -1 && x <= 0 && z >= -1 && z <= 0));
        assertFalse(Couplings.areaLoaded(-0.5, -0.5, 1, (x, z) -> x == -1 && z == -1));
    }

    @Test void followersCloseTheGap() {
        assertEquals(0.5, Couplings.follow(1.6, 1.6, 0.5, 1.5), 1e-9);
        assertTrue(Couplings.follow(2.6, 1.6, 0.5, 1.5) > 0.5);
        assertTrue(Couplings.follow(1.2, 1.6, 0.5, 1.5) < 0.5);
        assertEquals(1.5, Couplings.follow(20, 1.6, 1.4, 1.5), 1e-9);
        assertEquals(-0.2, Couplings.follow(0.2, 1.6, 0, 1.5), 1e-9);
        assertEquals(0, Couplings.follow(1.62, 1.6, 0, 1.5), 1e-9);
    }
}
