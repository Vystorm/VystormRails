package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rankings rails:distance and rails:trips (1.22.0, Core 0.67.0) and the store riders.yml. */
class RailsRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID KIM = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    @TempDir Path dir;
    private RideCounts rides;
    private PlaceholderTesting placeholders;
    private Plugin plugin;

    private static void ride(RideCounts rides, UUID player, String name, double blocks) {
        rides.start(player);
        for (double left = blocks; left > 0; left -= 0.5) rides.moved(player, name, Math.min(0.5, left));
        rides.end(player);
    }

    @BeforeEach void fixture() throws Exception {
        rides = new RideCounts(dir.resolve("riders.yml"), AtomicFilesInterface.DEFAULT);
        ride(rides, ALEX, "Alex", 1500);     // one long ride
        ride(rides, SAM, "Sam", 100);
        ride(rides, SAM, "Sam", 100);
        ride(rides, SAM, "Sam", 100);        // three rides, 300 blocks
        ride(rides, KIM, "Kim", 10);         // too short to be a ride
        rides.write(rides.takeChanges());
        rides = new RideCounts(dir.resolve("riders.yml"), AtomicFilesInterface.DEFAULT);   // a restart; nobody online
        placeholders = PlaceholderTesting.detached();
        plugin = PlaceholderTesting.plugin("VystormRails");
    }

    private void apply(boolean core, boolean on) {
        RailsPlaceholders.rankings(placeholders.placeholders(), plugin, core, on, rides, null, null);
    }

    private List<String> ids() { return placeholders.rankings().stream().map(PlaceholderRanking::id).sorted().toList(); }

    @Test void givenTheFixture_whenDistanceIsRead_thenBlocksPerPlayerAsDistanceOfflineIncluded() {
        apply(true, true);
        assertEquals(List.of("rails:distance", "rails:trips"), ids());
        List<RankingSource.Row> rows = placeholders.top("rails:distance", 10);
        assertEquals(List.of(ALEX, SAM, KIM), rows.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(1500.0, 300.0, 10.0), rows.stream().map(RankingSource.Row::value).toList());
        assertEquals("DISTANCE_BLOCKS", placeholders.ranking("rails:distance").orElseThrow().info().format().name());
        assertEquals("Alex", placeholders.resolve(null, "%vystorm_rails_top_distance_1_name%"));
        assertEquals("1.50 km", placeholders.resolve(null, "%vystorm_rails_top_distance_1_value%"));
        assertEquals("1,50 km", placeholders.language("de").resolve(null, "{rails:top_distance:1:value}"));
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTheFixture_whenTripsAreRead_thenOnlyRidesOfSixteenBlocksCount() {
        apply(true, true);
        List<RankingSource.Row> rows = placeholders.top("rails:trips", 10);
        assertEquals(List.of(SAM, ALEX), rows.stream().map(RankingSource.Row::player).toList(), "Kim's 10 blocks are no ride");
        assertEquals(List.of(3.0, 1.0), rows.stream().map(RankingSource.Row::value).toList());
        assertEquals("3", placeholders.resolve(null, "%vystorm_rails_top_trips_1_value%"));
        assertEquals("", placeholders.resolve(null, "%vystorm_rails_top_trips_3_name%"));
    }

    @Test void givenTeleportJumpsAndNonsense_whenMoved_thenIgnored() {
        assertFalse(rides.moved(KIM, "Kim", 50));
        assertFalse(rides.moved(KIM, "Kim", Double.NaN));
        assertFalse(rides.moved(KIM, "Kim", -3));
        assertEquals(10.0, rides.distance(KIM), 1e-9);
    }

    @Test void givenOneRide_whenItPassesSixteenBlocks_thenItCountsOnceAndANewRideCountsAgain() {
        rides.start(KIM);
        int counted = 0;
        for (int i = 0; i < 100; i++) if (rides.moved(KIM, "Kim", 1)) counted++;
        assertEquals(1, counted);
        rides.end(KIM);
        rides.start(KIM);
        for (int i = 0; i < 16; i++) rides.moved(KIM, "Kim", 1);
        assertEquals(2, rides.trips(KIM));
    }

    @Test void givenRides_whenCounted_thenTheFileIsNotWrittenPerMoveAndAnOlderSnapshotNeverWins() throws Exception {
        String before = Files.readString(dir.resolve("riders.yml"), StandardCharsets.UTF_8);
        assertNull(rides.takeChanges(), "nothing changed since the restart");
        rides.moved(SAM, "Sam", 1);
        rides.moved(SAM, "Sam", 1);
        assertEquals(before, Files.readString(dir.resolve("riders.yml"), StandardCharsets.UTF_8));
        RideCounts.Snapshot older = rides.takeChanges();
        assertNull(rides.takeChanges(), "one write for many moves");
        rides.moved(SAM, "Sam", 1);
        rides.write(rides.takeChanges());
        rides.write(older);
        assertEquals(303.0, new RideCounts(dir.resolve("riders.yml"), AtomicFilesInterface.DEFAULT).distance(SAM), 1e-9);
    }

    @Test void givenRankingsSwitchedOff_whenApplied_thenNotDeclaredAndAReloadRemovesThem() {
        apply(true, false);
        assertEquals(List.of(), ids());
        apply(true, true);
        assertEquals(2, ids().size());
        apply(true, false);
        assertEquals(List.of(), ids());
    }

    @Test void givenACoreWithoutTheCapability_whenApplied_thenNoDeclarationAndNoError() {
        assertDoesNotThrow(() -> apply(false, true));
        assertEquals(List.of(), ids());
        assertDoesNotThrow(() -> RailsPlaceholders.rankings(null, plugin, true, true, rides, null, null));
    }

    @Test void givenTheNamespaceOwnedByAnotherPlugin_whenApplied_thenRefusedQuietly() {
        placeholders.register(PlaceholderTesting.plugin("Other"), "rails");
        assertDoesNotThrow(() -> apply(true, true));
        assertEquals(List.of(), ids());
    }

    @Test void givenABrokenFile_whenLoaded_thenSetAsideAndNothingIsCountedOrWritten() throws Exception {
        Path file = dir.resolve("broken").resolve("riders.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "players: [unclosed", StandardCharsets.UTF_8);
        RideCounts store = new RideCounts(file, AtomicFilesInterface.DEFAULT);
        assertNotNull(store.problem());
        assertFalse(store.moved(ALEX, "Alex", 1) || store.distance(ALEX) > 0);
        assertNull(store.takeChanges());
        try (var listing = Files.list(file.getParent())) { assertEquals(1, listing.count(), "the broken file is kept"); }
    }

    @Test void givenEveryLanguage_whenRead_thenRankingsAndTheirSwitchHaveTexts() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String key : List.of("distance", "trips"))
                for (String part : List.of("label", "description"))
                    assertFalse(lang.getString("rankings." + key + "." + part, "").isBlank(), code + " rankings." + key + "." + part);
            assertFalse(lang.getString(RailSwitches.labelKey(RailSwitches.RANKINGS), "").isBlank(), code + " switch name");
            assertFalse(lang.getString(RailSwitches.descriptionKey(RailSwitches.RANKINGS), "").isBlank(), code + " switch description");
        }
        assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml")).getBoolean(RailSwitches.RANKINGS, false));
    }
}
