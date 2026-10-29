package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

class GameTransformerTest {

    /** Thrown instead of halting the JVM. */
    static final class Stopped extends RuntimeException {
        Stopped() {
            super("stopped");
        }
    }

    private Runnable realHalt;

    @BeforeEach
    void catchHalt() {
        realHalt = Safety.halt;
        Safety.halt = () -> {
            throw new Stopped();
        };
    }

    @AfterEach
    void restoreHalt() {
        Safety.halt = realHalt;
    }

    @Test
    void leavesOtherClassesAlone() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Target", true, node -> 1));
        byte[] bytes = emptyClass("a/Other", Opcodes.V1_6);
        assertSame(bytes, new GameTransformer(registry).transform("x", "a.Other", bytes));
    }

    @Test
    void appliesThePatchOfItsTarget() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Target", true, node -> {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "added", "I", null, null));
            return 1;
        }));
        byte[] patched = new GameTransformer(registry).transform("x", "a.Target", emptyClass("a/Target", Opcodes.V1_6));
        ClassNode node = Asm.read(patched);
        assertEquals("added", node.fields.get(0).name);
    }

    @Test
    void replacesAClassWhole() {
        byte[] replacement = emptyClass("a/Target", Opcodes.V1_6);
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.replace("test", "a.Target", replacement));
        assertArrayEquals(replacement,
                new GameTransformer(registry).transform("x", "a.Target", emptyClass("a/Target", Opcodes.V1_5)));
    }

    @Test
    void keepsTheClassWhenAnOptionalPatchFindsNothing() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Target", false, node -> 0));
        byte[] bytes = emptyClass("a/Target", Opcodes.V1_6);
        assertSame(bytes, new GameTransformer(registry).transform("x", "a.Target", bytes));
    }

    @Test
    void stopsWhenACriticalPatchFindsNothing() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Target", true, node -> 0));
        byte[] bytes = emptyClass("a/Target", Opcodes.V1_6);
        assertThrows(Stopped.class, () -> new GameTransformer(registry).transform("x", "a.Target", bytes));
    }

    @Test
    void refusesBytecodeNewerThanJava6() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Target", true, node -> 1));
        byte[] bytes = emptyClass("a/Target", Opcodes.V1_8);
        assertThrows(Stopped.class, () -> new GameTransformer(registry).transform("x", "a.Target", bytes));
    }

    @Test
    void refusesTwoPatchesForOneClass() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("height", "a.Target", true, node -> 1));
        assertThrows(IllegalStateException.class,
                () -> registry.add(PatchTarget.patch("ids", "a.Target", true, node -> 1)));
    }

    @Test
    void goesRightAfterTheDeobfuscationTransformer() {
        List<Object> transformers = new ArrayList<Object>(Arrays.asList(new Object(), new FakeDeobfuscationTransformer(), new Object()));
        assertEquals(2, GameTransformerInstaller.insertionIndex(transformers));
    }

    @Test
    void waitsForTheDeobfuscationTransformer() {
        assertEquals(-1, GameTransformerInstaller.insertionIndex(Arrays.asList(new Object(), new Object())));
    }

    @Test
    void neverGoesInTwice() {
        List<Object> transformers = Arrays.asList(new FakeDeobfuscationTransformer(), new GameTransformer(new PatchRegistry()));
        assertEquals(-1, GameTransformerInstaller.insertionIndex(transformers));
    }

    @Test
    void criticalNamesListsOnlyCriticalTargets() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("test", "a.Critical", true, node -> 1));
        registry.add(PatchTarget.patch("test", "a.Optional", false, node -> 1));
        assertTrue(registry.criticalClassNames().contains("a.Critical"));
        assertEquals(1, registry.criticalClassNames().size());
    }

    static final class FakeDeobfuscationTransformer {
    }

    static byte[] emptyClass(String name, int version) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
