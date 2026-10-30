package fr.deltabuild.deltaagent.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Classes of the runtime package are called by patched game classes and use game types, so the
 * LaunchClassLoader must define them itself. The agent jar is not on its class path, so they are
 * copied to a temporary folder that is added to it: every transformer then gets their bytes, as for
 * any mod class. Some coremods fail on a class without bytes.
 */
final class RuntimeClasses {

    static final String PACKAGE = AgentPackages.ROOT + "runtime.";
    private static final String FOLDER = PACKAGE.replace('.', '/');

    /** Copies the runtime classes to a temporary folder and adds it to the LaunchClassLoader. */
    static void addTo(ClassLoader launchClassLoader) throws Exception {
        Path root = Files.createTempDirectory("deltaagent-runtime-");
        root.toFile().deleteOnExit();
        int copied = copy(source(), root);
        if (copied == 0) {
            throw new IllegalStateException("no class found in " + FOLDER);
        }
        launchClassLoader.getClass().getMethod("addURL", URL.class).invoke(launchClassLoader, root.toUri().toURL());
        Log.debug("core", copied + " runtime classes added to the LaunchClassLoader from " + root + ".");
    }

    /** The bytes of a runtime class, or null for any other name. Used if a class is still not found. */
    static byte[] find(String className) {
        if (!className.startsWith(PACKAGE)) {
            return null;
        }
        String resource = className.replace('.', '/') + ".class";
        try (InputStream in = RuntimeClasses.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Log.debug("core", "Handing " + className + " to the LaunchClassLoader.");
            return in.readAllBytes();
        } catch (Exception e) {
            Log.warn("core", "Could not read " + resource + ": " + e);
            return null;
        }
    }

    /** The agent jar, or the classes folder when running from the build. */
    private static File source() throws Exception {
        URL location = RuntimeClasses.class.getProtectionDomain().getCodeSource().getLocation();
        return Paths.get(location.toURI()).toFile();
    }

    static int copy(File source, Path root) throws IOException {
        List<String> names = new ArrayList<String>();
        if (source.isDirectory()) {
            // Build output: the runtime classes are in their own folder, next to the others.
            for (File dir : candidates(source)) {
                Path folder = dir.toPath().resolve(FOLDER);
                if (Files.isDirectory(folder)) {
                    try (Stream<Path> files = Files.list(folder)) {
                        for (Path file : (Iterable<Path>) files::iterator) {
                            if (file.toString().endsWith(".class")) {
                                write(root, FOLDER + file.getFileName(), Files.readAllBytes(file));
                                names.add(file.getFileName().toString());
                            }
                        }
                    }
                }
            }
            return names.size();
        }
        try (JarFile jar = new JarFile(source)) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                if (entry.getName().startsWith(FOLDER) && entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        write(root, entry.getName(), in.readAllBytes());
                    }
                    names.add(entry.getName());
                }
            }
        }
        return names.size();
    }

    /** From build/classes/java/main, the sibling source sets: build/classes/java/*. */
    private static List<File> candidates(File classes) {
        List<File> dirs = new ArrayList<File>();
        dirs.add(classes);
        File[] siblings = classes.getParentFile() == null ? null : classes.getParentFile().listFiles();
        if (siblings != null) {
            for (File sibling : siblings) {
                if (sibling.isDirectory() && !sibling.equals(classes)) {
                    dirs.add(sibling);
                }
            }
        }
        return dirs;
    }

    /** deleteOnExit runs in reverse order: folders are registered from the top, before their files. */
    private static void write(Path root, String name, byte[] bytes) throws IOException {
        Path target = root.resolve(name);
        Path dir = root;
        for (Path part : root.relativize(target.getParent())) {
            dir = dir.resolve(part);
            if (!Files.isDirectory(dir)) {
                Files.createDirectory(dir);
                dir.toFile().deleteOnExit();
            }
        }
        Files.write(target, bytes);
        target.toFile().deleteOnExit();
    }

    private RuntimeClasses() {
    }
}
