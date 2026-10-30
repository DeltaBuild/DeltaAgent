package fr.deltabuild.deltaagent.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Where the agent's classes live, and which of them the LaunchClassLoader must leave to the
 * application class loader.
 */
public final class AgentPackages {

    public static final String ROOT = "fr.deltabuild.deltaagent.";
    private static final String ROOT_INTERNAL = "fr/deltabuild/deltaagent/";

    /*
     * Everything but the "runtime" package: code called by patched game classes that has to
     * see those classes must be defined by the LaunchClassLoader itself.
     */
    private static final List<String> EXCLUDED = Collections.unmodifiableList(Arrays.asList(
            ROOT + "DeltaAgent",
            ROOT + "core.",
            ROOT + "java17.",
            ROOT + "ids.",
            ROOT + "height.",
            ROOT + "plugins.",
            ROOT + "shadow."));

    /** Prefixes for LaunchClassLoader.addClassLoaderExclusion. */
    public static List<String> launchClassLoaderExclusions() {
        return EXCLUDED;
    }

    /** Whether an internal name ("a/b/C") belongs to the agent. */
    public static boolean isAgentClass(String internalName) {
        return internalName != null && internalName.startsWith(ROOT_INTERNAL);
    }

    private AgentPackages() {
    }
}
