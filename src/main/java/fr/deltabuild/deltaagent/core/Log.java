package fr.deltabuild.deltaagent.core;

/**
 * Console output of the agent: one prefix per module, so a line tells which part wrote it.
 */
public final class Log {

    private static final boolean DEBUG = Options.flag("deltaagent.debug", false, "deltaheight.debug");

    public static void info(String module, String message) {
        System.out.println(prefix(module) + message);
    }

    public static void warn(String module, String message) {
        System.out.println(prefix(module) + "WARNING: " + message);
    }

    public static void debug(String module, String message) {
        if (DEBUG) {
            System.out.println(prefix(module) + "(debug) " + message);
        }
    }

    public static boolean debugEnabled() {
        return DEBUG;
    }

    private static String prefix(String module) {
        return "[DeltaAgent/" + module + "] ";
    }

    private Log() {
    }
}
