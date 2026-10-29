package fr.deltabuild.deltaagent.java17;

import fr.deltabuild.deltaagent.core.Log;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.SoftReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gives the server the reflective access it had on Java 8.
 */
final class ModuleAccess {

    /**
     * Equivalent of {@code --add-opens <every JDK package>=ALL-UNNAMED}: Forge, Bukkit and mods
     * call setAccessible on JDK internals, which Java 17 refuses by default.
     */
    static void openJdkModules(Instrumentation inst) {
        try {
            Module self = ModuleAccess.class.getModule();
            inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.lang", Set.of(self)), Set.of(), Map.of());

            Method openToAllUnnamed = Module.class.getDeclaredMethod("implAddOpensToAllUnnamed", String.class);
            openToAllUnnamed.setAccessible(true);
            int count = 0;
            for (Module module : ModuleLayer.boot().modules()) {
                for (String pkg : module.getPackages()) {
                    openToAllUnnamed.invoke(module, pkg);
                    count++;
                }
            }
            Log.info("java17", "Opened " + count + " JDK packages to the server.");
        } catch (Throwable t) {
            Log.info("java17", "Could not open JDK modules, continuing: " + t);
        }
    }

    /**
     * Java 12+ hides some fields from reflection, notably {@code Field.modifiers}, which Forge's
     * EnumHelper uses to write static final fields. Java 8 hid nothing of the kind.
     */
    static void removeReflectionFilters() {
        try {
            Class<?> reflection = Class.forName("jdk.internal.reflect.Reflection");
            VarHandle filterMap = MethodHandles.privateLookupIn(reflection, MethodHandles.lookup())
                    .findStaticVarHandle(reflection, "fieldFilterMap", Map.class);
            @SuppressWarnings("unchecked")
            Map<Class<?>, ?> filtered = (Map<Class<?>, ?>) filterMap.getVolatile();
            filterMap.setVolatile(new HashMap<Class<?>, Set<String>>());

            // Drop the reflection caches that may already hold the filtered view.
            if (filtered != null) {
                VarHandle reflectionData = MethodHandles.privateLookupIn(Class.class, MethodHandles.lookup())
                        .findVarHandle(Class.class, "reflectionData", SoftReference.class);
                for (Class<?> type : filtered.keySet()) {
                    reflectionData.setVolatile(type, (SoftReference<?>) null);
                }
            }
            Log.info("java17", "Reflection filters removed.");
        } catch (Throwable t) {
            Log.info("java17", "Could not remove reflection filters, continuing: " + t);
        }
    }

    private ModuleAccess() {
    }
}
