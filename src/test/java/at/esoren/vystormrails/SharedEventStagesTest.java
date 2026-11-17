package at.esoren.vystormrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Coupling clicks and rider heat, called by Vystorm Core's real stage dispatcher next to a second plugin. */
class SharedEventStagesTest {
    private final Plugin rails = mock(Plugin.class);
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final Lang lang = mock(Lang.class);
    private final Player player = mock(Player.class);
    private final Minecart cart = mock(Minecart.class);
    private final UUID cartId = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(rails.getName()).thenReturn("VystormRails");
        when(rails.isEnabled()).thenReturn(true);
        when(rails.namespace()).thenReturn("vystormrails");
        when(cart.getUniqueId()).thenReturn(cartId);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack held = mock(ItemStack.class);
        when(held.getType()).thenReturn(Material.STICK);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(player.getInventory()).thenReturn(inventory);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private EventStageTesting trainsAndAnotherPlugin() throws Exception {
        Trains trains = new Trains(rails, lang);
        SharedEventStagesTest.<Set<Material>>field(trains, "couplers").add(Material.STICK);
        EventStageTesting stages = EventStageTesting.detached();
        stages.handle(other, PlayerInteractEntityEvent.class, EventStage.ADJUST, event -> calls.add("adjust"));
        stages.handle(other, PlayerInteractEntityEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        trains.handleSharedEvents(stages.stages());
        return stages;
    }

    @Test
    void givenCoupler_whenACartIsClicked_thenCouplingConsumesTheClickAfterTheRulesAndNothingObservesIt() throws Exception {
        EventStageTesting stages = trainsAndAnotherPlugin();

        PlayerInteractEntityEvent click = stages.fire(new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND));

        assertTrue(click.isCancelled());
        verify(lang).send(player, "denied");
        assertEquals(List.of("adjust"), calls);
    }

    @Test
    void givenCoupler_whenAProtectionDeniesTheClick_thenNothingIsCoupled() throws Exception {
        EventStageTesting stages = trainsAndAnotherPlugin();
        stages.handle(other, PlayerInteractEntityEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        assertTrue(stages.fire(new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND)).isCancelled());

        verifyNoInteractions(lang);
        verify(player, never()).getInventory();
    }

    @Test
    void givenOtherItem_whenACartIsClicked_thenTheClickIsLeftAlone() throws Exception {
        EventStageTesting stages = trainsAndAnotherPlugin();
        when(player.getInventory().getItemInMainHand().getType()).thenReturn(Material.DIRT);

        assertFalse(stages.fire(new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND)).isCancelled());

        assertEquals(List.of("adjust", "observe"), calls);
    }

    /** Lava damage to the rider of the cart; its cancelled state behaves like the real event's. */
    private EntityDamageEvent heat() {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        boolean[] cancelled = {false};
        doAnswer(call -> { cancelled[0] = call.getArgument(0); return null; }).when(event).setCancelled(anyBoolean());
        when(event.isCancelled()).thenAnswer(call -> cancelled[0]);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.LAVA);
        when(player.getVehicle()).thenReturn(cart);
        when(event.getEntity()).thenReturn(player);
        return event;
    }

    private EventStageTesting floatingCartAndAnotherPlugin() throws Exception {
        CartBlocks blocks = new CartBlocks(new Vystorm.vystorm_core.compat.CompatInterface() {});
        SharedEventStagesTest.<Set<UUID>>field(blocks, "floating").add(cartId);
        EventStageTesting stages = EventStageTesting.detached();
        stages.handle(other, EntityDamageEvent.class, EventStage.ACT, event -> calls.add("act"));
        stages.handle(other, EntityDamageEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        blocks.handleSharedEvents(rails, stages.stages());
        return stages;
    }

    @Test
    void givenRiderOfAFloatingCart_whenLavaWouldHurt_thenTheRuleRefusesItBeforeAnyLaterHandler() throws Exception {
        EntityDamageEvent heat = floatingCartAndAnotherPlugin().fire(heat());

        assertTrue(heat.isCancelled());
        verify(player).setFireTicks(0);
        assertEquals(List.of(), calls);
    }

    @Test
    void givenRiderOfAFloatingCart_whenAProtectionAlreadyCancelledTheDamage_thenTheRuleStaysOut() throws Exception {
        EventStageTesting stages = floatingCartAndAnotherPlugin();
        stages.handle(other, EntityDamageEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));

        stages.fire(heat());

        verify(player, never()).setFireTicks(anyInt());
    }
}
