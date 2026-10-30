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
        CALLS.put("org.spigotmc.AntiXray", new String[] {"Registries.blocks", "Registries.flag", "Registries.setFlag",
            "XrayIds.narrow"});
        CALLS.put("net.minecraft.item.ItemMap", new String[] {"MapHistogram.size", "MapHistogram.slot"});
        CALLS.put("net.minecraft.world.chunk.storage.ExtendedBlockStorage", new String[0]);
        CALLS.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", new String[] {"SectionIds.read", "SectionIds.write",
            "ChunkBiomes.read", "ChunkBiomes.write"});
        CALLS.put("net.minecraft.world.biome.BiomeGenBase", new String[0]);
        CALLS.put("net.minecraftforge.common.BiomeDictionary", new String[0]);
        CALLS.put("net.minecraft.world.chunk.Chunk", new String[] {"ChunkBiomes.biome"});
        CALLS.put("net.minecraft.world.gen.ChunkProviderServer", new String[] {"ChunkBiomes.generated"});
        CALLS.put("org.bukkit.craftbukkit.v1_6_R3.CraftWorld", new String[] {"ChunkBiomes.generated", "ChunkBiomes.setBukkit"});
        CALLS.put("net.minecraft.network.packet.Packet56MapChunks", new String[] {"ChunkPackets.extend"});
        CALLS.put("net.minecraft.network.NetServerHandler", new String[] {"ChunkPackets.onSend", "ChunkPackets.afterSend"});
        CALLS.put("net.minecraft.server.management.PlayerInstance", new String[] {"ChunkPackets.flushStart",
            "ChunkPackets.flushEnd"});
        CALLS.put("org.bukkit.craftbukkit.v1_6_R3.CraftChunkSnapshot", new String[] {"Snapshots.typeId"});
        CALLS.put("org.bukkit.craftbukkit.v1_6_R3.CraftChunk", new String[] {"Snapshots.widen", "Snapshots.b3"});
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
        assertEquals(25, CALLS.size());
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
    void sectionReplacementKeepsEveryMcpcMember() throws Exception {
        String name = "net/minecraft/world/chunk/storage/ExtendedBlockStorage";
        byte[] ours = StoragePatches.sectionClass();
        assertEquals(50, ours[7]);
        ClassNode mcpc = Asm.read(McpcClasses.read(name));
        ClassNode node = Asm.read(ours);
        List<String> missing = new ArrayList<String>();
        for (MethodNode method : mcpc.methods) {
            if ((method.access & Opcodes.ACC_PRIVATE) == 0 && !hasMethod(node, method.name, method.desc)) {
                missing.add(method.name + method.desc);
            }
        }
        for (FieldNode field : mcpc.fields) {
            if ((field.access & Opcodes.ACC_PRIVATE) == 0 && field(node, field.name) == null) {
                missing.add(field.name);
            }
        }
        String[][] ng = {
            {"getBlockA2Array", "()Lnet/minecraft/world/chunk/NibbleArray;"},
            {"setBlockA2Array", "(Lnet/minecraft/world/chunk/NibbleArray;)V"},
            {"createBlockA2Array", "()Lnet/minecraft/world/chunk/NibbleArray;"},
            {"clearA2Array", "()V"},
            {"getBlockB3Array", "()[B"},
            {"setBlockB3Array", "([B)V"},
            {"createBlockB3Array", "()[B"},
            {"clearB3Array", "()V"},
        };
        for (String[] method : ng) {
            if (!hasMethod(node, method[0], method[1])) {
                missing.add("NG " + method[0] + method[1]);
            }
        }
        assertTrue(missing.isEmpty(), missing.toString());
    }

    @Test
    void biomesGetSixteenBitsOnBothChunks() throws Exception {
        for (String name : new String[] {"net.minecraft.world.biome.BiomeGenBase", "net.minecraftforge.common.BiomeDictionary"}) {
            ClassNode node = patched(name, name.endsWith("BiomeGenBase") ? new BiomePatches.ListSizePatch(BiomePatches.BIOME)
                    : new BiomePatches.ListSizePatch("net/minecraftforge/common/BiomeDictionary$BiomeInfo"));
            assertTrue(hasConstant(Asm.require(node, "<clinit>", "()V"), 65536), name);
        }
        // Vanilla chunk (height module off): the field is added
        ClassNode vanilla = patched("net.minecraft.world.chunk.Chunk", new BiomePatches.ChunkPatch());
        assertNotNull(field(vanilla, BiomePatches.HIGH_FIELD));
        assertTrue(calls(vanilla, "ChunkBiomes.biome"));
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(vanilla.name,
                Asm.require(vanilla, "func_76591_a", "(IILnet/minecraft/world/biome/WorldChunkManager;)Lnet/minecraft/world/biome/BiomeGenBase;"));
    }

    private static boolean hasConstant(MethodNode method, int value) {
        for (AbstractInsnNode in = method.instructions.getFirst(); in != null; in = in.getNext()) {
            if (Asm.isInt(in, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return true;
            }
        }
        return false;
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
