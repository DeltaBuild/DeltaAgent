package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.ClassPatch;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Biome IDs on 16 bits: a biome list of 65536 entries as in NGClient, and the Biomes16 array of
 * each chunk, read, saved, filled at generation and set by Bukkit. The chunk protocol side is in
 * ChunkPackets (NGBI block).
 */
final class BiomePatches implements Opcodes {

    static final int BIOMES = 65536;
    static final String CHUNK = StoragePatches.CHUNK;
    static final String CHUNK_BIOMES = "fr/deltabuild/deltaagent/runtime/ChunkBiomes";
    static final String BIOME = "net/minecraft/world/biome/BiomeGenBase";
    static final String MANAGER = "net/minecraft/world/biome/WorldChunkManager";
    static final String HIGH_FIELD = "deltaBiomeHigh";
    static final String GENERATE = "func_73154_d";
    static final String PROVIDER = "net/minecraft/world/chunk/IChunkProvider";

    static void register(PatchRegistry registry) {
        add(registry, "net.minecraft.world.biome.BiomeGenBase", false, new ListSizePatch(BIOME));
        add(registry, "net.minecraftforge.common.BiomeDictionary", false, new ListSizePatch("net/minecraftforge/common/BiomeDictionary$BiomeInfo"));
        add(registry, "net.minecraft.world.chunk.Chunk", true, new ChunkPatch());
        add(registry, "net.minecraft.world.gen.ChunkProviderServer", false, new GeneratedPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftWorld", false, new GeneratedPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftWorld", false, new SetBiomePatch());
    }

    private static void add(PatchRegistry registry, String className, boolean critical, ClassPatch patch) {
        registry.add(PatchTarget.patch("ids", className, critical, patch));
    }

    /** Static init: the array of 256 biome entries gets 65536. */
    static final class ListSizePatch implements ClassPatch {
        private final String element;

        ListSizePatch(String element) {
            this.element = element;
        }

        public int apply(ClassNode cn) {
            MethodNode clinit = Asm.require(cn, "<clinit>", "()V");
            int n = 0;
            for (AbstractInsnNode in : clinit.instructions.toArray()) {
                if (in.getOpcode() == ANEWARRAY && element.equals(((TypeInsnNode) in).desc) && Asm.isInt(Asm.prev(in), 256)) {
                    clinit.instructions.set(Asm.prev(in), new LdcInsnNode(Integer.valueOf(BIOMES)));
                    n++;
                }
            }
            Asm.check(n == 1, "new " + element + "[256]");
            return n;
        }
    }

    /** Chunk: the Biomes16 field, and getBiomeGenForWorldCoords reading it (both chunk classes). */
    static final class ChunkPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            boolean declared = false;
            for (FieldNode field : cn.fields) {
                declared |= field.name.equals(HIGH_FIELD);
            }
            if (!declared) {
                cn.fields.add(new FieldNode(ACC_PUBLIC, HIGH_FIELD, "[B", null, null));
                n++;
            }
            MethodNode get = Asm.require(cn, "func_76591_a", "(IIL" + MANAGER + ";)L" + BIOME + ";");
            get.instructions.clear();
            get.tryCatchBlocks = new ArrayList<TryCatchBlockNode>();
            get.localVariables = new ArrayList<LocalVariableNode>();
            get.instructions.add(new VarInsnNode(ALOAD, 0));
            get.instructions.add(new VarInsnNode(ILOAD, 1));
            get.instructions.add(new VarInsnNode(ILOAD, 2));
            get.instructions.add(new VarInsnNode(ALOAD, 3));
            get.instructions.add(IdPatches.call(CHUNK_BIOMES, "biome", "(L" + CHUNK + ";IIL" + MANAGER + ";)L" + BIOME + ";"));
            get.instructions.add(new InsnNode(ARETURN));
            return n + 1;
        }
    }

    /** After a generator made a chunk: the biomes above 255 it could only write on 8 bits. */
    static final class GeneratedPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (in.getOpcode() == INVOKEINTERFACE && Asm.isCall(in, PROVIDER, GENERATE, "(II)L" + CHUNK + ";")) {
                        InsnList hook = new InsnList();
                        hook.add(new InsnNode(DUP));
                        hook.add(IdPatches.call(CHUNK_BIOMES, "generated", "(L" + CHUNK + ";)V"));
                        m.instructions.insert(in, hook);
                        n++;
                    }
                }
            }
            Asm.check(n > 0, "IChunkProvider.provideChunk call");
            return n;
        }
    }

    /** CraftWorld.setBiome: after the low byte, the full ID through ChunkBiomes. */
    static final class SetBiomePatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode m = Asm.require(cn, "setBiome", "(IILorg/bukkit/block/Biome;)V");
            int biome = -1;
            int chunk = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                AbstractInsnNode next = Asm.next(in);
                if (next != null && next.getOpcode() == ASTORE && in instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) in;
                    if (call.desc.endsWith(")L" + BIOME + ";")) {
                        biome = Asm.var(next);
                    } else if (call.desc.endsWith(")L" + CHUNK + ";")) {
                        chunk = Asm.var(next);
                    }
                }
            }
            Asm.check(biome >= 0 && chunk >= 0, "biome and chunk variables");
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() == BASTORE) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, chunk));
                    hook.add(new VarInsnNode(ILOAD, 1));
                    hook.add(new VarInsnNode(ILOAD, 2));
                    hook.add(new VarInsnNode(ALOAD, biome));
                    hook.add(IdPatches.call(CHUNK_BIOMES, "setBukkit", "(L" + CHUNK + ";IIL" + BIOME + ";)V"));
                    m.instructions.insert(in, hook);
                    return 1;
                }
            }
            throw new IllegalStateException("pattern not found: biome array store");
        }
    }

    private BiomePatches() {
    }
}
