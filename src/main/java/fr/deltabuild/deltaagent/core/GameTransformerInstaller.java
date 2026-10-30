package fr.deltabuild.deltaagent.core;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;

/**
 * Puts {@link GameTransformer} into the LaunchClassLoader's transformer list, right after FML's
 * DeobfuscationTransformer, as soon as that one is there.
 *
 * <p>Runs as a JVM transformer only to get a hand on the LaunchClassLoader; it never changes a class.
 */
public final class GameTransformerInstaller implements ClassFileTransformer {

    private static final String DEOBFUSCATION = "DeobfuscationTransformer";

    private final PatchRegistry registry;
    private volatile boolean installed;

    public GameTransformerInstaller(PatchRegistry registry) {
        this.registry = registry;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (!installed && LaunchClassLoaders.is(loader)) {
            try {
                tryInstall(loader);
            } catch (Throwable t) {
                Safety.stop("core", "Could not install the game transformer: " + t);
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void tryInstall(ClassLoader launchClassLoader) throws Exception {
        if (installed) {
            return;
        }
        Field field = launchClassLoader.getClass().getDeclaredField("transformers");
        field.setAccessible(true);
        List transformers = (List) field.get(launchClassLoader);
        int position = insertionIndex(transformers);
        if (position < 0) {
            return; // FML has not added its deobfuscation yet
        }
        registry.runLoaderChecks(launchClassLoader);
        RuntimeClasses.addTo(launchClassLoader);
        transformers.add(position, new GameTransformer(registry));
        installed = true;
        Log.info("core", "Game transformer installed after " + transformers.get(position - 1).getClass().getName()
                + " (" + registry.size() + " patched class" + (registry.size() == 1 ? "" : "es") + ").");
        if (Log.debugEnabled()) {
            for (int i = 0; i < transformers.size(); i++) {
                Log.debug("core", "transformer " + i + ": " + transformers.get(i).getClass().getName());
            }
        }
        checkNothingLoadedTooEarly(launchClassLoader);
    }

    /** Index right after DeobfuscationTransformer, -1 while it is missing, or if we are already in. */
    static int insertionIndex(List<?> transformers) {
        int deobfuscation = -1;
        for (int i = 0; i < transformers.size(); i++) {
            Object transformer = transformers.get(i);
            if (transformer instanceof GameTransformer) {
                return -1;
            }
            if (transformer != null && transformer.getClass().getName().endsWith(DEOBFUSCATION)) {
                deobfuscation = i;
            }
        }
        return deobfuscation < 0 ? -1 : deobfuscation + 1;
    }

    /** A critical class defined before the transformer was in place can no longer be patched. */
    @SuppressWarnings("rawtypes")
    private void checkNothingLoadedTooEarly(ClassLoader launchClassLoader) {
        Map cached;
        try {
            Field field = launchClassLoader.getClass().getDeclaredField("cachedClasses");
            field.setAccessible(true);
            cached = (Map) field.get(launchClassLoader);
        } catch (Throwable t) {
            Log.warn("core", "Could not check the classes already loaded: " + t);
            return;
        }
        for (String name : registry.criticalClassNames()) {
            if (cached.containsKey(name)) {
                Safety.stop("core", name + " was loaded before the game transformer and cannot be patched.");
            }
        }
    }
}
