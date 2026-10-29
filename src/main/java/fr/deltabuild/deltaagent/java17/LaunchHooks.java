package fr.deltabuild.deltaagent.java17;

import fr.deltabuild.deltaagent.core.AgentPackages;
import fr.deltabuild.deltaagent.core.Log;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Called by the patched {@code net.minecraft.launchwrapper.Launch} constructor (Launch.bin), and
 * when the LaunchClassLoader shows up.
 */
public final class LaunchHooks {

    /**
     * launchwrapper builds its class loader from {@code ((URLClassLoader) getClassLoader()).getURLs()};
     * the application class loader is no longer a URLClassLoader since Java 9.
     */
    public static URL[] classPath() {
        List<URL> urls = new ArrayList<URL>();
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            try {
                urls.add(new File(entry).getAbsoluteFile().toURI().toURL());
            } catch (Exception e) {
                Log.info("java17", "Ignoring class path entry " + entry + ": " + e);
            }
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * LaunchClassLoader has the bootstrap loader as parent. On Java 8 that covered the whole JDK;
     * since Java 9, modules such as java.sql or java.scripting live in the platform loader, so their
     * packages (javax.sql, javax.script...) must be delegated explicitly.
     *
     * @param shareAsm when ASM 4 is replaced by the ASM bundled in the agent: the loader then
     *                 delegates ASM to the application class loader, which also finds the classes
     *                 that did not exist in ASM 4
     */
    public static void configure(ClassLoader launchClassLoader, boolean shareAsm) {
        try {
            Method exclude = launchClassLoader.getClass().getMethod("addClassLoaderExclusion", String.class);
            for (String prefix : AgentPackages.launchClassLoaderExclusions()) {
                exclude.invoke(launchClassLoader, prefix);
            }
            if (shareAsm) {
                exclude.invoke(launchClassLoader, "org.objectweb.asm.");
            }
            int count = 0;
            for (Module module : ModuleLayer.boot().modules()) {
                if (module.getClassLoader() == null) {
                    continue;
                }
                for (String pkg : module.getPackages()) {
                    if (!pkg.startsWith("java.")) {
                        exclude.invoke(launchClassLoader, pkg + ".");
                        count++;
                    }
                }
            }
            Log.info("java17", "LaunchClassLoader delegates " + count + " platform packages to the JDK.");
        } catch (Throwable t) {
            Log.info("java17", "Could not configure LaunchClassLoader, continuing: " + t);
        }
    }

    private LaunchHooks() {
    }
}
