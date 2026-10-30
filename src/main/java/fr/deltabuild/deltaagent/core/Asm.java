package fr.deltabuild.deltaagent.core;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Reading, writing and pattern helpers for the game class patches (taken from DeltaHeight).
 *
 * <p>Frames are read expanded and written back as they are, never recomputed: computing them would
 * load classes in the middle of a transformation. Patches keep them valid, or only touch code
 * whose frames do not change.
 */
public final class Asm implements Opcodes {

    public static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    public static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Labels, line numbers and frames: nodes that are not instructions. */
    public static boolean isMeta(AbstractInsnNode node) {
        return node instanceof LabelNode || node instanceof LineNumberNode || node instanceof FrameNode;
    }

    /** The next real instruction, or null. */
    public static AbstractInsnNode next(AbstractInsnNode node) {
        if (node == null) {
            return null;
        }
        node = node.getNext();
        while (node != null && isMeta(node)) {
            node = node.getNext();
        }
        return node;
    }

    /** The previous real instruction, or null. */
    public static AbstractInsnNode prev(AbstractInsnNode node) {
        if (node == null) {
            return null;
        }
        node = node.getPrevious();
        while (node != null && isMeta(node)) {
            node = node.getPrevious();
        }
        return node;
    }

    /** @param desc null for any descriptor */
    public static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) {
                return method;
            }
        }
        return null;
    }

    public static MethodNode require(ClassNode node, String name, String desc) {
        MethodNode method = method(node, name, desc);
        if (method == null) {
            throw new IllegalStateException("method not found: " + node.name + "." + name + (desc == null ? "" : desc));
        }
        return method;
    }

    public static boolean isIload(AbstractInsnNode node, int var) {
        return node != null && node.getOpcode() == ILOAD && ((VarInsnNode) node).var == var;
    }

    public static boolean isIload(AbstractInsnNode node) {
        return node != null && node.getOpcode() == ILOAD;
    }

    public static int var(AbstractInsnNode node) {
        return ((VarInsnNode) node).var;
    }

    /** Whether the instruction pushes this int constant, whatever the opcode. */
    public static boolean isInt(AbstractInsnNode node, int value) {
        if (node == null) {
            return false;
        }
        int op = node.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) {
            return op - ICONST_0 == value;
        }
        if (op == BIPUSH || op == SIPUSH) {
            return ((IntInsnNode) node).operand == value;
        }
        if (op == LDC) {
            Object constant = ((LdcInsnNode) node).cst;
            return constant instanceof Integer && (Integer) constant == value;
        }
        return false;
    }

    /** The shortest instruction pushing this int. */
    public static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    /** Null arguments match anything. */
    public static boolean isCall(AbstractInsnNode node, String owner, String name, String desc) {
        if (!(node instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) node;
        return (owner == null || owner.equals(call.owner)) && (name == null || name.equals(call.name))
                && (desc == null || desc.equals(call.desc));
    }

    /** Null owner or name match anything. */
    public static boolean isField(AbstractInsnNode node, int opcode, String owner, String name) {
        if (!(node instanceof FieldInsnNode) || node.getOpcode() != opcode) {
            return false;
        }
        FieldInsnNode field = (FieldInsnNode) node;
        return (owner == null || owner.equals(field.owner)) && (name == null || name.equals(field.name));
    }

    /** Fails the patch when the expected code is not there. */
    public static void check(boolean found, String what) {
        if (!found) {
            throw new IllegalStateException("pattern not found: " + what);
        }
    }

    private Asm() {
    }
}
