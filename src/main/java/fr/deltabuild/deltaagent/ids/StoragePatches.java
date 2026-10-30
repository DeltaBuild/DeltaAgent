package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.ClassPatch;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Block IDs on 24 bits in the world: the replaced section storage, its save format, the NGClient
 * chunk protocol, and the few game classes that read IDs on fewer bits.
 */
final class StoragePatches implements Opcodes {

    static final String EBS = "net/minecraft/world/chunk/storage/ExtendedBlockStorage";
    static final String CHUNK = "net/minecraft/world/chunk/Chunk";
    static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    static final String PACKET = "net/minecraft/network/packet/Packet";
    static final String SECTION_IDS = "fr/deltabuild/deltaagent/runtime/SectionIds";
    static final String CHUNK_PACKETS = "fr/deltabuild/deltaagent/runtime/ChunkPackets";
    static final String MAP_HISTOGRAM = "fr/deltabuild/deltaagent/runtime/MapHistogram";
    static final String XRAY_IDS = "fr/deltabuild/deltaagent/runtime/XrayIds";
    static final String SNAPSHOTS = "fr/deltabuild/deltaagent/runtime/Snapshots";
    static final String SNAPSHOT = "org/bukkit/craftbukkit/v1_6_R3/CraftChunkSnapshot";
    static final String SNAPSHOT_B3 = "deltaB3";
    private static final String SECTION_RESOURCE = "ExtendedBlockStorage.class.bin";

    static void register(PatchRegistry registry) {
        registry.add(PatchTarget.replace("ids", "net.minecraft.world.chunk.storage.ExtendedBlockStorage", sectionClass()));
        add(registry, "net.minecraft.world.chunk.storage.AnvilChunkLoader", true, new AnvilChunkLoaderPatch());
        // Network
        add(registry, "net.minecraft.network.packet.Packet56MapChunks", true, new Packet56Patch());
        add(registry, "net.minecraft.network.NetServerHandler", true, new SendPatch());
        add(registry, "net.minecraft.server.management.PlayerInstance", true, new FlushPatch());
        // Readers of IDs on 8 or 16 bits
        add(registry, "net.minecraft.item.ItemMap", false, new ItemMapPatch());
        add(registry, "org.spigotmc.AntiXray", false, new AntiXrayNarrowPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftChunkSnapshot", true, new SnapshotPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftChunk", false, new SnapshotFillPatch());
    }

    private static void add(PatchRegistry registry, String className, boolean critical, ClassPatch patch) {
        registry.add(PatchTarget.patch("ids", className, critical, patch));
    }

    /** The replacement section, compiled from src/replace/java to Java 6 bytecode by the build. */
    static byte[] sectionClass() {
        try (InputStream in = StoragePatches.class.getResourceAsStream(SECTION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + SECTION_RESOURCE);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("[DeltaAgent/ids] Cannot read the replacement ExtendedBlockStorage: " + e, e);
        }
    }

    // Save format

