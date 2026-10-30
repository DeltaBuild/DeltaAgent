package fr.deltabuild.deltaagent.height;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.Bytes;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.SafetyHook;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

class HeightModuleTest {

    @AfterEach
    void clear() {
        System.clearProperty("deltaheight.legacyView");
    }

    @Test
    void registersTheDeltaHeightTargetsAndAntiXray() {
        PatchRegistry registry = new PatchRegistry();
        HeightModule.registerTargets(registry);
        assertEquals(33, registry.size()); // 32 from DeltaHeight + AntiXray
        assertNotNull(registry.get("net.minecraft.world.chunk.Chunk"));
        assertNotNull(registry.get("org.spigotmc.AntiXray"));
        assertFalse(registry.get("org.spigotmc.AntiXray").critical());
        assertTrue(registry.get("net.minecraft.server.management.PlayerInstance").critical());
    }

    @Test
    void oldLegacyViewOptionStillAddsClasses() {
        System.setProperty("deltaheight.legacyView", "a.First, a.Second");
        PatchRegistry registry = new PatchRegistry();
        HeightModule.registerTargets(registry);
        assertNotNull(registry.get("a.First"));
        assertNotNull(registry.get("a.Second"));
    }

    @Test
    void replacementChunkIsJava6Bytecode() {
        byte[] chunk = HeightModule.chunkClass();
        assertEquals(50, chunk[7]);
        ClassNode node = Asm.read(chunk);
        assertEquals("net/minecraft/world/chunk/Chunk", node.name);
        assertTrue(Bytes.contains(chunk, Bytes.ascii("dhLegacyPending")));
        assertTrue(Bytes.contains(chunk, Bytes.ascii("NGHeight")));
    }

    @Test
    void antiXrayCoversTwentyFourSectionsFromYMinus64() throws Exception {
        byte[] bytes;
        try (InputStream in = FakeAntiXray.class.getResourceAsStream("FakeAntiXray.class")) {
            bytes = in.readAllBytes();
        }
        ClassNode node = Asm.read(bytes);
        assertEquals(2, new Patches.AntiXrayPatch().apply(node));
        Class<?> patched = new ClassLoader(null) {
            Class<?> define(byte[] b) {
                return defineClass("fr.deltabuild.deltaagent.height.FakeAntiXray", b, 0, b.length);
            }
        }.define(Asm.write(node));
        Object antiXray = patched.getDeclaredConstructor().newInstance();
        Method obfuscate = patched.getMethod("obfuscate", int.class, List.class);
        List<Integer> visited = new ArrayList<Integer>();
        obfuscate.invoke(antiXray, 0xFFFFFF, visited);
        assertEquals(24, visited.size());
        assertEquals(-64, (int) visited.get(0));
        assertEquals(304, (int) visited.get(23));
    }

    @Test
    void stopsWhenChunkWasNotReplaced() throws Exception {
        LaunchClassLoader loader = new LaunchClassLoader(new URL[0]);
        HeightJvmTransformer transformer = new HeightJvmTransformer();
        SafetyHook.install();
        try {
            assertThrows(SafetyHook.Stopped.class, () -> transformer.transform(loader, "net/minecraft/world/chunk/Chunk",
                    null, null, Bytes.ascii("vanilla chunk")));
            assertNull(transformer.transform(loader, "net/minecraft/world/chunk/Chunk", null, null, HeightModule.chunkClass()));
        } finally {
            SafetyHook.uninstall();
        }
    }

    @Test
    void leavesUnknownPluginClassesAlone() {
        assertNull(PluginPatches.transform("com/example/Plugin", new byte[] {1, 2, 3}));
    }
}
