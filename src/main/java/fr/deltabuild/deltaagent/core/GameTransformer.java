package fr.deltabuild.deltaagent.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.tree.ClassNode;

/**
 * The single LaunchClassLoader transformer of the agent, placed right after FML's deobfuscation:
 * it sees MCP class names and SRG member names, and coremods run after it.
 */
public final class GameTransformer implements IClassTransformer {

    private final PatchRegistry registry;

    public GameTransformer(PatchRegistry registry) {
        this.registry = registry;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || transformedName == null) {
            return bytes;
        }
        PatchTarget target = registry.get(transformedName);
        if (target == null) {
            return bytes;
        }
        try {
            if (target.replacement != null) {
                Log.info(target.module, "Replaced " + transformedName + ".");
                return target.replacement;
            }
            ClassNode node = Asm.read(bytes);
            if (!Asm.isJava6OrOlder(node)) {
                throw new IllegalStateException("not Java 6 bytecode (class version " + (node.version & 0xFFFF) + ")");
            }
            int changes = target.patch.apply(node);
            if (changes <= 0) {
                throw new IllegalStateException("expected code not found");
            }
            Log.info(target.module, "Patched " + transformedName + " (" + changes + " change" + (changes > 1 ? "s" : "") + ").");
            return Asm.write(node);
        } catch (Throwable t) {
            if (target.critical) {
                Safety.stop(target.module, "Could not patch " + transformedName + ": " + t);
            }
            Log.warn(target.module, "Left " + transformedName + " unchanged: " + t);
            return bytes;
        }
    }
}
