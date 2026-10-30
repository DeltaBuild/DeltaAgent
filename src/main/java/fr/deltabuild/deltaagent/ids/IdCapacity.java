package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Log;

/**
 * Sizes of the block and item registries. They start at a power of two and double when an ID does
 * not fit, up to 2^24 IDs, as NationsGlory's NGCapaciteIds does.
 */
public final class IdCapacity {

    /** Highest block or item ID: 24 bits. */
    public static final int MAX_ID = 16777215;
    public static final int LIMIT = MAX_ID + 1;
    /** Same start as NGClient. */
    public static final int DEFAULT = 131072;
    static final int MIN = 4096;
    /** Item IDs below this are written as a plain short. */
    public static final int VANILLA_ITEMS = 32000;

    /**
     * The size a registry of {@code current} entries needs to hold {@code id}: {@code current}
     * doubled until it does, at most {@link #LIMIT}. Same rule as NGCapaciteIds.tailleCouvrant.
     */
    public static int covering(int id, int current) {
        if (id < 0 || id > MAX_ID) {
            throw new IllegalArgumentException("ID " + id + " is outside 0-" + MAX_ID + " (24 bits).");
        }
        if (id < current) {
            return current;
        }
        long size = current > 0 ? current : 16L;
        while (size <= id) {
            size <<= 1;
        }
        return (int) Math.min(size, LIMIT);
    }

    /** The starting size from -Ddeltaagent.ids.capacity, rounded up to a power of two. */
    static int parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        try {
            long size = Long.parseLong(value.trim());
            if (size >= MIN && size <= LIMIT) {
                int power = Integer.highestOneBit((int) size);
                if (power != size) {
                    power <<= 1;
                    Log.info("ids", "-D" + IdsModule.CAPACITY + "=" + value.trim() + " rounded up to " + power + ".");
                }
                return power;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        Log.warn("ids", "Ignoring -D" + IdsModule.CAPACITY + "=" + value + ": expected a size from " + MIN + " to "
                + LIMIT + ". Using " + DEFAULT + ".");
        return DEFAULT;
    }

    private IdCapacity() {
    }
}
