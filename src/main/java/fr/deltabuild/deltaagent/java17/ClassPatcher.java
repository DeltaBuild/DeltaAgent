package fr.deltabuild.deltaagent.java17;

import fr.deltabuild.deltaagent.core.AgentPackages;
import fr.deltabuild.deltaagent.core.Bytes;
import fr.deltabuild.deltaagent.core.LaunchClassLoaders;
import fr.deltabuild.deltaagent.core.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * JVM transformer of the java17 module. It sees every class once all LaunchClassLoader
 * transformers have run, and:
 * <ul>
 * <li>replaces launchwrapper's {@code Launch} with a Java 9+ ready one ({@code Launch.bin});</li>
 * <li>serves the patched ASM 9.7 of the agent jar in place of MCPC+'s ASM 4;</li>
 * <li>redirects {@code sun.reflect} classes, removed in Java 9, to the agent's shims.</li>
 * </ul>
 */
public final class ClassPatcher implements ClassFileTransformer {

    /** JDK 8 internals removed in Java 9, and their replacements in this agent. */
    private static final String[][] REDIRECTS = {
        {"sun/reflect/ReflectionFactory", "fr/deltabuild/deltaagent/java17/shim/ReflectionFactory"},
        {"sun/reflect/ConstructorAccessor", "fr/deltabuild/deltaagent/java17/shim/ConstructorAccessor"},
        {"sun/reflect/FieldAccessor", "fr/deltabuild/deltaagent/java17/shim/FieldAccessor"},
    };
    private static final byte[] SUN_REFLECT_SLASH = Bytes.ascii("sun/reflect/");
    private static final byte[] SUN_REFLECT_DOT = Bytes.ascii("sun.reflect.");

    private static final String LAUNCH = "net/minecraft/launchwrapper/Launch";
    private static final String LAUNCH_RESOURCE = "Launch.bin";
    private static final String ASM_PREFIX = "org/objectweb/asm/";

