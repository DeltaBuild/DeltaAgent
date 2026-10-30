package fr.deltabuild.deltaagent.core;

import java.util.List;
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
        if (transformedName == null) {
            return bytes;
        }
        if (bytes == null) {
            return RuntimeClasses.find(transformedName);
        }
        List<PatchTarget> targets = registry.get(transformedName);
        if (targets == null) {
            return bytes;
        }
        byte[] current = bytes;
        for (PatchTarget target : targets) {
            current = apply(target, transformedName, current);
        }
        return current;
    }

    /** One target on the bytes left by the previous one; a failed optional patch changes nothing. */
    private static byte[] apply(PatchTarget target, String className, byte[] bytes) {
        try {
            if (target.replacement != null) {
                Log.info(target.module, "Replaced " + className + ".");
                return target.replacement;
            }
            ClassNode node = Asm.read(bytes);
            int changes = target.patch.apply(node);
            if (changes <= 0) {
                throw new IllegalStateException("expected code not found");
            }
            Log.info(target.module, "Patched " + className + " (" + changes + " change" + (changes > 1 ? "s" : "") + ").");
            return Asm.write(node);
        } catch (Throwable t) {
            if (target.critical) {
                Safety.stop(target.module, "Could not patch " + className + ": " + t);
            }
            Log.warn(target.module, "Left " + className + " unchanged: " + t);
            return bytes;
        }
    }
}
