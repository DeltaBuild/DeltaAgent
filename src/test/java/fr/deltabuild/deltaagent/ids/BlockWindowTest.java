package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BlockWindowTest {

    @AfterEach
    void restore() {
        BlockWindow.configure(null);
    }

    @Test
    void defaultsToTheNgClientWindow() {
        BlockWindow.configure(null);
        assertEquals(16337, BlockWindow.min());
        assertEquals(19475, BlockWindow.max());
        assertEquals(19476, BlockWindow.searchTop());
    }

    @Test
    void searchGoesDownTheWindowThenBelow4096() {
        BlockWindow.configure("5000-5002");
        int id = BlockWindow.searchTop() - 1;
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            order.append(id).append(' ');
            id = BlockWindow.next(id);
        }
        assertEquals("5002 5001 5000 4095 4094 ", order.toString());
        assertEquals(254, BlockWindow.next(255)); // terrain blocks, below the window
    }

    @Test
    void refusesWindowsNgClientRefuses() {
        BlockWindow.configure("1000-2000");
        assertEquals(16337, BlockWindow.min());
        BlockWindow.configure("9000-8000");
        assertEquals(19475, BlockWindow.max());
        BlockWindow.configure("5000-16777216");
        assertEquals(19475, BlockWindow.max());
        BlockWindow.configure("5000");
        assertEquals(16337, BlockWindow.min());
        BlockWindow.configure("a-b");
        assertEquals(16337, BlockWindow.min());
    }

    @Test
    void acceptsAWindowStartingAt4096() {
        BlockWindow.configure("4096-4100");
        assertEquals(4095, BlockWindow.next(4096));
    }
}
