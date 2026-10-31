package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.core.Asm;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The ids patches on WorldEdit 5.6 (libs/WorldEdit.jar, not published): each applies and verifies. */
@EnabledIf("present")
class PluginIdPatchesTest {

    static boolean present() {
        return jar().isFile();
    }

    private static File jar() {
        return new File(System.getProperty("deltaagent.test.worldEdit", "libs/WorldEdit.jar"));
    }

    @Test
    void everyWorldEditPatchAppliesAndVerifies() throws Exception {
        String[][] calls = {
            {"com/sk89q/worldedit/foundation/Block", null},
            {"com/sk89q/worldedit/blocks/BaseBlock", null},
            {"com/sk89q/worldedit/WorldEdit", null},
            {"com/sk89q/worldedit/bukkit/BukkitWorld", "PluginBlocks.setTypeIdAndData", "PluginBlocks.setData", "PluginBlocks.getData"},
            {"com/sk89q/worldedit/schematic/MCEditSchematicFormat", "PluginSchematics.block", "PluginSchematics.save"},
        };
        List<String> problems = new ArrayList<String>();
        for (String[] target : calls) {
            byte[] patched = PluginIdPatches.transform(target[0], read(target[0]));
            assertNotNull(patched, target[0]);
            ClassNode node = Asm.read(patched);
            for (int i = 1; i < target.length; i++) {
                if (target[i] != null && !calls(node, target[i])) {
                    problems.add(target[0] + ": no call to " + target[i]);
                }
            }
            for (MethodNode method : node.methods) {
                try {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                } catch (Exception e) {
                    problems.add(target[0] + "." + method.name + method.desc + ": " + e.getMessage());
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void worldEditBlocksTakeWideIdsAndMetadata() throws Exception {
        String name = "com/sk89q/worldedit/foundation/Block";
        final byte[] patched = PluginIdPatches.transform(name, read(name));
        ClassNode node = Asm.read(patched);
        for (FieldNode field : node.fields) {
            if (field.name.equals("id") || field.name.equals("data")) {
                assertEquals("I", field.desc, field.name);
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar().toURI().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(className)) {
                    if (className.equals("com.sk89q.worldedit.foundation.Block")) {
                        Class<?> loaded = findLoadedClass(className);
                        return loaded != null ? loaded : defineClass(className, patched, 0, patched.length);
                    }
                    return super.loadClass(className, resolve);
                }
            }
        }) {
            Class<?> block = loader.loadClass("com.sk89q.worldedit.foundation.Block");
            Object wide = block.getConstructor(int.class, int.class).newInstance(16777215, 65535);
            assertEquals(16777215, block.getMethod("getId").invoke(wide));
            assertEquals(65535, block.getMethod("getData").invoke(wide));
            Object vanilla = block.getConstructor(int.class, int.class).newInstance(60035, 2);
            assertEquals(60035, block.getMethod("getId").invoke(vanilla));
            InvocationTargetException tooBig = assertThrows(InvocationTargetException.class,
                    () -> block.getMethod("setData", int.class).invoke(wide, 65536));
            assertTrue(tooBig.getCause().getMessage().contains("above 65535"), tooBig.getCause().getMessage());
            assertThrows(InvocationTargetException.class, () -> block.getMethod("setId", int.class).invoke(wide, 16777216));
        }
    }

    private static byte[] read(String internalName) throws Exception {
        try (ZipFile zip = new ZipFile(jar())) {
            ZipEntry entry = zip.getEntry(internalName + ".class");
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static boolean calls(ClassNode node, String call) {
        String owner = call.substring(0, call.indexOf('.'));
        String name = call.substring(call.indexOf('.') + 1);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && ((MethodInsnNode) in).owner.endsWith("/" + owner) && ((MethodInsnNode) in).name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
