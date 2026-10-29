package fr.deltabuild.deltaagent.ids.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class IdPatchesTest {

    private static final String CAPACITY = "fr/deltabuild/deltaagent/ids/legacy/IdCapacity";

    @Test
    void sizesBlockArraysFromTheCapacityAndChecksNewIds() {
        ClassNode block = read(IdPatches.patch("net/minecraft/block/Block", blockClass()));
        assertTrue(calls(method(block, "<clinit>"), CAPACITY, "blocks"));
        assertTrue(calls(method(block, "<init>"), CAPACITY, "checkBlock"));
    }

    @Test
    void ignoresClassesThatAreNotTargets() {
        assertNull(IdPatches.patch("net/minecraft/block/BlockStone", blockClass()));
    }

    @Test
    void capacityFallsBackToTheDefault() {
        assertEquals(65536, IdCapacity.readCapacity(null));
        assertEquals(65536, IdCapacity.readCapacity("4095"));
        assertEquals(65536, IdCapacity.readCapacity("lots"));
        assertEquals(1048576, IdCapacity.readCapacity(" 1048576 "));
    }

    @Test
    void placeholderBlocksBecomeAir() {
        assertEquals(4095, IdCapacity.storedId(4095));
        assertEquals(0, IdCapacity.storedId(4096));
        assertFalse(IdCapacity.storable(60000));
    }

    /** A Block-like class: static Block[4096] and a constructor taking the ID first. */
    private static byte[] blockClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "net/minecraft/block/Block", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "blocksList", "[Lnet/minecraft/block/Block;", null, null);

        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitIntInsn(Opcodes.SIPUSH, 4096);
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, "net/minecraft/block/Block");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "net/minecraft/block/Block", "blocksList", "[Lnet/minecraft/block/Block;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        throw new AssertionError("no " + name);
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        List<MethodInsnNode> calls = new ArrayList<MethodInsnNode>();
        method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode) {
                calls.add((MethodInsnNode) insn);
            }
        });
        for (MethodInsnNode call : calls) {
            if (call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
