package fr.deltabuild.deltaagent;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Map;
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
        return launchLike(Collections.<String, byte[]>emptyMap());
    }

    /** Same, with some game classes replaced: binary name to class bytes. */
    public static ClassLoader launchLike(final Map<String, byte[]> replaced) throws Exception {
        URL helper = McpcClasses.class.getClassLoader().getResource("fr/deltabuild/deltaagent/runtime/ItemIds.class");
        String root = helper.toString().substring(0, helper.toString().length() - "fr/deltabuild/deltaagent/runtime/ItemIds.class".length());
        // The server jar beside it brings the libraries (Trove, Guava) the game classes use.
        File server = new File(jar().getParentFile(), "minecraft_server.jar");
        URL[] urls = server.isFile() ? new URL[] {jar().toURI().toURL(), new URL(root), server.toURI().toURL()}
                : new URL[] {jar().toURI().toURL(), new URL(root)};
        return new URLClassLoader(urls, McpcClasses.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("net.minecraft.") && !name.startsWith("fr.deltabuild.deltaagent.runtime.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) {
                        return loaded;
                    }
                    byte[] bytes = replaced.get(name);
                    return bytes != null ? defineClass(name, bytes, 0, bytes.length) : findClass(name);
                }
            }
        };
    }

    /** A replacement class shipped in the agent jar, e.g. "ids/ExtendedBlockStorage". */
    public static byte[] replacement(String path) throws Exception {
        try (InputStream in = McpcClasses.class.getClassLoader().getResourceAsStream("fr/deltabuild/deltaagent/" + path + ".class.bin")) {
            if (in == null) {
                throw new IllegalArgumentException("no replacement " + path);
            }
            return in.readAllBytes();
        }
    }

    private McpcClasses() {
    }
}
