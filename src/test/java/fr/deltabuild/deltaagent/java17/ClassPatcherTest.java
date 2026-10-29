package fr.deltabuild.deltaagent.java17;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class ClassPatcherTest {

    @Test
    void redirectsSunReflectToTheShims() {
        byte[] redirected = ClassPatcher.redirectSunReflect(usesSunReflect());
        assertNotNull(redirected);
        ClassNode node = read(redirected);
        MethodNode method = node.methods.get(0);
        List<String> owners = new ArrayList<String>();
        List<Object> constants = new ArrayList<Object>();
        method.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode) {
                owners.add(((MethodInsnNode) insn).owner);
            } else if (insn instanceof LdcInsnNode) {
                constants.add(((LdcInsnNode) insn).cst);
            }
        });
        assertTrue(owners.contains("fr/deltabuild/deltaagent/java17/shim/ReflectionFactory"));
        assertTrue(constants.contains("fr.deltabuild.deltaagent.java17.shim.FieldAccessor"));
        // Only whole class names move: FieldAccessorImpl still exists in the JDK.
        assertTrue(constants.contains("sun.reflect.FieldAccessorImpl"));
    }

    @Test
    void leavesClassesWithoutSunReflectAlone() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "a/Plain", null, "java/lang/Object", null);
        writer.visitEnd();
        assertNull(ClassPatcher.redirectSunReflect(writer.toByteArray()));
    }

    @Test
    void launchResourceCallsDeltaAgentHooks() throws Exception {
        byte[] launch;
        try (InputStream in = ClassPatcher.class.getResourceAsStream("Launch.bin")) {
            launch = in.readAllBytes();
        }
        ClassNode node = read(launch);
        assertEquals("net/minecraft/launchwrapper/Launch", node.name);
        List<String> owners = new ArrayList<String>();
        for (MethodNode method : node.methods) {
            method.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode) {
                    owners.add(((MethodInsnNode) insn).owner);
                }
            });
        }
        assertTrue(owners.contains("fr/deltabuild/deltaagent/java17/LaunchHooks"));
        assertFalse(new String(launch, StandardCharsets.ISO_8859_1).contains("nationsglory"));
    }

    /** A class calling sun.reflect.ReflectionFactory and naming two sun.reflect classes in strings. */
    private static byte[] usesSunReflect() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "a/UsesSunReflect", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "sun/reflect/ReflectionFactory", "getReflectionFactory",
                "()Lsun/reflect/ReflectionFactory;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("sun.reflect.FieldAccessor");
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("sun.reflect.FieldAccessorImpl");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
