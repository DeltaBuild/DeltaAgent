package fr.deltabuild.deltaagent.ids.legacy;

import fr.deltabuild.deltaagent.core.AgentPackages;
import fr.deltabuild.deltaagent.core.LaunchClassLoaders;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.Options;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * JVM transformer applying {@link IdPatches} to the classes of the LaunchClassLoader, as
 * mcpc-java17 did. Registered after the java17 one, so it sees classes already redirected.
 */
public final class LegacyIdTransformer implements ClassFileTransformer {

    private final Map<ClassLoader, Boolean> enabled =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (className == null || !LaunchClassLoaders.is(loader) || AgentPackages.isAgentClass(className)) {
            return null;
        }
        try {
            return enabled(loader) ? IdPatches.patch(className, bytes) : null;
        } catch (Throwable t) {
            Log.info("ids", "Could not patch " + className + ", leaving it untouched: " + t);
            return null;
        }
    }

    /**
     * The raised limits only apply to stock MCPC+: a server with its own ID extension (NGServer)
     * keeps it. They can also be turned off with -Ddeltaagent.ids.capacity=off.
     */
    private boolean enabled(ClassLoader loader) {
        Boolean value = enabled.get(loader);
        if (value == null) {
            if ("off".equalsIgnoreCase(Options.get(IdCapacity.PROPERTY, IdCapacity.OLD_PROPERTY))) {
                Log.info("ids", "ID limits left unchanged (-D" + IdCapacity.PROPERTY + "=off).");
                value = false;
            } else if (loader.getResource("fr/nationsglory/ngid/NGCapaciteIds.class") != null) {
                Log.info("ids", "The server has its own ID extension, ID limits left unchanged.");
                value = false;
            } else {
                Log.info("ids", "Raising the ID limits to " + IdCapacity.blocks()
                        + " (blocks above " + IdCapacity.MAX_STORABLE_BLOCK + " are placeholders).");
                value = true;
            }
            enabled.put(loader, value);
        }
        return value;
    }
}
