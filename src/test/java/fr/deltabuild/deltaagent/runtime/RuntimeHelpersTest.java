package fr.deltabuild.deltaagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.McpcClasses;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.SafetyHook;
import fr.deltabuild.deltaagent.ids.IdsModule;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** ItemIds and WorldFormats on the real NBT classes of MCPC+, loaded as the LaunchClassLoader would. */
@EnabledIf("fr.deltabuild.deltaagent.McpcClasses#present")
class RuntimeHelpersTest {

    private ClassLoader loader;
    private Class<?> nbt;
    private Class<?> stack;
    private Class<?> itemIds;
    private Class<?> worldFormats;

    @BeforeEach
    void setUp() throws Exception {
        loader = McpcClasses.launchLike();
        nbt = loader.loadClass("net.minecraft.nbt.NBTTagCompound");
        stack = loader.loadClass("net.minecraft.item.ItemStack");
        itemIds = loader.loadClass("fr.deltabuild.deltaagent.runtime.ItemIds");
        worldFormats = loader.loadClass("fr.deltabuild.deltaagent.runtime.WorldFormats");
        SafetyHook.install();
    }

    @AfterEach
    void tearDown() {
        SafetyHook.uninstall();
    }

    @Test
    void largeItemsAreSavedWithIdExt() throws Exception {
        Object tag = tag();
        call(itemIds, "writeId", tag, "id", 40000);
        assertEquals((short) 0, call(nbt, "func_74765_d", tag, "id"));
        assertEquals(40000, call(nbt, "func_74762_e", tag, "idExt"));
        assertEquals(40000, call(itemIds, "readId", tag, "id"));
    }

    @Test
    void vanillaItemsStayPlainShorts() throws Exception {
        Object tag = tag();
        call(itemIds, "writeId", tag, "id", 40000);
        call(itemIds, "writeId", tag, "id", 31999); // same tag reused: the old idExt goes away
        assertEquals((short) 31999, call(nbt, "func_74765_d", tag, "id"));
        assertFalse((Boolean) call(nbt, "func_74764_b", tag, "idExt"));
        assertEquals(31999, call(itemIds, "readId", tag, "id"));
    }

    @Test
    void readsIdsStoredAsInt() throws Exception {
        Object tag = tag();
        call(nbt, "func_74768_a", tag, "id", 70000);
        assertEquals(70000, call(itemIds, "readId", tag, "id"));
        assertEquals(0, call(itemIds, "readId", tag(), "id"));
    }

    @Test
    void networkStacksCarryIdExtInACopyOfTheirTag() throws Exception {
        assertEquals(0, call(itemIds, "netId", 32000));
        assertEquals(31999, call(itemIds, "netId", 31999));
        Object small = newStack(5000);
        Object own = tag();
        assertSame(own, call(itemIds, "netTag", own, small));

        Object large = newStack(70000);
        Object sent = call(itemIds, "netTag", own, large);
        assertNotSame(own, sent);
        assertFalse((Boolean) call(nbt, "func_74764_b", own, "idExt"));
        assertEquals(70000, call(nbt, "func_74762_e", sent, "idExt"));

        // What the client sends back: short 0 and the tag.
        Object received = newStack(0);
        stack.getField("field_77990_d").set(received, sent);
        call(itemIds, "readNetExt", received);
        assertEquals(70000, stack.getField("field_77993_c").get(received));
        assertNull(stack.getField("field_77990_d").get(received)); // nothing else was in the tag
    }

    @Test
    void networkStacksKeepTheirOtherTags() throws Exception {
        Object own = tag();
        call(nbt, "func_74768_a", own, "charge", 7);
        Object sent = call(itemIds, "netTag", null, newStack(40000));
        assertEquals(40000, call(nbt, "func_74762_e", sent, "idExt"));
        Object withTag = call(itemIds, "netTag", own, newStack(40000));
        Object received = newStack(0);
        stack.getField("field_77990_d").set(received, withTag);
        call(itemIds, "readNetExt", received);
        Object kept = stack.getField("field_77990_d").get(received);
        assertEquals(7, call(nbt, "func_74762_e", kept, "charge"));
        assertFalse((Boolean) call(nbt, "func_74764_b", kept, "idExt"));
    }

