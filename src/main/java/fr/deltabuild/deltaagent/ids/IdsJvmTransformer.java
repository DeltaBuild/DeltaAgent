package fr.deltabuild.deltaagent.ids;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * JVM level part of the ids module: the editing plugins, loaded by Bukkit's plugin class loaders,
 * which the LaunchClassLoader transformers never see. Does nothing once the module is left out.
 */
public final class IdsJvmTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (className == null || !IdsModule.active()) {
            return null;
        }
        return PluginIdPatches.transform(className, bytes);
    }
}
