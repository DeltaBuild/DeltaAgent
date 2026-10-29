package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OptionsTest {

    @AfterEach
    void clear() {
        System.clearProperty("deltaagent.test.new");
        System.clearProperty("deltaagent.test.old");
    }

    @Test
    void newNameWinsOverOldName() {
        System.setProperty("deltaagent.test.new", "new");
        System.setProperty("deltaagent.test.old", "old");
        assertEquals("new", Options.get("deltaagent.test.new", "deltaagent.test.old"));
    }

    @Test
    void oldNameStillWorks() {
        System.setProperty("deltaagent.test.old", "old");
        assertEquals("old", Options.get("deltaagent.test.new", "deltaagent.test.old"));
    }

    @Test
    void unsetGivesNull() {
        assertNull(Options.get("deltaagent.test.new", "deltaagent.test.old"));
    }

    @Test
    void flagAcceptsOnlyTrueOrFalse() {
        assertTrue(Options.flag("deltaagent.test.new", true));
        System.setProperty("deltaagent.test.new", "FALSE");
        assertFalse(Options.flag("deltaagent.test.new", true));
        System.setProperty("deltaagent.test.new", "nope");
        assertTrue(Options.flag("deltaagent.test.new", true));
    }
}
