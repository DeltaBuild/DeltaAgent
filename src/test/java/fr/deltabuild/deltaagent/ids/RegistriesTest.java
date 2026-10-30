package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistriesTest {

    static final class Blocks {
        static Object[] list = new Object[8];
        static boolean[] flags = new boolean[3]; // shorter than the registry: fixed when tracked
        static int[] notBuiltYet;
    }

    static final class Items {
        static Object[] list = new Object[32];
    }

    static final class Frozen {
        static final int[] TABLE = new int[8];
    }

    @BeforeEach
    void setUp() {
        Registries.reset();
        Registries.configure(8);
        Blocks.list = new Object[8];
        Blocks.flags = new boolean[3];
        Blocks.notBuiltYet = null;
        Items.list = new Object[32];
        Registries.declare(Blocks.class.getName(), "list", Registries.Kind.BLOCK);
        Registries.declare(Blocks.class.getName(), "flags", Registries.Kind.BLOCK);
        Registries.declare(Blocks.class.getName(), "notBuiltYet", Registries.Kind.BLOCK);
        Registries.declare(Items.class.getName(), "list", Registries.Kind.ITEM);
        Registries.track(Blocks.class);
        Registries.track(Items.class);
    }

    @AfterEach
    void tearDown() {
        Registries.reset();
    }

    @Test
    void itemsNeverStartBelowVanilla() {
        assertEquals(8, Registries.blocks());
        assertEquals(IdCapacity.VANILLA_ITEMS, Registries.items());
        Registries.configure(131072);
        assertEquals(131072, Registries.items());
    }

    @Test
    void trackingBringsShortArraysUpToSize() {
        assertEquals(8, Blocks.flags.length);
        assertEquals(IdCapacity.VANILLA_ITEMS, Items.list.length);
    }

    @Test
    void aBlockThatDoesNotFitGrowsEveryArray() {
        Object stone = new Object();
        Blocks.list[1] = stone;
        Blocks.flags[1] = true;
        Registries.onNewBlock(40000);
        assertEquals(65536, Registries.blocks());
        assertEquals(65536, Registries.items()); // never fewer items than blocks
        assertEquals(65536, Blocks.list.length);
        assertEquals(65536, Blocks.flags.length);
        assertSame(stone, Blocks.list[1]);
        assertTrue(Blocks.flags[1]);
        assertEquals(65536, Items.list.length);
    }

    @Test
    void anItemThatDoesNotFitGrowsOnlyItems() {
        Registries.onNewItem(70000);
        assertEquals(8, Registries.blocks());
        assertEquals(128000, Registries.items());
        assertEquals(8, Blocks.list.length);
        assertEquals(128000, Items.list.length);
    }

    @Test
    void anArrayBuiltLaterIsLeftToItsClass() {
        Registries.onNewBlock(100);
        assertEquals(null, Blocks.notBuiltYet);
    }

    @Test
    void refusesIdsBeyond24Bits() {
        assertThrows(IllegalArgumentException.class, () -> Registries.onNewBlock(-1));
        assertThrows(IllegalArgumentException.class, () -> Registries.onNewItem(IdCapacity.LIMIT));
    }

    @Test
    void configurationReservesOnlyValidIds() {
        assertEquals(-1, Registries.reserveBlock(-1));
        assertEquals(8, Registries.blocks());
        assertEquals(20, Registries.reserveBlock(20));
        assertEquals(32, Registries.blocks());
        assertEquals(40000, Registries.reserveItem(40000)); // item 40256
        assertEquals(64000, Registries.items());
    }

    @Test
    void flagTablesStayWithinBounds() {
        boolean[] table = new boolean[4];
        Registries.setFlag(table, 2, true);
        Registries.setFlag(table, 70000, true);
        Registries.setFlag(table, -1, true);
        assertTrue(Registries.flag(table, 2));
        assertFalse(Registries.flag(table, 70000));
        assertFalse(Registries.flag(table, -1));
    }

    @Test
    void doesNotFollowFinalFields() {
        Registries.declare(Frozen.class.getName(), "TABLE", Registries.Kind.BLOCK);
        Registries.track(Frozen.class);
        Registries.onNewBlock(100);
        assertEquals(8, Frozen.TABLE.length);
    }
}