    /**
     * Sections read and written with their upper planes and palette sections, through SectionIds;
     * the chunk's Biomes16 array, through ChunkBiomes.
     */
    static final class AnvilChunkLoaderPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode read = Asm.require(cn, "func_75823_a",
                    "(Lnet/minecraft/world/World;L" + NBT + ";)L" + CHUNK + ";");
            int tag = varBefore(read, "Blocks");
            int n = 0;
            for (AbstractInsnNode in : read.instructions.toArray()) {
                if (Asm.isCall(in, EBS, "func_76672_e", "()V")) {
                    // Stack: section. read(section, tag) runs before the section counts its blocks.
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(DUP));
                    hook.add(new VarInsnNode(ALOAD, tag));
                    hook.add(IdPatches.call(SECTION_IDS, "read", "(L" + EBS + ";L" + NBT + ";)V"));
                    read.instructions.insertBefore(in, hook);
                    n++;
                }
            }
            Asm.check(n == 1, "section recount in readChunkFromNBT");
            n += biomeHook(read, "func_76616_a", "read", "(L" + CHUNK + ";L" + NBT + ";)V", true);
            MethodNode write = Asm.require(cn, "func_75820_a",
                    "(L" + CHUNK + ";Lnet/minecraft/world/World;L" + NBT + ";)V");
            AbstractInsnNode blocks = ldc(write, "Blocks");
            int sectionTag = Asm.var(Asm.prev(blocks));
            AbstractInsnNode section = Asm.next(blocks);
            Asm.check(section != null && section.getOpcode() == ALOAD, "section variable");
            for (AbstractInsnNode in = blocks; in != null; in = in.getNext()) {
                if (Asm.isCall(in, "net/minecraft/nbt/NBTTagList", "func_74742_a", "(Lnet/minecraft/nbt/NBTBase;)V")) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, sectionTag));
                    hook.add(new VarInsnNode(ALOAD, Asm.var(section)));
                    hook.add(IdPatches.call(SECTION_IDS, "write", "(L" + NBT + ";L" + EBS + ";)V"));
                    write.instructions.insertBefore(in, hook);
                    return n + 1 + biomeHook(write, "func_76605_m", "write", "(L" + NBT + ";L" + CHUNK + ";)V", false);
                }
            }
            throw new IllegalStateException("pattern not found: sections.appendTag(section)");
        }

        /**
         * After the call that moves the Biomes array (read: chunk.setBiomeArray(tag.getByteArray("Biomes")),
         * write: tag.setByteArray("Biomes", chunk.getBiomeArray())), the same for Biomes16.
         */
        private static int biomeHook(MethodNode m, String chunkCall, String name, String desc, boolean chunkFirst) {
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (!Asm.isCall(in, CHUNK, chunkCall, null)) {
                    continue;
                }
                AbstractInsnNode after;
                int chunk;
                int tag;
                if (chunkFirst) {
                    // ALOAD chunk; ALOAD tag; LDC "Biomes"; getByteArray; setBiomeArray
                    AbstractInsnNode ldc = Asm.prev(Asm.prev(in));
                    Asm.check(ldc instanceof LdcInsnNode && "Biomes".equals(((LdcInsnNode) ldc).cst), "\"Biomes\" read");
                    tag = Asm.var(Asm.prev(ldc));
                    chunk = Asm.var(Asm.prev(Asm.prev(ldc)));
                    after = in;
                } else {
                    // ALOAD tag; LDC "Biomes"; ALOAD chunk; getBiomeArray; setByteArray
                    AbstractInsnNode ldc = Asm.prev(Asm.prev(in));
                    Asm.check(ldc instanceof LdcInsnNode && "Biomes".equals(((LdcInsnNode) ldc).cst), "\"Biomes\" write");
                    chunk = Asm.var(Asm.prev(in));
                    tag = Asm.var(Asm.prev(ldc));
                    after = Asm.next(in);
                    Asm.check(Asm.isCall(after, NBT, "func_74773_a", null), "setByteArray(\"Biomes\")");
                }
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, chunkFirst ? chunk : tag));
                hook.add(new VarInsnNode(ALOAD, chunkFirst ? tag : chunk));
                hook.add(IdPatches.call(BiomePatches.CHUNK_BIOMES, name, desc));
                m.instructions.insert(after, hook);
                return 1;
            }
            throw new IllegalStateException("pattern not found: Chunk." + chunkCall);
        }
    }

    // Network

    /** Each chunk of a Packet56 gets the NG blocks after its vanilla data. */
    static final class Packet56Patch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode ctor = Asm.require(cn, "<init>", "(Ljava/util/List;)V");
            for (AbstractInsnNode in : ctor.instructions.toArray()) {
                if (!Asm.isCall(in, "net/minecraft/network/packet/Packet51MapChunk", "func_73594_a", null)) {
                    continue;
                }
                AbstractInsnNode chunk = Asm.prev(Asm.prev(Asm.prev(in)));
                Asm.check(chunk != null && chunk.getOpcode() == ALOAD, "chunk argument of getMapChunkData");
                InsnList hook = new InsnList();
                hook.add(new InsnNode(DUP));
                hook.add(new VarInsnNode(ALOAD, Asm.var(chunk)));
                hook.add(IdPatches.call(CHUNK_PACKETS, "extend",
                        "(Lnet/minecraft/network/packet/Packet51MapChunkData;L" + CHUNK + ";)V"));
                ctor.instructions.insert(in, hook);
                return 1;
            }
            return 0;
        }
    }

    /** sendPacketToPlayer: a packet that cannot carry its IDs is replaced first, what it leaves is sent after. */
    static final class SendPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode send = Asm.require(cn, "func_72567_b", "(L" + PACKET + ";)V");
            InsnList start = new InsnList();
            start.add(new VarInsnNode(ALOAD, 1));
            start.add(new VarInsnNode(ALOAD, 0));
            start.add(IdPatches.call(CHUNK_PACKETS, "onSend", "(L" + PACKET + ";L" + cn.name + ";)L" + PACKET + ";"));
            start.add(new VarInsnNode(ASTORE, 1));
            send.instructions.insert(start);
            return 1 + beforeReturns(send, CHUNK_PACKETS, "afterSend", "(L" + cn.name + ";)V", true);
        }
    }

    /** PlayerInstance.flushPendingChanges marks its start and end, so a chunk is sent whole once per flush. */
    static final class FlushPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode flush = Asm.require(cn, "func_73254_a", "()V");
            flush.instructions.insert(IdPatches.call(CHUNK_PACKETS, "flushStart", "()V"));
            return 1 + beforeReturns(flush, CHUNK_PACKETS, "flushEnd", "()V", false);
        }
    }

    // Readers of IDs on fewer bits

    /** ItemMap: a 4096 slot histogram per pixel, wide IDs counted under a block of the same color. */
    static final class ItemMapPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode m = Asm.require(cn, "func_77872_a", null);
            int n = IdPatches.replaceLength(m, IdPatches.BLOCK, IdPatches.BLOCKS_LIST,
                    IdPatches.call(MAP_HISTOGRAM, "size", "()I"));
            Asm.check(n == 2, "map histogram size, found " + n);
            int histogram = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() == NEWARRAY && ((IntInsnNode) in).operand == T_INT && Asm.next(in).getOpcode() == ASTORE) {
                    histogram = Asm.var(Asm.next(in));
                    break;
                }
            }
            Asm.check(histogram >= 0, "histogram array");
            int slots = 0;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                AbstractInsnNode index = Asm.next(in);
                if (in.getOpcode() == ALOAD && Asm.var(in) == histogram && Asm.isIload(index)
                        && Asm.next(index) != null && Asm.next(index).getOpcode() == DUP2) {
                    m.instructions.insert(index, IdPatches.call(MAP_HISTOGRAM, "slot", "(I)I"));
                    slots++;
                }
            }
            Asm.check(slots >= 1, "histogram count by block ID");
            return n + slots;
        }
    }

    /** Spigot's anti-xray only hides positions whose ID is the low byte it reads. */
    static final class AntiXrayNarrowPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode m = Asm.require(cn, "obfuscate", "(III[BLnet/minecraft/world/World;)V");
            int section = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (Asm.isInt(in, 1) && Asm.isIload(Asm.next(in)) && Asm.next(Asm.next(in)).getOpcode() == ISHL) {
                    section = Asm.var(Asm.next(in));
                    break;
                }
            }
            Asm.check(section >= 0, "section variable");
            List<Integer> loops = new ArrayList<Integer>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (Asm.isIload(in) && Asm.var(in) != section && Asm.isInt(Asm.next(in), 16)
                        && Asm.next(Asm.next(in)).getOpcode() == IF_ICMPGE && !loops.contains(Asm.var(in))) {
                    loops.add(Asm.var(in));
                }
            }
            Asm.check(loops.size() == 3, "y, z, x loops, found " + loops);
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (Asm.isCall(in, IdPatches.REGISTRIES, "flag", "([ZI)Z")) {
                    InsnList narrow = new InsnList();
                    narrow.add(new VarInsnNode(ALOAD, 5));
                    narrow.add(new VarInsnNode(ILOAD, 1));
                    narrow.add(new VarInsnNode(ILOAD, 2));
                    narrow.add(new VarInsnNode(ILOAD, section));
                    narrow.add(new VarInsnNode(ILOAD, loops.get(2)));
                    narrow.add(new VarInsnNode(ILOAD, loops.get(0)));
                    narrow.add(new VarInsnNode(ILOAD, loops.get(1)));
                    narrow.add(IdPatches.call(XRAY_IDS, "narrow", "(ZLnet/minecraft/world/World;IIIIII)Z"));
                    m.instructions.insert(in, narrow);
                    return 1;
                }
            }
            return 0;
        }
    }

    /** CraftChunkSnapshot: a plane for bits 16-23, and IDs read as unsigned. */
    static final class SnapshotPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            cn.fields.add(new FieldNode(ACC_PUBLIC, SNAPSHOT_B3, "[[B", null, null));
            MethodNode get = Asm.require(cn, "getBlockTypeId", "(III)I");
            int n = 1;
            for (AbstractInsnNode in : get.instructions.toArray()) {
                if (in.getOpcode() == IRETURN) {
                    InsnList wide = new InsnList();
                    wide.add(new VarInsnNode(ALOAD, 0));
                    wide.add(new FieldInsnNode(GETFIELD, cn.name, SNAPSHOT_B3, "[[B"));
                    wide.add(new VarInsnNode(ILOAD, 1));
                    wide.add(new VarInsnNode(ILOAD, 2));
                    wide.add(new VarInsnNode(ILOAD, 3));
                    wide.add(IdPatches.call(SNAPSHOTS, "typeId", "(I[[BIII)I"));
                    get.instructions.insertBefore(in, wide);
                    n++;
                }
            }
            Asm.check(n > 1, "getBlockTypeId return");
            return n;
        }
    }

    /** CraftChunk.getChunkSnapshot: Add2 into the short IDs, BlocksB3 copied to the snapshot. */
    static final class SnapshotFillPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode m = Asm.require(cn, "getChunkSnapshot", "(ZZZ)Lorg/bukkit/ChunkSnapshot;");
            int sections = -1;
            int ids = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                AbstractInsnNode next = Asm.next(in);
                if (sections < 0 && Asm.isCall(in, CHUNK, "func_76587_i", null) && next.getOpcode() == ASTORE) {
                    sections = Asm.var(next);
                } else if (ids < 0 && in.getOpcode() == ANEWARRAY && "[S".equals(((TypeInsnNode) in).desc)
                        && next.getOpcode() == ASTORE) {
                    ids = Asm.var(next);
                }
            }
            Asm.check(sections >= 0 && ids >= 0, "sections and short IDs");
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (Asm.isCall(in, SNAPSHOT, "<init>", null)) {
                    // Stack: snapshot.
                    InsnList fill = new InsnList();
                    fill.add(new VarInsnNode(ALOAD, ids));
                    fill.add(new VarInsnNode(ALOAD, sections));
                    fill.add(IdPatches.call(SNAPSHOTS, "widen", "([[S[L" + EBS + ";)V"));
                    fill.add(new InsnNode(DUP));
                    fill.add(new VarInsnNode(ALOAD, sections));
                    fill.add(IdPatches.call(SNAPSHOTS, "b3", "([L" + EBS + ";)[[B"));
                    fill.add(new FieldInsnNode(PUTFIELD, SNAPSHOT, SNAPSHOT_B3, "[[B"));
                    m.instructions.insert(in, fill);
                    return 1;
                }
            }
            return 0;
        }
    }

    // Helpers

    private static AbstractInsnNode ldc(MethodNode m, String text) {
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof LdcInsnNode && text.equals(((LdcInsnNode) in).cst)) {
                return in;
            }
        }
        throw new IllegalStateException("pattern not found: \"" + text + "\" in " + m.name);
    }

    /** The variable loaded right before a string constant: the compound read with that key. */
    private static int varBefore(MethodNode m, String key) {
        AbstractInsnNode load = Asm.prev(ldc(m, key));
        Asm.check(load != null && load.getOpcode() == ALOAD, "compound read with \"" + key + "\"");
        return Asm.var(load);
    }

    private static int beforeReturns(MethodNode m, String owner, String name, String desc, boolean loadThis) {
        int n = 0;
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (in.getOpcode() == RETURN) {
                InsnList hook = new InsnList();
                if (loadThis) {
                    hook.add(new VarInsnNode(ALOAD, 0));
                }
                hook.add(new MethodInsnNode(INVOKESTATIC, owner, name, desc, false));
                m.instructions.insertBefore(in, hook);
                n++;
            }
        }
        Asm.check(n > 0, m.name + " return");
        return n;
    }

    private StoragePatches() {
    }
}
