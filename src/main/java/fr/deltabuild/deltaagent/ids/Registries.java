package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Log;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The block and item registries of the game, and every array indexed by those IDs. When a block or
 * an item does not fit, all of them grow together, so no array is left shorter than its registry.
 *
 * <p>Called by the patched game classes. This class never names a game type: the classes that hold
 * the arrays hand themselves over through {@link #track(Class)} at the end of their static init.
 */
public final class Registries {

    /** Which registry sizes an array. */
    public enum Kind {
        BLOCK, ITEM
    }

    private static final Map<String, Map<String, Kind>> declared = new ConcurrentHashMap<String, Map<String, Kind>>();
    private static final List<Tracked> tracked = new CopyOnWriteArrayList<Tracked>();

    private static volatile int blocks = 4096;
    private static volatile int items = IdCapacity.VANILLA_ITEMS;

    /** Starting sizes; items never fewer than the vanilla 32000. */
    static synchronized void configure(int capacity) {
        blocks = capacity;
        items = Math.max(capacity, IdCapacity.VANILLA_ITEMS);
    }

    /** An array field the growth must follow, once its class calls {@link #track(Class)}. */
    static void declare(String className, String field, Kind kind) {
        Map<String, Kind> fields = declared.get(className);
        if (fields == null) {
            fields = new ConcurrentHashMap<String, Kind>();
            Map<String, Kind> previous = declared.putIfAbsent(className, fields);
            fields = previous != null ? previous : fields;
        }
        fields.put(field, kind);
    }

    /** Size of the arrays indexed by block ID. */
    public static int blocks() {
        return blocks;
    }

    /** Size of the arrays indexed by item ID. */
    public static int items() {
        return items;
    }

    /** Block constructor: makes room for the ID. */
    public static void onNewBlock(int id) {
        if (id > Placeholders.MAX_STORABLE) {
            Placeholders.registered(id);
        }
        if (id < 0 || id >= blocks) {
            grow(Kind.BLOCK, id);
        }
    }

    /** Item constructor, with the final item ID (constructor argument + 256). */
    public static void onNewItem(int id) {
        if (id < 0 || id >= items) {
            grow(Kind.ITEM, id);
        }
    }

    /** Forge Configuration: a block ID read or proposed; makes room for it and returns it. */
    public static int reserveBlock(int id) {
        if (id >= blocks && id <= IdCapacity.MAX_ID) {
            grow(Kind.BLOCK, id);
        }
        return id;
    }

    /** Forge Configuration: an item value (item ID - 256) read or proposed; makes room and returns it. */
    public static int reserveItem(int value) {
        int id = value + 256;
        if (id >= items && id <= IdCapacity.MAX_ID) {
            grow(Kind.ITEM, id);
        }
        return value;
    }

    /** Reads a flag table indexed by block ID; IDs past its end are off. */
    public static boolean flag(boolean[] table, int id) {
        return id >= 0 && id < table.length && table[id];
    }

    /** Writes a flag table indexed by block ID; IDs past its end are dropped. */
    public static void setFlag(boolean[] table, int id, boolean value) {
        if (id >= 0 && id < table.length) {
            table[id] = value;
        }
    }

    /** End of a static init: the arrays of that class now follow the registries. */
    public static void track(Class<?> owner) {
        Map<String, Kind> fields = declared.get(owner.getName());
        if (fields == null) {
            Log.warn("ids", owner.getName() + " asked to be followed, but none of its fields is declared.");
            return;
        }
        synchronized (Registries.class) {
            for (Map.Entry<String, Kind> entry : fields.entrySet()) {
                try {
                    Field field = owner.getDeclaredField(entry.getKey());
                    if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        throw new IllegalStateException("not a static, non final field");
                    }
                    field.setAccessible(true);
                    Tracked array = new Tracked(field, entry.getValue());
                    array.resize(size(array.kind));
                    tracked.add(array);
                } catch (Throwable t) {
                    Log.warn("ids", "Cannot follow " + owner.getName() + "." + entry.getKey() + ": " + t);
                }
            }
        }
        Log.debug("ids", "Following the ID arrays of " + owner.getName() + ".");
    }

    private static synchronized void grow(Kind kind, int id) {
        String what = kind == Kind.BLOCK ? "Block" : "Item";
        if (id < 0 || id > IdCapacity.MAX_ID) {
            throw new IllegalArgumentException("[DeltaAgent/ids] " + what + " ID " + id + " is outside 0-"
                    + IdCapacity.MAX_ID + ".");
        }
        int oldBlocks = blocks;
        int oldItems = items;
        // An ItemBlock takes the ID of its block: items always cover the blocks.
        int newBlocks = kind == Kind.BLOCK ? IdCapacity.covering(id, oldBlocks) : oldBlocks;
        int newItems = Math.max(IdCapacity.covering(id, oldItems), newBlocks);
        if (newBlocks == oldBlocks && newItems == oldItems) {
            return;
        }
        // Arrays first, sizes after: a reader that sees the new size finds the room behind it.
        resizeAll(Kind.BLOCK, newBlocks);
        resizeAll(Kind.ITEM, newItems);
        blocks = newBlocks;
        items = newItems;
        StringBuilder message = new StringBuilder();
        if (newBlocks != oldBlocks) {
            message.append("Block registry grown from ").append(oldBlocks).append(" to ").append(newBlocks);
            if (newItems != oldItems) {
                message.append(", item registry from ").append(oldItems).append(" to ").append(newItems);
            }
        } else {
            message.append("Item registry grown from ").append(oldItems).append(" to ").append(newItems);
        }
        message.append(" for ").append(what.toLowerCase()).append(" ID ").append(id).append(". A mod that kept the old")
                .append(" arrays would miss the new IDs: start with -D").append(IdsModule.CAPACITY).append('=')
                .append(Math.max(newBlocks, newItems)).append(" to avoid growing.");
        Log.warn("ids", message.toString());
    }

    private static void resizeAll(Kind kind, int size) {
        for (Tracked array : tracked) {
            if (array.kind == kind) {
                array.resize(size);
            }
        }
    }

    private static int size(Kind kind) {
        return kind == Kind.BLOCK ? blocks : items;
    }

    /** For tests: forget the tracked arrays and the declarations. */
    static synchronized void reset() {
        tracked.clear();
        declared.clear();
        blocks = 4096;
        items = IdCapacity.VANILLA_ITEMS;
    }

    private static final class Tracked {

        final Field field;
        final Kind kind;

        Tracked(Field field, Kind kind) {
            this.field = field;
            this.kind = kind;
        }

        /** Copies the array into a longer one; a null array (not built yet) is left alone. */
        void resize(int size) {
            try {
                Object array = field.get(null);
                if (array == null) {
                    return;
                }
                int length = Array.getLength(array);
                if (length >= size) {
                    return;
                }
                Object bigger = Array.newInstance(array.getClass().getComponentType(), size);
                System.arraycopy(array, 0, bigger, 0, length);
                field.set(null, bigger);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot resize " + field + ": " + e, e);
            }
        }
    }

    private Registries() {
    }
}