    private final byte[] launch;
    /** This agent's jar, which also carries a recent ASM. */
    private final JarFile agentJar;
    private final Map<ClassLoader, Boolean> legacyAsm =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());
    private final Map<ClassLoader, Boolean> configuredLoaders =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    public ClassPatcher() {
        launch = readResource(LAUNCH_RESOURCE);
        if (launch == null) {
            Log.info("java17", "Missing patch resource: " + LAUNCH_RESOURCE);
        }
        Log.info("java17", (launch != null ? 1 : 0) + " class patch(es) loaded.");

        JarFile jar = null;
        try {
            URL url = ClassPatcher.class.getProtectionDomain().getCodeSource().getLocation();
            jar = new JarFile(new File(url.toURI()));
            if (jar.getEntry("org/objectweb/asm/ClassReader.class") == null) {
                Log.info("java17", "No ASM bundled in the agent jar.");
                jar.close();
                jar = null;
            }
        } catch (Throwable t) {
            Log.info("java17", "Could not open the agent jar, ASM will not be upgraded: " + t);
        }
        this.agentJar = jar;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain domain, byte[] original) {
        try {
            if (LaunchClassLoaders.is(loader) && configuredLoaders.put(loader, Boolean.TRUE) == null) {
                LaunchHooks.configure(loader, agentJar != null && isLegacyAsm(loader));
            }
            if (className == null) {
                return null;
            }
            if (className.startsWith(ASM_PREFIX)) {
                return agentJar != null && isLegacyAsm(loader) ? bundledClass(className) : null;
            }
            if (className.equals(LAUNCH) && launch != null && needsLaunchPatch(original)) {
                Log.info("java17", "Patched: " + className);
                return launch.clone();
            }
            if (loader == null || AgentPackages.isAgentClass(className)) {
                return null;
            }
            if (Bytes.contains(original, SUN_REFLECT_SLASH) || Bytes.contains(original, SUN_REFLECT_DOT)) {
                byte[] redirected = redirectSunReflect(original);
                if (redirected != null) {
                    Log.info("java17", "Redirected sun.reflect in: " + className);
                    return redirected;
                }
            }
        } catch (Throwable t) {
            Log.info("java17", "Could not patch " + className + ", leaving it untouched: " + t);
        }
        return null;
    }

    /**
     * Loads every bundled ASM class now. The JVM does not transform the classes loaded from within
     * a transformer: loaded there, they would come from the server's ASM 4 unchanged and mix with
     * the upgraded ones.
     */
    void preloadAsm() {
        if (agentJar == null) {
            return;
        }
        ClassLoader appLoader = ClassLoader.getSystemClassLoader();
        int loaded = 0;
        for (JarEntry entry : Collections.list(agentJar.entries())) {
            String name = entry.getName();
            if (name.startsWith(ASM_PREFIX) && name.endsWith(".class")) {
                try {
                    Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, appLoader);
                    loaded++;
                } catch (Throwable ignored) {
                    // Absent from the server's own ASM, or not loadable on its own: not needed.
                }
            }
        }
        Log.info("java17", loaded + " ASM classes preloaded.");
    }

    /**
     * The stock launchwrapper 1.8 Launch casts the application class loader to URLClassLoader.
     * Servers that already ship a Java 9+ ready Launch keep their own.
     */
    private static boolean needsLaunchPatch(byte[] original) {
        return Bytes.contains(original, Bytes.ascii("java/net/URLClassLoader"));
    }

    /** The bundled ASM version of a class, or null for an ASM 4 class that no longer exists. */
    private byte[] bundledClass(String className) {
        JarEntry entry = agentJar.getJarEntry(className + ".class");
        if (entry == null) {
            return null;
        }
        try (InputStream in = agentJar.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (Throwable t) {
            Log.info("java17", "Could not read bundled " + className + ": " + t);
            return null;
        }
    }

    /**
     * MCPC+ ships ASM 4, which cannot read Java 8+ bytecode nor run coremods written for a recent
     * ASM: it is then replaced by the ASM bundled in this agent. ASM 5 added type annotations;
     * servers already on ASM 5 or later keep their own.
     */
    private boolean isLegacyAsm(ClassLoader loader) {
        ClassLoader key = loader != null ? loader : ClassLoader.getPlatformClassLoader();
        Boolean legacy = legacyAsm.get(key);
        if (legacy == null) {
            byte[] classReader = null;
            try (InputStream in = key.getResourceAsStream("org/objectweb/asm/ClassReader.class")) {
                if (in != null) {
                    classReader = in.readAllBytes();
                }
            } catch (Throwable ignored) {
            }
            legacy = classReader != null && !Bytes.contains(classReader, Bytes.ascii("readTypeAnnotations"));
            if (legacy && agentJar != null) {
                Log.info("java17", "ASM 4 detected, upgrading it to the bundled ASM.");
            } else if (!legacy) {
                Log.info("java17", "Recent ASM detected, keeping the server's ASM classes.");
            }
            legacyAsm.put(key, legacy);
        }
        return legacy;
    }

    /**
     * Rewrites the UTF-8 constants of the class that name one of the {@link #REDIRECTS}, in internal
     * form (type references, descriptors) or dotted form (Class.forName strings). Returns null when
     * nothing changed.
     */
    static byte[] redirectSunReflect(byte[] b) {
        int count = u2(b, 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 256);
        out.write(b, 0, 10);
        boolean changed = false;
        int pos = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[pos] & 0xFF;
            int size;
            switch (tag) {
                case 1:
                    int length = u2(b, pos + 1);
                    String value = new String(b, pos + 3, length, StandardCharsets.ISO_8859_1);
                    String replaced = redirect(value);
                    if (replaced != value) {
                        byte[] bytes = replaced.getBytes(StandardCharsets.ISO_8859_1);
                        out.write(1);
                        out.write(bytes.length >>> 8);
                        out.write(bytes.length);
                        out.write(bytes, 0, bytes.length);
                        changed = true;
                        pos += 3 + length;
                        continue;
                    }
                    size = 3 + length;
                    break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    size = 5;
                    break;
                case 5: case 6:
                    size = 9;
                    i++;
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    size = 3;
                    break;
                case 15:
                    size = 4;
                    break;
                default:
                    throw new IllegalStateException("Unknown constant pool tag " + tag);
            }
            out.write(b, pos, size);
            pos += size;
        }
        if (!changed) {
            return null;
        }
        out.write(b, pos, b.length - pos);
        return out.toByteArray();
    }

    /** Returns {@code value} itself when it holds no redirected name. */
    private static String redirect(String value) {
        if (!value.contains("sun/reflect/") && !value.contains("sun.reflect.")) {
            return value;
        }
        String result = value;
        for (String[] redirect : REDIRECTS) {
            result = replaceName(result, redirect[0], redirect[1]);
            result = replaceName(result, redirect[0].replace('/', '.'), redirect[1].replace('/', '.'));
        }
        return result.equals(value) ? value : result;
    }

    /** Replaces whole class names only: sun/reflect/FieldAccessor, not sun/reflect/FieldAccessorImpl. */
    private static String replaceName(String value, String from, String to) {
        StringBuilder sb = null;
        int copied = 0;
        int search = 0;
        int index;
        while ((index = value.indexOf(from, search)) >= 0) {
            int end = index + from.length();
            search = end;
            if (end < value.length() && Character.isJavaIdentifierPart(value.charAt(end))) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(value.length() + 32);
            }
            sb.append(value, copied, index).append(to);
            copied = end;
        }
        if (sb == null) {
            return value;
        }
        return sb.append(value, copied, value.length()).toString();
    }

    private static int u2(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static byte[] readResource(String name) {
        try (InputStream in = ClassPatcher.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (Throwable t) {
            return null;
        }
    }
}
