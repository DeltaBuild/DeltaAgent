package fr.deltabuild.deltaagent.height;

import fr.deltabuild.deltaagent.core.Bytes;
import fr.deltabuild.deltaagent.core.LaunchClassLoaders;
import fr.deltabuild.deltaagent.core.Safety;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * JVM level part of the height module: patches the plugin classes, which the LaunchClassLoader
 * transformers never see, and checks that Chunk was really replaced.
 */
final class HeightJvmTransformer implements ClassFileTransformer {

    private static final String CHUNK = "net/minecraft/world/chunk/Chunk";
    /** A field only the replacement Chunk has. */
    private static final byte[] REPLACEMENT_MARK = Bytes.ascii("dhLegacyPending");

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain domain, byte[] bytes) {
        if (className == null) {
            return null;
        }
        if (CHUNK.equals(className) && LaunchClassLoaders.is(loader)
                && (bytes == null || !Bytes.contains(bytes, REPLACEMENT_MARK))) {
            Safety.stop("height", "Chunk was not replaced, the server cannot read a world with the extended height.");
        }
        return PluginPatches.transform(className, bytes);
    }
}
