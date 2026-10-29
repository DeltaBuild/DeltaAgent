package fr.deltabuild.deltaagent.core;

/**
 * Stops the server when going on could damage the world. halt() skips the shutdown hooks on
 * purpose: they would save the chunks loaded so far.
 */
public final class Safety {

    public static final int EXIT_CODE = 42;

    /** Replaced by tests, which cannot let the JVM halt. */
    static volatile Runnable halt = () -> Runtime.getRuntime().halt(EXIT_CODE);

    public static void stop(String module, String reason) {
        System.out.println("[DeltaAgent/" + module + "] FATAL: " + reason);
        System.out.println("[DeltaAgent/" + module + "] Server stopped to protect the world.");
        System.out.flush();
        halt.run();
    }

    private Safety() {
    }
}
