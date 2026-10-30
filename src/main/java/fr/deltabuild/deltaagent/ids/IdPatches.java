package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.ClassPatch;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches of the game classes for the block and item registries and for items above 31999. They
 * only add straight calls to the agent. The 24-bit storage has its own, see {@link StoragePatches}.
 */
final class IdPatches implements Opcodes {

    static final String REGISTRIES = "fr/deltabuild/deltaagent/ids/Registries";
    static final String WINDOW = "fr/deltabuild/deltaagent/ids/BlockWindow";
    static final String ITEM_IDS = "fr/deltabuild/deltaagent/runtime/ItemIds";
    static final String WORLD_FORMATS = "fr/deltabuild/deltaagent/runtime/WorldFormats";

    static final String BLOCK = "net/minecraft/block/Block";
    static final String ITEM = "net/minecraft/item/Item";
    static final String ITEM_STACK = "net/minecraft/item/ItemStack";
    static final String NBT = "net/minecraft/nbt/NBTTagCompound";
    static final String PROPERTY = "net/minecraftforge/common/Property";
    static final String BLOCKS_LIST = "field_71973_m";
    static final String ITEMS_LIST = "field_77698_e";

    static final String[] BLOCK_ARRAYS = {"field_71973_m", "field_71970_n", "field_71971_o", "field_71985_p",
        "field_71984_q", "field_71982_s", "blockFireSpreadSpeed", "blockFlammability"};

    static void register(PatchRegistry registry) {
        declare("net.minecraft.block.Block", Registries.Kind.BLOCK, BLOCK_ARRAYS);
        declare("net.minecraft.item.Item", Registries.Kind.ITEM, ITEMS_LIST);
        declare("net.minecraft.stats.StatList", Registries.Kind.BLOCK, "field_75934_C");
        declare("net.minecraft.stats.StatList", Registries.Kind.ITEM, "field_75928_D", "field_75929_E", "field_75930_F");
        declare("net.minecraft.entity.monster.EntityEnderman", Registries.Kind.BLOCK, "field_70827_d");
        declare("org.bukkit.Material", Registries.Kind.ITEM, "byId");
        declare("net.minecraftforge.common.Configuration", Registries.Kind.ITEM, "configMarkers");

        // Registries
        add(registry, "net.minecraft.block.Block", true, new BlockPatch());
        add(registry, "net.minecraft.item.Item", true, new ItemPatch());
        add(registry, "net.minecraft.stats.StatList", true, new StatListPatch());
        add(registry, "org.bukkit.Material", true, new MaterialPatch());
        add(registry, "net.minecraftforge.common.Configuration", true, new ConfigurationPatch());
        add(registry, "net.minecraft.entity.monster.EntityEnderman", false, new TrackPatch());
        add(registry, "net.minecraftforge.common.MinecraftForge", false, new EndermanSizePatch());
        add(registry, "org.spigotmc.AntiXray", false, new AntiXrayPatch());
        // Items above 31999: NBT, network, level.dat
        add(registry, "net.minecraft.item.ItemStack", true, new ItemStackPatch());
        add(registry, "net.minecraft.network.packet.Packet", true, new PacketPatch());
        add(registry, "net.minecraft.network.packet.Packet20NamedEntitySpawn", true, new HeldItemPatch());
        registerGuard(registry);
    }

    static void registerGuard(PatchRegistry registry) {
        add(registry, "net.minecraft.world.storage.WorldInfo", true, new WorldInfoPatch());
    }

    private static void declare(String className, Registries.Kind kind, String... fields) {
        for (String field : fields) {
            Registries.declare(className, field, kind);
        }
    }

    private static void add(PatchRegistry registry, String className, boolean critical, ClassPatch patch) {
        registry.add(PatchTarget.patch("ids", className, critical, patch));
    }

    // Registries

