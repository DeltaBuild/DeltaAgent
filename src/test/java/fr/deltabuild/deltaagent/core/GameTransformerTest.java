package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @BeforeEach
    void catchHalt() {
        SafetyHook.install();
    }

    @AfterEach
    void restoreHalt() {
        SafetyHook.uninstall();
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
        assertThrows(SafetyHook.Stopped.class, () -> new GameTransformer(registry).transform("x", "a.Target", bytes));
    }

    @Test
    void appliesSeveralPatchesInOrder() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("height", "a.Target", true, node -> addField(node, "first")));
        registry.add(PatchTarget.patch("ids", "a.Target", true, node -> addField(node, "second")));
        ClassNode node = Asm.read(new GameTransformer(registry).transform("x", "a.Target", emptyClass("a/Target", Opcodes.V1_6)));
        assertEquals("first", node.fields.get(0).name);
        assertEquals("second", node.fields.get(1).name);
    }

    @Test
    void patchesTheReplacementWhateverTheOrderOfAdding() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("ids", "a.Target", true, node -> addField(node, "added")));
        registry.add(PatchTarget.replace("height", "a.Target", emptyClass("a/Target", Opcodes.V1_6)));
        ClassNode node = Asm.read(new GameTransformer(registry).transform("x", "a.Target", emptyClass("a/Target", Opcodes.V1_5)));
        assertEquals(Opcodes.V1_6, node.version);
        assertEquals("added", node.fields.get(0).name);
    }

    @Test
    void skipsAFailedOptionalPatchButAppliesTheOthers() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("height", "a.Target", false, node -> {
            addField(node, "lost");
            throw new IllegalStateException("broken");
        }));
        registry.add(PatchTarget.patch("ids", "a.Target", true, node -> addField(node, "kept")));
        ClassNode node = Asm.read(new GameTransformer(registry).transform("x", "a.Target", emptyClass("a/Target", Opcodes.V1_6)));
        assertEquals(1, node.fields.size());
        assertEquals("kept", node.fields.get(0).name);
    }

    @Test
    void refusesTwoReplacementsForOneClass() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.replace("height", "a.Target", emptyClass("a/Target", Opcodes.V1_6)));
        assertThrows(IllegalStateException.class,
                () -> registry.add(PatchTarget.replace("ids", "a.Target", emptyClass("a/Target", Opcodes.V1_6))));
    }

    @Test
    void removesAWholeModule() {
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("height", "a.Shared", true, node -> 1));
        registry.add(PatchTarget.patch("ids", "a.Shared", true, node -> 1));
        registry.add(PatchTarget.patch("ids", "a.Alone", true, node -> 1));
        assertEquals(2, registry.removeModule("ids"));
        assertEquals(1, registry.size());
        assertEquals(1, registry.get("a.Shared").size());
        assertNull(registry.get("a.Alone"));
    }

    @Test
    void handsRuntimeClassesOverWhenTheLoaderHasNoBytes() {
        GameTransformer transformer = new GameTransformer(new PatchRegistry());
        byte[] bytes = transformer.transform("x", "fr.deltabuild.deltaagent.runtime.ItemIds", null);
        assertEquals("fr/deltabuild/deltaagent/runtime/ItemIds", Asm.read(bytes).name);
        assertNull(transformer.transform("x", "fr.deltabuild.deltaagent.runtime.Missing", null));
        assertNull(transformer.transform("x", "fr.deltabuild.deltaagent.core.Log", null));
        assertNull(transformer.transform("x", "a.Missing", null));
    }

    private static int addField(ClassNode node, String name) {
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, name, "I", null, null));
        return 1;
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
