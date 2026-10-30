package fr.deltabuild.deltaagent.bench;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Step 1 bench: the height module must patch classes exactly like DeltaHeight 1.0.0, except where
 * DeltaAgent changes it on purpose (listed in {@link #EXPECTED}). Each agent runs in its own class
 * loader, with launchwrapper for IClassTransformer.
 *
 * <p>Arguments: DeltaHeight.jar, DeltaAgent.jar, launchwrapper-1.8.jar, then the jars whose classes
 * are patched (MCPC+ in SRG names, plugins).
 */
public final class CompareDeltaHeight {

    /** Classes DeltaAgent changes on purpose, and why. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<String, String>();
    static {
        EXPECTED.put("net/minecraft/world/chunk/Chunk", "replacement: NGHeight chunks, superflat per world, 16 bit short[] IDs");
        EXPECTED.put("org/spigotmc/AntiXray", "new patch: 24 sections, Y - 64");
    }
    private static final int SHOWN = 20;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("usage: <DeltaHeight.jar> <DeltaAgent.jar> <launchwrapper.jar> <jar to scan>...");
        }
        File launchwrapper = new File(args[2]);
        Old old = new Old(new File(args[0]), launchwrapper);
        New neu = new New(new File(args[1]), launchwrapper);

        List<String> differences = new ArrayList<String>();
        Map<String, String> expectedSeen = new LinkedHashMap<String, String>();
        List<String> patched = new ArrayList<String>();
        int scanned = 0;
        PrintStream console = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // both agents log every patch
        try {
            for (int i = 3; i < args.length; i++) {
                try (JarFile jar = new JarFile(args[i])) {
                    for (JarEntry entry : Collections.list(jar.entries())) {
                        if (!entry.getName().endsWith(".class")) {
                            continue;
                        }
                        byte[] bytes = read(jar, entry);
                        String name = new ClassReader(bytes).getClassName();
                        byte[] before = old.patch(name, bytes);
                        byte[] after = neu.patch(name, bytes);
                        scanned++;
                        if (before == null && after == null) {
                            continue;
                        }
                        patched.add(name);
                        String oldText = before == null ? "(unchanged)" : text(before);
                        String newText = after == null ? "(unchanged)" : text(after);
                        if (oldText.equals(newText)) {
                            continue;
                        }
                        if (EXPECTED.containsKey(name)) {
                            expectedSeen.put(name, before == null || after == null ? "patched by one agent only"
                                    : memberDifferences(before, after));
                        } else {
                            differences.add(new File(args[i]).getName() + "!" + name);
                        }
                    }
                }
            }
        } finally {
            System.setOut(console);
        }

        System.out.println("Classes scanned: " + scanned + ", patched by either agent: " + patched.size()
                + ", unexpected differences: " + differences.size());
        for (Map.Entry<String, String> entry : expectedSeen.entrySet()) {
            System.out.println("  expected: " + entry.getKey() + " (" + EXPECTED.get(entry.getKey()) + ")");
            System.out.println("            " + entry.getValue());
        }
        for (int i = 0; i < differences.size() && i < SHOWN; i++) {
            System.out.println("  differs: " + differences.get(i));
        }
        if (!differences.isEmpty()) {
            System.exit(1);
        }
    }

    /** Members added, removed or changed between two versions of a class. */
    private static String memberDifferences(byte[] before, byte[] after) {
        Map<String, String> oldMembers = members(before);
        Map<String, String> newMembers = members(after);
        Set<String> names = new TreeSet<String>(oldMembers.keySet());
        names.addAll(newMembers.keySet());
        List<String> changes = new ArrayList<String>();
        for (String name : names) {
            String a = oldMembers.get(name);
            String b = newMembers.get(name);
            if (a == null) {
                changes.add("+" + name);
            } else if (b == null) {
                changes.add("-" + name);
            } else if (!a.equals(b)) {
                changes.add("~" + name);
            }
        }
        return String.join(", ", changes);
    }

    private static Map<String, String> members(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        Map<String, String> members = new LinkedHashMap<String, String>();
        for (FieldNode field : node.fields) {
            members.put(field.name, field.desc + " " + field.access + " " + field.value);
        }
        for (MethodNode method : node.methods) {
            Textifier textifier = new Textifier();
            method.accept(new TraceMethodVisitor(textifier));
            StringWriter out = new StringWriter();
            textifier.print(new PrintWriter(out));
            members.put(method.name + method.desc, out.toString());
        }
        return members;
    }

    /**
     * The class as text, without MAXSTACK: COMPUTE_MAXS of ASM 9.7 (DeltaAgent) is sometimes lower
     * than that of ASM 6.0 (DeltaHeight), both being valid.
     */
    private static String text(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(out)), 0);
        return out.toString().replaceAll("(?m)^ *MAXSTACK = \\d+$", "");
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws Exception {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static ClassLoader loader(File agent, File launchwrapper) throws Exception {
        return new URLClassLoader(new URL[] {agent.toURI().toURL(), launchwrapper.toURI().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    /** DeltaHeight: its LaunchClassLoader transformer, then its JVM level plugin patches. */
    private static final class Old {

        private final Object transformer;
        private final Method transform;
        private final Method plugins;

        Old(File jar, File launchwrapper) throws Exception {
            ClassLoader loader = loader(jar, launchwrapper);
            Class<?> type = Class.forName("fr.deltabuild.deltaheight.DeltaHeightTransformer", true, loader);
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            transformer = constructor.newInstance();
            transform = type.getMethod("transform", String.class, String.class, byte[].class);
            plugins = Class.forName("fr.deltabuild.deltaheight.PluginPatches", true, loader)
                    .getDeclaredMethod("transform", String.class, byte[].class);
            plugins.setAccessible(true);
        }

        byte[] patch(String name, byte[] bytes) throws Exception {
            byte[] out = (byte[]) transform.invoke(transformer, name.replace('/', '.'), name.replace('/', '.'), bytes);
            if (out != bytes && !Arrays.equals(out, bytes)) {
                return out;
            }
            return (byte[]) plugins.invoke(null, name, bytes);
        }
    }

    /** DeltaAgent: the game transformer with the height targets, then the JVM level plugin patches. */
    private static final class New {

        private final Object transformer;
        private final Method transform;
        private final Method plugins;

        New(File jar, File launchwrapper) throws Exception {
            ClassLoader loader = loader(jar, launchwrapper);
            Class<?> registryType = Class.forName("fr.deltabuild.deltaagent.core.PatchRegistry", true, loader);
            Object registry = registryType.getDeclaredConstructor().newInstance();
            Class.forName("fr.deltabuild.deltaagent.height.HeightModule", true, loader)
                    .getMethod("registerTargets", registryType).invoke(null, registry);
            Class<?> type = Class.forName("fr.deltabuild.deltaagent.core.GameTransformer", true, loader);
            transformer = type.getConstructor(registryType).newInstance(registry);
            transform = type.getMethod("transform", String.class, String.class, byte[].class);
            plugins = Class.forName("fr.deltabuild.deltaagent.height.PluginPatches", true, loader)
                    .getDeclaredMethod("transform", String.class, byte[].class);
            plugins.setAccessible(true);
        }

        byte[] patch(String name, byte[] bytes) throws Exception {
            byte[] out = (byte[]) transform.invoke(transformer, name.replace('/', '.'), name.replace('/', '.'), bytes);
            if (out != bytes && !Arrays.equals(out, bytes)) {
                return out;
            }
            return (byte[]) plugins.invoke(null, name, bytes);
        }
    }

    private CompareDeltaHeight() {
    }
}
