package fr.deltabuild.deltaagent.java17;

import fr.deltabuild.deltaagent.core.Log;
import java.lang.instrument.Instrumentation;

/**
 * Runs MCPC+ 1.6.4 on Java 17. Ported from mcpc-java17 2.1.0 without behaviour changes.
 */
public final class Java17 {

    private static final String SPOOFED_JAVA_VERSION = "1.7.0_80";

    public static void start(Instrumentation inst) {
        spoofJavaVersion();
        ModuleAccess.openJdkModules(inst);
        ModuleAccess.removeReflectionFilters();
        installClassPatches(inst);
    }

    /** MCPC+ refuses to start on anything but Java 7. */
    private static void spoofJavaVersion() {
        String actual = System.getProperty("java.version");
        if (actual != null && !actual.startsWith("1.7")) {
            System.setProperty("java.version", SPOOFED_JAVA_VERSION);
            Log.info("java17", "Java " + actual + " detected, reporting " + SPOOFED_JAVA_VERSION
                    + " to bypass the MCPC+ version check.");
        } else {
            Log.info("java17", "java.version = " + actual + ", nothing to bypass.");
        }
    }

    private static void installClassPatches(Instrumentation inst) {
        try {
            ClassPatcher patcher = new ClassPatcher();
            inst.addTransformer(patcher);
            patcher.preloadAsm();
        } catch (Throwable t) {
            Log.info("java17", "Could not install class patches, continuing: " + t);
        }
    }

    private Java17() {
    }
}
