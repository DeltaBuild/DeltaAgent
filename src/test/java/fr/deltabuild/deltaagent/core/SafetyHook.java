package fr.deltabuild.deltaagent.core;

/**
 * Lets tests of other packages catch {@link Safety#stop} instead of halting the JVM.
 */
public final class SafetyHook {

    /** Thrown instead of halting. */
    public static final class Stopped extends RuntimeException {
        Stopped() {
            super("stopped");
        }
    }

    private static Runnable real;

    public static void install() {
        real = Safety.halt;
        Safety.halt = () -> {
            throw new Stopped();
        };
    }

    public static void uninstall() {
        Safety.halt = real;
    }

    private SafetyHook() {
    }
}
