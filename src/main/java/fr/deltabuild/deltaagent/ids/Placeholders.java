package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Log;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunks still store 12-bit block IDs. A block above 4095 is registered like any other, so its
 * mod loads and its item works, but it cannot be placed: the chunk refuses it, and a section
 * writes air instead. The idea comes from mcpc-java17.
 */
public final class Placeholders {

    /** Highest block ID a chunk section can store. */
    public static final int MAX_STORABLE = 4095;
    private static final int LOGGED = 5;

    private static final AtomicInteger registered = new AtomicInteger();
    private static final AtomicInteger refused = new AtomicInteger();

    static void registered(int id) {
        int count = registered.incrementAndGet();
        if (count <= LOGGED) {
            Log.info("ids", "Block " + id + " is above " + MAX_STORABLE + ": registered, but it cannot be placed in the"
                    + " world yet (chunks store 12-bit IDs)." + (count == LOGGED ? " Further such blocks are not logged." : ""));
        }
    }

    /** Chunk.setBlockIDWithMetadata: whether the chunk can take this block. */
    public static boolean storable(int id) {
        if (id >= 0 && id <= MAX_STORABLE) {
            return true;
        }
        int count = refused.incrementAndGet();
        if (count <= LOGGED) {
            Log.info("ids", "Refused to place block " + id + " (above " + MAX_STORABLE + ")."
                    + (count == LOGGED ? " Further refusals are not logged." : ""));
        }
        return false;
    }

    /** ExtendedBlockStorage.setExtBlockID: the ID written, air for a block the section cannot store. */
    public static int storedId(int id) {
        return storable(id) ? id : 0;
    }

    /** ItemMap counts the blocks of a map pixel in an array indexed by stored block ID. */
    public static int mapHistogramSize() {
        return MAX_STORABLE + 1;
    }

    private Placeholders() {
    }
}
