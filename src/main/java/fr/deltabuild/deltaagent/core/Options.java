package fr.deltabuild.deltaagent.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * System properties read by the agent. Each option can have older names, kept so that start
 * scripts written for mcpc-java17 or DeltaHeight keep working.
 */
public final class Options {

    private static final Set<String> warned = Collections.synchronizedSet(new HashSet<String>());

    /** The value of {@code name}, else of the first old name that is set, else null. */
    public static String get(String name, String... oldNames) {
        String value = System.getProperty(name);
        if (value != null) {
            return value;
        }
        for (String oldName : oldNames) {
            value = System.getProperty(oldName);
            if (value != null) {
                if (warned.add(oldName)) {
                    // Not through Log: Log reads its own option while it initializes.
                    System.out.println("[DeltaAgent/core] -D" + oldName + " still works, but its new name is -D" + name + ".");
                }
                return value;
            }
        }
        return null;
    }

    /** true or false; anything else gives the default. */
    public static boolean flag(String name, boolean defaultValue, String... oldNames) {
        String value = get(name, oldNames);
        if (value == null) {
            return defaultValue;
        }
        value = value.trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return defaultValue;
    }

    private Options() {
    }
}