    @Test
    void levelDatGetsTheItemFormatOnceALargeItemIsSaved() throws Exception {
        IdsModule.start(new PatchRegistry());
        Object data = tag(); // each test has its own loader, so nothing was saved yet
        call(worldFormats, "write", data);
        assertFalse((Boolean) call(nbt, "func_74764_b", data, "NGItemIdFormat"));
        call(itemIds, "writeId", tag(), "id", 50000);
        Object after = tag();
        call(worldFormats, "write", after);
        assertEquals(1, call(nbt, "func_74762_e", after, "NGItemIdFormat"));
    }

    @Test
    void aMarkedWorldStaysMarked() throws Exception {
        IdsModule.start(new PatchRegistry());
        Object loaded = tag();
        call(nbt, "func_74768_a", loaded, "NGItemIdFormat", 1);
        call(worldFormats, "read", loaded);
        Object saved = tag();
        call(worldFormats, "write", saved);
        assertEquals(1, call(nbt, "func_74762_e", saved, "NGItemIdFormat"));
    }

    @Test
    void theMarkCanBeDroppedOnce() throws Exception {
        System.setProperty("ngid.formatmonde.oublier", "true");
        try {
            IdsModule.start(new PatchRegistry());
            Object loaded = tag();
            call(nbt, "func_74768_a", loaded, "NGItemIdFormat", 1);
            call(worldFormats, "read", loaded);
            Object saved = tag();
            call(worldFormats, "write", saved);
            assertFalse((Boolean) call(nbt, "func_74764_b", saved, "NGItemIdFormat"));
        } finally {
            System.clearProperty("ngid.formatmonde.oublier");
            IdsModule.start(new PatchRegistry());
        }
    }

    @Test
    void refusesWorldsItCannotRead() throws Exception {
        IdsModule.start(new PatchRegistry());
        Object blocks = tag();
        call(nbt, "func_74768_a", blocks, "NGBlockIdFormat", 6);
        assertStops(blocks);
        Object items = tag();
        call(nbt, "func_74768_a", items, "NGItemIdFormat", 2);
        assertStops(items);
        Object fine = tag();
        call(nbt, "func_74768_a", fine, "NGItemIdFormat", 1);
        call(nbt, "func_74768_a", fine, "NGBlockIdFormat", 5);
        call(worldFormats, "read", fine);
    }

    private void assertStops(Object data) {
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> method(worldFormats, "read", 1).invoke(null, data));
        assertTrue(e.getCause() instanceof SafetyHook.Stopped, String.valueOf(e.getCause()));
    }

    private Object tag() throws Exception {
        return nbt.getConstructor().newInstance();
    }

    private Object newStack(int id) throws Exception {
        return stack.getConstructor(int.class, int.class, int.class).newInstance(id, 1, 0);
    }

    /** Calls the static or instance method of that name; for an instance method the first argument is the target. */
    private static Object call(Class<?> type, String name, Object... args) throws Exception {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
            int count = isStatic ? args.length : args.length - 1;
            if (method.getParameterCount() != count) {
                continue;
            }
            Object target = isStatic ? null : args[0];
            Object[] rest = isStatic ? args : java.util.Arrays.copyOfRange(args, 1, args.length);
            if (!fits(method.getParameterTypes(), rest)) {
                continue;
            }
            try {
                return method.invoke(target, rest);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
            } else if (types[i] == int.class) {
                if (!(args[i] instanceof Integer)) {
                    return false;
                }
            } else if (types[i] == short.class) {
                if (!(args[i] instanceof Short)) {
                    return false;
                }
            } else if (!types[i].isPrimitive() && !types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Method method(Class<?> type, String name, int count) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count) {
                return method;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
