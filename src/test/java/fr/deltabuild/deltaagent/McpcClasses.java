package fr.deltabuild.deltaagent;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The MCPC+ classes of libs/mcpc-srg.jar, for tests that need the real game code. That jar is not
 * published, so these tests are skipped where it is missing (GitHub Actions).
 */
public final class McpcClasses {

    public static File jar() {
        String path = System.getProperty("deltaagent.test.mcpcSrg");
        return path == null ? null : new File(path);
    }

    public static boolean present() {
        File jar = jar();
        return jar != null && jar.isFile();
    }

    /** Bytes of a class, internal name. */
    public static byte[] read(String internalName) throws Exception {
        try (ZipFile zip = new ZipFile(jar())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            if (entry == null) {
                throw new IllegalArgumentException("not in mcpc-srg.jar: " + internalName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * Loads game classes and the runtime helpers itself, the rest (agent, JDK) from the test class
     * path: like the LaunchClassLoader with its exclusions.
     */
    public static ClassLoader launchLike() throws Exception {
        URL helper = McpcClasses.class.getClassLoader().getResource("fr/deltabuild/deltaagent/runtime/ItemIds.class");
        String root = helper.toString().substring(0, helper.toString().length() - "fr/deltabuild/deltaagent/runtime/ItemIds.class".length());
        URL[] urls = {jar().toURI().toURL(), new URL(root)};
        return new URLClassLoader(urls, McpcClasses.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("net.minecraft.") && !name.startsWith("fr.deltabuild.deltaagent.runtime.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
        };
    }

    private McpcClasses() {
    }
}
