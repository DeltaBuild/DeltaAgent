package fr.deltabuild.deltaagent.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads and writes the game classes the agent patches.
 *
 * <p>Minecraft 1.6.4, Forge and MCPC+ classes are Java 6 bytecode: frames are dropped and not
 * recomputed, which Java 6 class files allow. Computing them would need to load classes in the
 * middle of a transformation.
 */
public final class Asm {

    public static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    public static boolean isJava6OrOlder(ClassNode node) {
        return (node.version & 0xFFFF) <= Opcodes.V1_6;
    }

    public static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private Asm() {
    }
}
