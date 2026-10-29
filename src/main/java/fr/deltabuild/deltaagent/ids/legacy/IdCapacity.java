package fr.deltabuild.deltaagent.ids.legacy;

import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.Options;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block and item ID capacity of a stock MCPC+ server, raised so that mods registering IDs above the
 * vanilla limits (4096 blocks, 32000 items) can load. Chunks still store 12 bit block IDs, so blocks
 * above 4095 are placeholders: they are registered, but never written into the world.
 *
 * <p>Ported from mcpc-java17 so the server behaves as before; the ids module replaces it with real
 * support for large IDs. Called from the patched Minecraft classes (see {@link IdPatches}).
 */
public final class IdCapacity {

    public static final String PROPERTY = "deltaagent.ids.capacity";
    static final String OLD_PROPERTY = "mcpcj17.idCapacity";
    /** Highest block ID a chunk can store. */
    public static final int MAX_STORABLE_BLOCK = 4095;
    private static final int VANILLA_ITEMS = 32000;
    private static final int DEFAULT_CAPACITY = 65536;
    private static final int LOGGED_REFUSALS = 5;

    private static final int CAPACITY = readCapacity(Options.get(PROPERTY, OLD_PROPERTY));
    private static final AtomicInteger refusals = new AtomicInteger();

    static int readCapacity(String value) {
        if (value != null) {
            try {
                int capacity = Integer.parseInt(value.trim());
                if (capacity >= 4096) {
                    return capacity;
                }
            } catch (NumberFormatException ignored) {
            }
            Log.info("ids", "Ignoring invalid -D" + PROPERTY + "=" + value + ", using " + DEFAULT_CAPACITY + ".");
        }
        return DEFAULT_CAPACITY;
    }

    /** Size of the block arrays. */
    public static int blocks() {
        return CAPACITY;
    }

    /** Size of the item arrays, which also hold one ItemBlock per block ID. */
    public static int items() {
        return Math.max(CAPACITY, VANILLA_ITEMS);
    }

    /** Called when a block is created: a clear error rather than an ArrayIndexOutOfBoundsException. */
    public static void checkBlock(int id) {
        if (id < 0 || id >= blocks()) {
            throw new IllegalArgumentException("[DeltaAgent/ids] Block ID " + id + " is outside the supported range 0-"
                    + (blocks() - 1) + ". Raise it with -D" + PROPERTY + "=<size> before -jar.");
        }
        if (id > MAX_STORABLE_BLOCK) {
            Log.info("ids", "Block ID " + id + " is above " + MAX_STORABLE_BLOCK
                    + ": registered as a placeholder, it cannot be placed in the world.");
        }
    }

    /** Called when an item is created, with its final ID (constructor argument + 256). */
    public static void checkItem(int id) {
        if (id < 0 || id >= items()) {
            throw new IllegalArgumentException("[DeltaAgent/ids] Item ID " + id + " is outside the supported range 0-"
                    + (items() - 1) + ". Raise it with -D" + PROPERTY + "=<size> before -jar.");
        }
    }

    /** Whether a block ID can be written into a chunk; logs the first refusals. */
    public static boolean storable(int id) {
        if (id >= 0 && id <= MAX_STORABLE_BLOCK) {
            return true;
        }
        int count = refusals.incrementAndGet();
        if (count <= LOGGED_REFUSALS) {
            Log.info("ids", "Refused to place block " + id + " (above " + MAX_STORABLE_BLOCK
                    + ", placeholder only)" + (count == LOGGED_REFUSALS ? "; further refusals are not logged." : "."));
        }
        return false;
    }

    /** The ID actually written into a chunk section: air for placeholder blocks. */
    public static int storedId(int id) {
        return storable(id) ? id : 0;
    }

    private IdCapacity() {
    }
}