    static final class BlockPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int arrays = sizeArrays(Asm.require(cn, "<clinit>", "()V"), 4096, "blocks");
            Asm.check(arrays == BLOCK_ARRAYS.length, BLOCK_ARRAYS.length + " block arrays, found " + arrays);
            return arrays + track(cn, BLOCK_ARRAYS) + hookConstructors(cn, "onNewBlock", 0);
        }
    }

    static final class ItemPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int arrays = sizeArrays(Asm.require(cn, "<clinit>", "()V"), 32000, "items");
            Asm.check(arrays == 1, "itemsList");
            return arrays + track(cn, ITEMS_LIST) + hookConstructors(cn, "onNewItem", 256);
        }
    }

    /** Statistics are indexed by block and item ID: placing or using a large one must not overflow. */
    static final class StatListPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int arrays = 0;
            for (String name : new String[] {"func_75918_d", "func_75920_a", "func_75926_b"}) {
                arrays += sizeArrays(Asm.require(cn, name, null), 32000, "items");
            }
            Asm.check(arrays == 3, "3 item statistic arrays, found " + arrays);
            return arrays + track(cn, "field_75934_C", "field_75928_D", "field_75929_E", "field_75930_F");
        }
    }

    /** MCPC+ adds a Bukkit material for every block and item, at byId[id], with no bound check. */
    static final class MaterialPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int arrays = sizeArrays(Asm.require(cn, "<clinit>", "()V"), 32000, "items");
            Asm.check(arrays == 1, "byId");
            return arrays + track(cn, "byId");
        }
    }

    static final class TrackPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            return track(cn, "field_70827_d");
        }
    }

    /** MinecraftForge.initialize gives endermen a table of 4096 blocks they can carry. */
    static final class EndermanSizePatch implements ClassPatch {
        public int apply(ClassNode cn) {
            return sizeArrays(Asm.require(cn, "initialize", "()V"), 4096, "blocks");
        }
    }

    /**
     * Forge's Configuration: room for every ID a config file holds, free block IDs taken from the
     * NGClient window, free item IDs below 32000 as before.
     */
    static final class ConfigurationPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            for (MethodNode m : cn.methods) {
                if (m.name.equals("getBlock") && m.desc.startsWith("(Ljava/lang/String;Ljava/lang/String;I")) {
                    n += replaceLength(m, BLOCK, BLOCKS_LIST, call(WINDOW, "searchTop", "()I"));
                }
            }
            Asm.check(n == 2, "getBlock upper bounds, found " + n);
            MethodNode block = Asm.require(cn, "getBlockInternal", null);
            n += reserve(block, "reserveBlock", 3);
            int steps = 0;
            for (AbstractInsnNode in : block.instructions.toArray()) {
                if (in instanceof IincInsnNode && ((IincInsnNode) in).incr == -1) {
                    int var = ((IincInsnNode) in).var;
                    InsnList next = new InsnList();
                    next.add(new VarInsnNode(ILOAD, var));
                    next.add(call(WINDOW, "next", "(I)I"));
                    next.add(new VarInsnNode(ISTORE, var));
                    block.instructions.insertBefore(in, next);
                    block.instructions.remove(in);
                    steps++;
                }
            }
            Asm.check(steps == 1, "block search loop");
            MethodNode item = Asm.require(cn, "getItem",
                    "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)L" + PROPERTY + ";");
            n += reserve(item, "reserveItem", 3);
            int vanilla = replaceLength(item, BLOCK, BLOCKS_LIST, Asm.pushInt(4096))
                    + replaceLength(item, ITEM, ITEMS_LIST, Asm.pushInt(32000));
            Asm.check(vanilla == 2, "getItem ranges");
            return n + steps + vanilla + track(cn, "configMarkers");
        }

        /** The ID argument and every Property.getInt() go through Registries.<method> first. */
        private static int reserve(MethodNode m, String method, int idVar) {
            InsnList start = new InsnList();
            start.add(new VarInsnNode(ILOAD, idVar));
            start.add(call(REGISTRIES, method, "(I)I"));
            start.add(new VarInsnNode(ISTORE, idVar));
            m.instructions.insert(start);
            int n = 1;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (Asm.isCall(in, PROPERTY, "getInt", "()I")) {
                    m.instructions.insert(in, call(REGISTRIES, method, "(I)I"));
                    n++;
                }
            }
            Asm.check(n > 1, "Property.getInt in " + m.name);
            return n;
        }
    }

    /** Spigot's anti-xray: a table as long as the block registry, read and written within bounds. */
    static final class AntiXrayPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = sizeArrays(Asm.require(cn, "<init>", null), Short.MAX_VALUE, "blocks");
            Asm.check(n == 1, "obfuscateBlocks size");
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (!Asm.isField(in, GETFIELD, cn.name, "obfuscateBlocks")) {
                        continue;
                    }
                    AbstractInsnNode index = Asm.next(in);
                    AbstractInsnNode op = Asm.next(index);
                    if (Asm.isIload(index) && op.getOpcode() == BALOAD) {
                        m.instructions.set(op, call(REGISTRIES, "flag", "([ZI)Z"));
                        n++;
                    } else if (Asm.isIload(index) && Asm.next(op) != null && Asm.next(op).getOpcode() == BASTORE) {
                        m.instructions.set(Asm.next(op), call(REGISTRIES, "setFlag", "([ZIZ)V"));
                        n++;
                    }
                }
            }
            return n;
        }
    }

    // Items above 31999

    /** NBT: "id" short 0 plus "idExt" int above 31999, as NGClient reads and writes them. */
    static final class ItemStackPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            MethodNode write = Asm.require(cn, "func_77955_b", "(L" + NBT + ";)L" + NBT + ";");
            for (AbstractInsnNode in : write.instructions.toArray()) {
                AbstractInsnNode narrow = Asm.next(in);
                AbstractInsnNode set = Asm.next(narrow);
                if (Asm.isField(in, GETFIELD, ITEM_STACK, "field_77993_c") && narrow.getOpcode() == I2S
                        && Asm.isCall(set, NBT, "func_74777_a", "(Ljava/lang/String;S)V")) {
                    write.instructions.remove(narrow);
                    write.instructions.set(set, call(ITEM_IDS, "writeId", "(L" + NBT + ";Ljava/lang/String;I)V"));
                    n++;
                    break;
                }
            }
            Asm.check(n == 1, "writeToNBT id");
            MethodNode read = Asm.require(cn, "func_77963_c", "(L" + NBT + ";)V");
            for (AbstractInsnNode in : read.instructions.toArray()) {
                AbstractInsnNode get = Asm.next(in);
                if (in instanceof LdcInsnNode && "id".equals(((LdcInsnNode) in).cst)
                        && Asm.isCall(get, NBT, "func_74765_d", "(Ljava/lang/String;)S")
                        && Asm.isField(Asm.next(get), PUTFIELD, ITEM_STACK, "field_77993_c")) {
                    read.instructions.set(get, call(ITEM_IDS, "readId", "(L" + NBT + ";Ljava/lang/String;)I"));
                    n++;
                    break;
                }
            }
            Asm.check(n == 2, "readFromNBT id");
            return n;
        }
    }

    /** Network item stacks: short 0 plus idExt in a copy of the tag, as NGClient's Packet does. */
    static final class PacketPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            MethodNode write = Asm.require(cn, "func_73270_a", "(L" + ITEM_STACK + ";Ljava/io/DataOutput;)V");
            for (AbstractInsnNode in : write.instructions.toArray()) {
                if (Asm.isField(in, GETFIELD, ITEM_STACK, "field_77993_c")
                        && Asm.isCall(Asm.next(in), "java/io/DataOutput", "writeShort", "(I)V")) {
                    write.instructions.insert(in, call(ITEM_IDS, "netId", "(I)I"));
                    n++;
                } else if (Asm.isCall(in, cn.name, "func_73275_a", "(L" + NBT + ";Ljava/io/DataOutput;)V")) {
                    // Stack: tag, out. The tag goes through netTag(tag, stack) under the output.
                    InsnList swap = new InsnList();
                    swap.add(new InsnNode(SWAP));
                    swap.add(new VarInsnNode(ALOAD, 0));
                    swap.add(call(ITEM_IDS, "netTag", "(L" + NBT + ";L" + ITEM_STACK + ";)L" + NBT + ";"));
                    swap.add(new InsnNode(SWAP));
                    write.instructions.insertBefore(in, swap);
                    n++;
                }
            }
            Asm.check(n == 2, "writeItemStack id and tag, found " + n);
            MethodNode read = Asm.require(cn, "func_73276_c", "(Ljava/io/DataInput;)L" + ITEM_STACK + ";");
            int stack = -1;
            for (AbstractInsnNode in : read.instructions.toArray()) {
                if (Asm.isCall(in, ITEM_STACK, "<init>", "(III)V") && Asm.next(in).getOpcode() == ASTORE) {
                    stack = Asm.var(Asm.next(in));
                } else if (stack >= 0 && Asm.isField(in, PUTFIELD, ITEM_STACK, "field_77990_d")) {
                    InsnList ext = new InsnList();
                    ext.add(new VarInsnNode(ALOAD, stack));
                    ext.add(call(ITEM_IDS, "readNetExt", "(L" + ITEM_STACK + ";)V"));
                    read.instructions.insert(in, ext);
                    n++;
                    break;
                }
            }
            Asm.check(n == 3, "readItemStack tag");
            return n;
        }
    }

    /** The held item of a player who comes into view: 0 above 31999, Packet5 sends the real one next. */
    static final class HeldItemPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            MethodNode write = Asm.require(cn, "func_73273_a", "(Ljava/io/DataOutput;)V");
            for (AbstractInsnNode in : write.instructions.toArray()) {
                if (Asm.isField(in, GETFIELD, cn.name, "field_73518_h")
                        && Asm.isCall(Asm.next(in), "java/io/DataOutput", "writeShort", "(I)V")) {
                    write.instructions.insert(in, call(ITEM_IDS, "netId", "(I)I"));
                    return 1;
                }
            }
            return 0;
        }
    }

    /** level.dat: NGItemIdFormat and NGBlockIdFormat, read on load and written on save. */
    static final class WorldInfoPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = beforeReturns(Asm.require(cn, "<init>", "(L" + NBT + ";)V"), 1, "read");
            n += beforeReturns(Asm.require(cn, "func_76064_a", "(L" + NBT + ";L" + NBT + ";)V"), 1, "write");
            return n;
        }

        private static int beforeReturns(MethodNode m, int tagVar, String method) {
            int n = 0;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() == RETURN) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, tagVar));
                    hook.add(call(WORLD_FORMATS, method, "(L" + NBT + ";)V"));
                    m.instructions.insertBefore(in, hook);
                    n++;
                }
            }
            Asm.check(n > 0, m.name + " return");
            return n;
        }
    }

    // Helpers

    /** Replaces {@code new X[size]} with {@code new X[Registries.<capacity>()]}. */
    static int sizeArrays(MethodNode m, int size, String capacity) {
        int n = 0;
        for (AbstractInsnNode in : m.instructions.toArray()) {
            AbstractInsnNode next = Asm.next(in);
            if (Asm.isInt(in, size) && next != null && (next.getOpcode() == NEWARRAY || next.getOpcode() == ANEWARRAY)) {
                m.instructions.set(in, call(REGISTRIES, capacity, "()I"));
                n++;
            }
        }
        return n;
    }

    /** Replaces {@code Owner.field.length} with the given value. */
    static int replaceLength(MethodNode m, String owner, String field, AbstractInsnNode value) {
        int n = 0;
        for (AbstractInsnNode in : m.instructions.toArray()) {
            AbstractInsnNode next = Asm.next(in);
            if (Asm.isField(in, GETSTATIC, owner, field) && next != null && next.getOpcode() == ARRAYLENGTH) {
                m.instructions.remove(next);
                m.instructions.set(in, n == 0 ? value : value.clone(null));
                n++;
            }
        }
        return n;
    }

    /** The fields become non final, and the class hands itself to Registries.track at the end of its static init. */
    static int track(ClassNode cn, String... fields) {
        List<String> names = new ArrayList<String>(Arrays.asList(fields));
        for (FieldNode field : cn.fields) {
            if (names.remove(field.name)) {
                Asm.check((field.access & ACC_STATIC) != 0 && field.desc.startsWith("["), "static array " + field.name);
                field.access &= ~ACC_FINAL;
            }
        }
        Asm.check(names.isEmpty(), "fields " + names);
        Asm.check(cn.version >= V1_5, "class constants (Java 5+)");
        MethodNode clinit = Asm.require(cn, "<clinit>", "()V");
        int n = 0;
        for (AbstractInsnNode in : clinit.instructions.toArray()) {
            if (in.getOpcode() == RETURN) {
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(Type.getObjectType(cn.name)));
                hook.add(call(REGISTRIES, "track", "(Ljava/lang/Class;)V"));
                clinit.instructions.insertBefore(in, hook);
                n++;
            }
        }
        Asm.check(n > 0, "static init return");
        return 1;
    }

    /** Constructors taking the ID first call Registries.<method>(id + offset) before anything else. */
    static int hookConstructors(ClassNode cn, String method, int offset) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>") || !m.desc.startsWith("(I")) {
                continue;
            }
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(ILOAD, 1));
            if (offset != 0) {
                hook.add(Asm.pushInt(offset));
                hook.add(new InsnNode(IADD));
            }
            hook.add(call(REGISTRIES, method, "(I)V"));
            m.instructions.insert(hook);
            n++;
        }
        Asm.check(n > 0, "constructor taking an ID");
        return n;
    }

    static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(INVOKESTATIC, owner, name, desc, false);
    }

    private IdPatches() {
    }
}
