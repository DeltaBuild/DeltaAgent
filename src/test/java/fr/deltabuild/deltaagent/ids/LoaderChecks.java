package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.PatchRegistry;
import java.lang.reflect.Method;

/** Runs the loader checks of a registry, as the game transformer installer does. */
final class LoaderChecks {

    private final PatchRegistry registry;

    LoaderChecks(PatchRegistry registry) {
        this.registry = registry;
    }

    void run(ClassLoader launchClassLoader) throws Exception {
        Method run = PatchRegistry.class.getDeclaredMethod("runLoaderChecks", ClassLoader.class);
        run.setAccessible(true);
        run.invoke(registry, launchClassLoader);
    }
}
