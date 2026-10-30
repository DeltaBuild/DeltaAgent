package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IdCapacityTest {

    @Test
    void coversLikeNationsGlory() {
        assertEquals(131072, IdCapacity.covering(80021, 131072));
        assertEquals(262144, IdCapacity.covering(131072, 131072));
        assertEquals(1048576, IdCapacity.covering(1000000, 131072));
        assertEquals(64000, IdCapacity.covering(40000, 32000));
        assertEquals(16, IdCapacity.covering(3, 0));
        assertEquals(IdCapacity.LIMIT, IdCapacity.covering(IdCapacity.MAX_ID, 131072));
    }

    @Test
    void refusesIdsBeyond24Bits() {
        assertThrows(IllegalArgumentException.class, () -> IdCapacity.covering(-1, 131072));
        assertThrows(IllegalArgumentException.class, () -> IdCapacity.covering(IdCapacity.LIMIT, 131072));
    }

    @Test
    void readsTheStartingSize() {
        assertEquals(131072, IdCapacity.parse(null));
        assertEquals(65536, IdCapacity.parse(" 65536 "));
        assertEquals(4096, IdCapacity.parse("4096"));
        assertEquals(IdCapacity.LIMIT, IdCapacity.parse("16777216"));
        assertEquals(262144, IdCapacity.parse("200000")); // rounded up
    }

    @Test
    void fallsBackToTheDefaultOnBadValues() {
        assertEquals(131072, IdCapacity.parse("4095"));
        assertEquals(131072, IdCapacity.parse("16777217"));
        assertEquals(131072, IdCapacity.parse("big"));
    }
}
