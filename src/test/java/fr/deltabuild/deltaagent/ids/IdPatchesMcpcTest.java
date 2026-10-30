package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.McpcClasses;
import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.ClassPatch;
import fr.deltabuild.deltaagent.core.GameTransformer;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import fr.deltabuild.deltaagent.core.SafetyHook;
import fr.deltabuild.deltaagent.height.HeightModule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The ids patches on the real MCPC+ classes: each finds its code, and the result still verifies. */
@EnabledIf("fr.deltabuild.deltaagent.McpcClasses#present")
class IdPatchesMcpcTest {

    /** Calls each patch must add to its class: owner.name. */
    private static final Map<String, String[]> CALLS = new TreeMap<String, String[]>();

    static {
        CALLS.put("net.minecraft.block.Block", new String[] {"Registries.blocks", "Registries.track", "Registries.onNewBlock"});
        CALLS.put("net.minecraft.item.Item", new String[] {"Registries.items", "Registries.track", "Registries.onNewItem"});
        CALLS.put("net.minecraft.stats.StatList", new String[] {"Registries.items", "Registries.track"});
        CALLS.put("org.bukkit.Material", new String[] {"Registries.items", "Registries.track"});
        CALLS.put("net.minecraftforge.common.Configuration", new String[] {"BlockWindow.searchTop", "BlockWindow.next",
            "Registries.reserveBlock", "Registries.reserveItem", "Registries.track"});
        CALLS.put("net.minecraft.entity.monster.EntityEnderman", new String[] {"Registries.track"});
        CALLS.put("net.minecraftforge.common.MinecraftForge", new String[] {"Registries.blocks"});
        CALLS.put("org.spigotmc.AntiXray", new String[] {"Registries.blocks", "Registries.flag", "Registries.setFlag"});
        CALLS.put("net.minecraft.item.ItemMap", new String[] {"Placeholders.mapHistogramSize"});
        CALLS.put("net.minecraft.world.chunk.Chunk", new String[] {"Placeholders.storable"});
        CALLS.put("net.minecraft.world.chunk.storage.ExtendedBlockStorage", new String[] {"Placeholders.storedId"});
        CALLS.put("net.minecraft.item.ItemStack", new String[] {"ItemIds.writeId", "ItemIds.readId"});
        CALLS.put("net.minecraft.network.packet.Packet", new String[] {"ItemIds.netId", "ItemIds.netTag", "ItemIds.readNetExt"});
        CALLS.put("net.minecraft.network.packet.Packet20NamedEntitySpawn", new String[] {"ItemIds.netId"});
        CALLS.put("net.minecraft.world.storage.WorldInfo", new String[] {"WorldFormats.read", "WorldFormats.write"});
    }

    private PrintStream console;
    private ByteArrayOutputStream log;

    @BeforeEach
    void setUp() {
        SafetyHook.install();
        console = System.out;
        log = new ByteArrayOutputStream();
        System.setOut(new PrintStream(log, true));
    }

    @AfterEach
    void tearDown() {
        System.setOut(console);
        SafetyHook.uninstall();
        Registries.reset();
    }

    @Test
    void everyPatchAppliesAndVerifies() throws Exception {
        PatchRegistry registry = new PatchRegistry();
        HeightModule.registerTargets(registry);
        IdsModule.start(registry);
        GameTransformer transformer = new GameTransformer(registry);
        assertEquals(15, CALLS.size());
        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, String[]> entry : CALLS.entrySet()) {
            String name = entry.getKey();
            byte[] patched = transformer.transform(name, name, McpcClasses.read(name.replace('.', '/')));
            ClassNode node = Asm.read(patched);
            for (String call : entry.getValue()) {
                if (!calls(node, call)) {
                    problems.add(name + ": no call to " + call);
                }
            }
            for (MethodNode method : node.methods) {
                try {
                    new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
                } catch (Exception e) {
                    problems.add(name + "." + method.name + method.desc + ": " + e.getMessage());
                }
            }
        }
        String output = log.toString();
        assertFalse(output.contains("WARNING"), output);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void trackedFieldsAreNoLongerFinal() throws Exception {
        ClassNode block = patched("net.minecraft.block.Block", new IdPatches.BlockPatch());
        for (String field : IdPatches.BLOCK_ARRAYS) {
            FieldNode node = field(block, field);
            assertNotNull(node, field);
            assertEquals(0, node.access & Opcodes.ACC_FINAL, field);
        }
    }

    @Test
    void chunkGuardWorksOnBothChunks() throws Exception {
        ClassNode vanilla = patched("net.minecraft.world.chunk.Chunk", new IdPatches.ChunkGuardPatch());
        assertTrue(calls(vanilla, "Placeholders.storable"));
        ClassNode node = Asm.read(chunkWithHeight());
        assertEquals(1, new IdPatches.ChunkGuardPatch().apply(node));
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, Asm.require(node, "func_76592_a", "(IIIII)Z"));
    }

    private static byte[] chunkWithHeight() {
        PatchRegistry registry = new PatchRegistry();
        HeightModule.registerTargets(registry);
        PatchTarget replace = registry.get("net.minecraft.world.chunk.Chunk").get(0);
        return new GameTransformer(registry(replace)).transform("x", "net.minecraft.world.chunk.Chunk", new byte[0]);
    }

    private static PatchRegistry registry(PatchTarget target) {
        PatchRegistry registry = new PatchRegistry();
        registry.add(target);
        return registry;
    }

    private static ClassNode patched(String name, ClassPatch patch) throws Exception {
        ClassNode node = Asm.read(McpcClasses.read(name.replace('.', '/')));
        assertTrue(patch.apply(node) > 0);
        return Asm.read(Asm.write(node));
    }

    private static boolean calls(ClassNode node, String call) {
        String owner = call.substring(0, call.indexOf('.'));
        String name = call.substring(call.indexOf('.') + 1);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && ((MethodInsnNode) in).owner.endsWith("/" + owner)
                        && ((MethodInsnNode) in).name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static FieldNode field(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) {
                return field;
            }
        }
        return null;
    }
}
