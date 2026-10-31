package fr.deltabuild.deltaagent.runtime;

import fr.deltabuild.deltaagent.core.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * WorldEdit schematics with 24-bit block IDs and 16-bit metadata. WorldEdit keeps writing Blocks,
 * AddBlocks (bits 8-11) and Data (bits 0-7); DeltaAgent adds AddBlocks2 (bits 12-15, same nibble
 * order as AddBlocks), BlocksB3 (bits 16-23) and Data2 (metadata bits 8-15), only when a block
 * needs them, so other schematics stay as WorldEdit writes them.
 *
 * <p>Called by the patched MCEditSchematicFormat. WorldEdit's classes live in its plugin class
 * loader, out of reach of this one: they are used by reflection.
 */
public final class PluginSchematics {

    public static final String ADD2 = "AddBlocks2";
    public static final String B3 = "BlocksB3";
    public static final String DATA2 = "Data2";

    /** Extra arrays of the schematic being loaded, by thread. */
    private static final ThreadLocal<Extra> loading = new ThreadLocal<Extra>();

    private static final class Extra {
        final Map<?, ?> schematic;
        final byte[] add2;
        final byte[] b3;
        final byte[] data2;
        Method getBlockForId;

        Extra(Map<?, ?> schematic) {
            this.schematic = schematic;
            add2 = bytes(schematic.get(ADD2));
            b3 = bytes(schematic.get(B3));
            data2 = bytes(schematic.get(DATA2));
        }
    }

    // Load

    /**
     * MCEditSchematicFormat.load, in place of getBlockForId(id, data): the block from the full ID
     * and metadata. WorldEdit read the ID on 12 bits and the metadata as a signed byte.
     */
    public static Object block(Object format, int id12, int data8, int index, Map<?, ?> schematic) throws Exception {
        Extra extra = loading.get();
        if (extra == null || extra.schematic != schematic) {
            extra = new Extra(schematic);
            loading.set(extra);
        }
        int id = id12;
        int data = data8 & 0xFF;
        if (extra.add2 != null && (index >> 1) < extra.add2.length) {
            int packed = extra.add2[index >> 1];
            id |= ((index & 1) == 0 ? packed & 0x0F : packed >> 4 & 0x0F) << 12;
        }
        if (extra.b3 != null && index < extra.b3.length) {
            id |= (extra.b3[index] & 0xFF) << 16;
        }
        if (extra.data2 != null && index < extra.data2.length) {
            data |= (extra.data2[index] & 0xFF) << 8;
        }
        if (extra.getBlockForId == null) {
            extra.getBlockForId = method(format.getClass(), "getBlockForId", int.class, short.class);
        }
        // getBlockForId takes a short: metadata above 32767 is set on the block afterwards.
        Object block = extra.getBlockForId.invoke(format, Integer.valueOf(id), Short.valueOf((short) (data & 0x7FFF)));
        if (data > 0x7FFF) {
            method(block.getClass(), "setData", int.class).invoke(block, Integer.valueOf(data));
        }
        return block;
    }

    // Save

    /** MCEditSchematicFormat.save, before the compound is written: the planes WorldEdit cannot write. */
    public static void save(Map<String, Object> schematic, Object clipboard) throws Exception {
        loading.remove();
        Class<?> type = clipboard.getClass();
        int width = (Integer) method(type, "getWidth").invoke(clipboard);
        int height = (Integer) method(type, "getHeight").invoke(clipboard);
        int length = (Integer) method(type, "getLength").invoke(clipboard);
        ClassLoader loader = type.getClassLoader();
        Constructor<?> vector = loader.loadClass("com.sk89q.worldedit.BlockVector").getConstructor(int.class, int.class, int.class);
        Method getPoint = method(type, "getPoint", loader.loadClass("com.sk89q.worldedit.Vector"));
        Method getId = method(loader.loadClass("com.sk89q.worldedit.foundation.Block"), "getId");
        Method getData = method(loader.loadClass("com.sk89q.worldedit.foundation.Block"), "getData");
        int size = width * height * length;
        byte[] add2 = null;
        byte[] b3 = null;
        byte[] data2 = null;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    Object block = getPoint.invoke(clipboard, vector.newInstance(x, y, z));
                    if (block == null) {
                        continue;
                    }
                    int id = (Integer) getId.invoke(block);
                    int data = (Integer) getData.invoke(block);
                    if (id <= 0xFFF && data <= 0xFF) {
                        continue;
                    }
                    int index = y * width * length + z * width + x;
                    if ((id & 0xF000) != 0) {
                        if (add2 == null) {
                            add2 = new byte[(size >> 1) + 1];
                        }
                        int nibble = id >> 12 & 0x0F;
                        add2[index >> 1] = (byte) ((index & 1) == 0 ? add2[index >> 1] & 0xF0 | nibble : add2[index >> 1] & 0x0F | nibble << 4);
                    }
                    if ((id & 0xFF0000) != 0) {
                        if (b3 == null) {
                            b3 = new byte[size];
                        }
                        b3[index] = (byte) (id >> 16);
                    }
                    if (data > 0xFF) {
                        if (data2 == null) {
                            data2 = new byte[size];
                        }
                        data2[index] = (byte) (data >> 8);
                    }
                }
            }
        }
        Constructor<?> tag = loader.loadClass("com.sk89q.jnbt.ByteArrayTag").getConstructor(String.class, byte[].class);
        put(schematic, tag, ADD2, add2);
        put(schematic, tag, B3, b3);
        put(schematic, tag, DATA2, data2);
        if (add2 != null || b3 != null || data2 != null) {
            Log.debug("ids", "Schematic saved with" + (add2 != null ? " " + ADD2 : "") + (b3 != null ? " " + B3 : "")
                    + (data2 != null ? " " + DATA2 : "") + ".");
        }
    }

    private static void put(Map<String, Object> schematic, Constructor<?> tag, String name, byte[] value) throws Exception {
        if (value != null) {
            schematic.put(name, tag.newInstance(name, value));
        }
    }

    // Reflection

    private static byte[] bytes(Object tag) {
        if (tag == null) {
            return null;
        }
        try {
            Object value = method(tag.getClass(), "getValue").invoke(tag);
            return value instanceof byte[] ? (byte[]) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A method of the class or of one of its superclasses, made accessible. */
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // look in the superclass
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private PluginSchematics() {
    }
}
