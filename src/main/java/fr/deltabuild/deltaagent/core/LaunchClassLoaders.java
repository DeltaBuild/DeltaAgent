package fr.deltabuild.deltaagent.core;

/**
 * Recognizes launchwrapper's class loader by name: the agent is loaded before it and must not
 * link against it.
 */
public final class LaunchClassLoaders {

    public static final String CLASS_NAME = "net.minecraft.launchwrapper.LaunchClassLoader";

    public static boolean is(ClassLoader loader) {
        return loader != null && CLASS_NAME.equals(loader.getClass().getName());
    }

    private LaunchClassLoaders() {
    }
}
