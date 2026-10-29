package fr.deltabuild.deltaagent.bench;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Step 0 bench: DeltaAgent must patch classes exactly like mcpc-java17, apart from the names of its
 * own classes. Both agent jars are loaded in their own class loader, so each one runs with the ASM
 * it ships.
 *
 * <p>Arguments: mcpc-java17-agent.jar, DeltaAgent.jar, then the jars whose classes are patched.
 */
public final class CompareMcpcJava17 {

    /** Old names in mcpc-java17's output and their DeltaAgent equivalent. */
    private static final String[][] RENAMES = {
        {"gg/nationsglory/mcpcj17/shim/", "fr/deltabuild/deltaagent/java17/shim/"},
        {"gg.nationsglory.mcpcj17.shim.", "fr.deltabuild.deltaagent.java17.shim."},
        {"gg/nationsglory/mcpcj17/IdCapacity", "fr/deltabuild/deltaagent/ids/legacy/IdCapacity"},
        {"gg/nationsglory/mcpcj17/LaunchHooks", "fr/deltabuild/deltaagent/java17/LaunchHooks"},
    };
    private static final int SHOWN_DIFFERENCES = 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <mcpc-java17-agent.jar> <DeltaAgent.jar> <jar to scan>...");
        }
        Agent oldAgent = new Agent(new File(args[0]), "gg.nationsglory.mcpcj17.ClassPatcher",
                "gg.nationsglory.mcpcj17.IdPatches");
        Agent newAgent = new Agent(new File(args[1]), "fr.deltabuild.deltaagent.java17.ClassPatcher",
                "fr.deltabuild.deltaagent.ids.legacy.IdPatches");

        List<String> differences = new ArrayList<String>();
        List<String> patched = new ArrayList<String>();
        int scanned = 0;
        PrintStream console = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // both agents log every patch
        try {
            for (int i = 2; i < args.length; i++) {
                try (JarFile jar = new JarFile(args[i])) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        // .class.bin: replacement classes shipped by height agents (NGHeight, DeltaHeight).
                        if (!entry.getName().endsWith(".class") && !entry.getName().endsWith(".class.bin")) {
                            continue;
                        }
                        byte[] bytes = read(jar, entry);
                        String name = new ClassReader(bytes).getClassName();
                        byte[] before = oldAgent.patch(name, bytes);
                        byte[] after = newAgent.patch(name, bytes);
                        scanned++;
                        if (before == null && after == null) {
                            continue;
                        }
                        patched.add(name);
                        String oldText = before == null ? "(unchanged)" : rename(text(before));
                        String newText = after == null ? "(unchanged)" : text(after);
                        if (!oldText.equals(newText)) {
                            differences.add(new File(args[i]).getName() + "!" + name);
                        }
                    }
                }
            }
        } finally {
            System.setOut(console);
        }

        List<String> resources = compareResources(new File(args[0]), new File(args[1]));
        System.out.println("Classes scanned: " + scanned + ", patched by either agent: " + patched.size()
                + ", different: " + differences.size());
        for (String name : patched) {
            System.out.println("  patched: " + name);
        }
        System.out.println("Bundled ASM and Launch: " + (resources.isEmpty() ? "identical" : resources.size() + " different"));
        for (int i = 0; i < differences.size() && i < SHOWN_DIFFERENCES; i++) {
            System.out.println("  differs: " + differences.get(i));
        }
        for (String resource : resources) {
            System.out.println("  differs: " + resource);
        }
        if (!differences.isEmpty() || !resources.isEmpty()) {
            System.exit(1);
        }
    }

    /** The patched ASM classes and Launch must match too: same patches, same ASM version. */
    private static List<String> compareResources(File oldJar, File newJar) throws Exception {
        List<String> differences = new ArrayList<String>();
        try (JarFile oldFile = new JarFile(oldJar); JarFile newFile = new JarFile(newJar)) {
            TreeSet<String> names = new TreeSet<String>();
            for (JarFile file : new JarFile[] {oldFile, newFile}) {
                for (Enumeration<JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (name.startsWith("org/objectweb/asm/") && name.endsWith(".class")) {
                        names.add(name);
                    }
                }
            }
            for (String name : names) {
                JarEntry oldEntry = oldFile.getJarEntry(name);
                JarEntry newEntry = newFile.getJarEntry(name);
                if (oldEntry == null || newEntry == null) {
                    differences.add(name + (oldEntry == null ? " (only in DeltaAgent)" : " (only in mcpc-java17)"));
                } else if (!text(read(oldFile, oldEntry)).equals(text(read(newFile, newEntry)))) {
                    differences.add(name);
                }
            }
            String oldLaunch = rename(text(read(oldFile, oldFile.getJarEntry("gg/nationsglory/mcpcj17/Launch.bin"))));
            String newLaunch = text(read(newFile, newFile.getJarEntry("fr/deltabuild/deltaagent/java17/Launch.bin")));
            if (!oldLaunch.equals(newLaunch)) {
                differences.add("Launch.bin");
            }
        }
        return differences;
    }

    private static String rename(String text) {
        for (String[] rename : RENAMES) {
            text = text.replace(rename[0], rename[1]);
        }
        return text;
    }

    private static String text(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)), 0);
        return out.toString();
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws Exception {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** One agent jar, isolated, with the same steps as its JVM transformers: sun.reflect, then IDs. */
    private static final class Agent {

        private final Method redirect;
        private final Method ids;

        Agent(File jar, String classPatcher, String idPatches) throws Exception {
            ClassLoader loader = new URLClassLoader(new URL[] {jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
            redirect = Class.forName(classPatcher, true, loader).getDeclaredMethod("redirectSunReflect", byte[].class);
            redirect.setAccessible(true);
            ids = Class.forName(idPatches, true, loader).getDeclaredMethod("patch", String.class, byte[].class);
            ids.setAccessible(true);
        }

        /** The patched class, or null when neither step changed it. */
        byte[] patch(String name, byte[] bytes) throws Exception {
            byte[] redirected = (byte[]) redirect.invoke(null, (Object) bytes);
            byte[] current = redirected != null ? redirected : bytes;
            byte[] raised = (byte[]) ids.invoke(null, name, current);
            if (raised != null) {
                return raised;
            }
            return redirected;
        }
    }

    private CompareMcpcJava17() {
    }
}
