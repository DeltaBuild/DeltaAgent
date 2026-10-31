package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Asm;
import fr.deltabuild.deltaagent.core.ClassPatch;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
 * Wide IDs for the editing plugins. WorldEdit 5.6 (and AsyncWorldEdit, which goes through its
 * edit sessions) gets block IDs up to 16777215 and metadata up to 65535 in its blocks, commands,
 * world access and schematics. VoxelSniper already keeps IDs in an int and goes through the
 * Bukkit API: it only needs the byte of that API read as unsigned, which CraftBlock now does.
 *
 * <p>Plugin classes come from Bukkit's plugin class loaders, which the LaunchClassLoader
 * transformers never see: they are patched by {@link IdsJvmTransformer}.
 */
final class PluginIdPatches implements Opcodes {

    static final String BLOCKS = "fr/deltabuild/deltaagent/runtime/PluginBlocks";
    static final String SCHEMATICS = "fr/deltabuild/deltaagent/runtime/PluginSchematics";
    static final String BUKKIT_BLOCK = "org/bukkit/block/Block";
    static final String WE_BLOCK = "com/sk89q/worldedit/foundation/Block";
    static final String WE_BASE_BLOCK = "com/sk89q/worldedit/blocks/BaseBlock";

    private static final Map<String, ClassPatch> TARGETS = new HashMap<String, ClassPatch>();

    static {
        TARGETS.put(WE_BLOCK, new WeBlockPatch());
        TARGETS.put(WE_BASE_BLOCK, new WeBaseBlockPatch());
        TARGETS.put("com/sk89q/worldedit/WorldEdit", new WeInputPatch());
        TARGETS.put("com/sk89q/worldedit/bukkit/BukkitWorld", new WeBukkitWorldPatch());
        TARGETS.put("com/sk89q/worldedit/schematic/MCEditSchematicFormat", new WeSchematicPatch());
    }

    /** The game side: the byte of the Bukkit API read as unsigned, and the metadata CraftBlock keeps read whole. */
    static void register(PatchRegistry registry) {
        registry.add(PatchTarget.patch("ids", "org.bukkit.craftbukkit.v1_6_R3.block.CraftBlock", false,
                new UnsignedDataPatch(3, "setData", "setTypeIdAndData")));
        registry.add(PatchTarget.patch("ids", "org.bukkit.craftbukkit.v1_6_R3.CraftWorld", false,
                new UnsignedDataPatch(1, "spawnFallingBlock")));
    }

    static byte[] transform(String internalName, byte[] bytes) {
        ClassPatch patch = TARGETS.get(internalName);
        if (patch == null || bytes == null) {
            return null;
        }
        String name = internalName.replace('/', '.');
        try {
            ClassNode cn = Asm.read(bytes);
            int n = patch.apply(cn);
            if (n <= 0) {
                Log.warn("ids", "Plugin class " + name + " left unchanged: expected code not found (other version?).");
                return null;
            }
            Log.info("ids", "Patched plugin class " + name + " (" + n + " change" + (n > 1 ? "s" : "") + ").");
            return Asm.write(cn);
        } catch (Throwable t) {
            Log.warn("ids", "Plugin class " + name + " left unchanged: " + t);
            return null;
        }
    }

    /**
     * The metadata byte of the Bukkit API taken as 0-255 (CraftBlock.setData, setTypeIdAndData,
     * CraftWorld.spawnFallingBlock). Signed, 200 became -56 and was refused by the 16-bit section;
     * vanilla kept its low 4 bits. CraftBlock.setTypeId keeps the metadata through its own getData(),
     * a signed byte too: it reads the whole metadata instead.
     */
    static final class UnsignedDataPatch implements ClassPatch {
        private final int expected;
        private final List<String> methods;

        UnsignedDataPatch(int expected, String... methods) {
            this.expected = expected;
            this.methods = Arrays.asList(methods);
        }

        public int apply(ClassNode cn) {
            int n = 0;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (in.getOpcode() == INVOKEVIRTUAL && Asm.isCall(in, cn.name, "getData", "()B") && !m.name.equals("getData")) {
                        m.instructions.set(in, IdPatches.call(BLOCKS, "getData", "(L" + BUKKIT_BLOCK + ";)I"));
                        n++;
                    }
                }
                if (!methods.contains(m.name)) {
                    continue;
                }
                Type[] arguments = Type.getArgumentTypes(m.desc);
                int slot = (m.access & ACC_STATIC) != 0 ? 0 : 1;
                for (Type argument : arguments) {
                    if (argument.getSort() == Type.BYTE) {
                        for (AbstractInsnNode in : m.instructions.toArray()) {
                            if (in.getOpcode() == ILOAD && Asm.var(in) == slot) {
                                InsnList unsigned = new InsnList();
                                unsigned.add(new IntInsnNode(SIPUSH, 0xFF));
                                unsigned.add(new InsnNode(IAND));
                                m.instructions.insert(in, unsigned);
                                n++;
                            }
                        }
                    }
                    slot += argument.getSize();
                }
            }
            Asm.check(n >= expected, "byte data of " + methods);
            return n;
        }
    }

    // WorldEdit

    /** foundation.Block: ID and data kept in ints, up to 16777215 and 65535 instead of 4095 and 15. */
    static final class WeBlockPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            for (FieldNode field : cn.fields) {
                if ((field.name.equals("id") || field.name.equals("data")) && field.desc.equals("S")) {
                    field.desc = "I";
                    n++;
                } else if (field.name.equals("MAX_ID")) {
                    field.value = Integer.valueOf(IdCapacity.MAX_ID);
                    n++;
                } else if (field.name.equals("MAX_DATA")) {
                    field.value = Integer.valueOf(0xFFFF);
                    n++;
                }
            }
            Asm.check(n == 4, "id, data, MAX_ID and MAX_DATA fields");
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (in instanceof FieldInsnNode && ((FieldInsnNode) in).owner.equals(cn.name)
                            && (((FieldInsnNode) in).name.equals("id") || ((FieldInsnNode) in).name.equals("data"))) {
                        ((FieldInsnNode) in).desc = "I";
                        AbstractInsnNode cast = Asm.prev(in);
                        if (in.getOpcode() == PUTFIELD && cast != null && cast.getOpcode() == I2S) {
                            m.instructions.remove(cast);
                        }
                        n++;
                    } else if (m.name.equals("setId") && Asm.isInt(in, 4095)) {
                        m.instructions.set(in, new LdcInsnNode(Integer.valueOf(IdCapacity.MAX_ID)));
                        n++;
                    } else if (m.name.equals("setData") && Asm.isInt(in, 15)) {
                        m.instructions.set(in, new LdcInsnNode(Integer.valueOf(0xFFFF)));
                        n++;
                    } else if (in instanceof LdcInsnNode && ((LdcInsnNode) in).cst instanceof String) {
                        String text = (String) ((LdcInsnNode) in).cst;
                        if (text.contains("above 4095")) {
                            ((LdcInsnNode) in).cst = text.replace("above 4095", "above " + IdCapacity.MAX_ID);
                        } else if (text.contains("above 15")) {
                            ((LdcInsnNode) in).cst = text.replace("above 15", "above 65535");
                        }
                    }
                }
            }
            return n;
        }
    }

    /** BaseBlock.rotate90, flip: the data is no longer cast to a short before setData (negative above 32767). */
    static final class WeBaseBlockPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (in.getOpcode() == I2S && Asm.next(in) instanceof MethodInsnNode && ((MethodInsnNode) Asm.next(in)).name.equals("setData")) {
                        m.instructions.remove(in);
                        n++;
                    }
                }
            }
            return n;
        }
    }

    /** WorldEdit.getBlock: "id:data" accepts data up to 65535. */
    static final class WeInputPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            for (MethodNode m : cn.methods) {
                if (!m.name.equals("getBlock") || !m.desc.endsWith("ZZ)L" + WE_BASE_BLOCK + ";")) {
                    continue;
                }
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (Asm.isInt(in, 15) && Asm.next(in) != null && Asm.next(in).getOpcode() == IF_ICMPLE && mentions(in, "Invalid data value")) {
                        m.instructions.set(in, new LdcInsnNode(Integer.valueOf(0xFFFF)));
                        return 1;
                    }
                }
            }
            return 0;
        }

        /** Whether one of the next few instructions loads a string starting with that text. */
        private static boolean mentions(AbstractInsnNode from, String text) {
            AbstractInsnNode in = from;
            for (int i = 0; i < 12 && in != null; i++, in = in.getNext()) {
                if (in instanceof LdcInsnNode && ((LdcInsnNode) in).cst instanceof String && ((String) ((LdcInsnNode) in).cst).startsWith(text)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** BukkitWorld: metadata set and read through PluginBlocks, without the byte of the Bukkit API. */
    static final class WeBukkitWorldPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            int n = 0;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in : m.instructions.toArray()) {
                    if (!(in instanceof MethodInsnNode) || !((MethodInsnNode) in).owner.equals(BUKKIT_BLOCK)) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) in;
                    String desc = null;
                    if (call.name.equals("setTypeIdAndData") && call.desc.equals("(IBZ)Z")) {
                        desc = "(L" + BUKKIT_BLOCK + ";IIZ)Z";
                    } else if (call.name.equals("setData") && call.desc.equals("(B)V")) {
                        desc = "(L" + BUKKIT_BLOCK + ";I)V";
                    } else if (call.name.equals("setData") && call.desc.equals("(BZ)V")) {
                        desc = "(L" + BUKKIT_BLOCK + ";IZ)V";
                    } else if (call.name.equals("getData") && call.desc.equals("()B")) {
                        desc = "(L" + BUKKIT_BLOCK + ";)I";
                    }
                    if (desc == null) {
                        continue;
                    }
                    if (!call.desc.startsWith("()")) {
                        // (byte) data: the cast goes, the full int is passed
                        AbstractInsnNode cast = Asm.prev(call);
                        if (cast != null && cast.getOpcode() != I2B) {
                            cast = Asm.prev(cast);
                        }
                        Asm.check(cast != null && cast.getOpcode() == I2B, "(byte) data before Block." + call.name);
                        m.instructions.remove(cast);
                    }
                    m.instructions.set(call, IdPatches.call(BLOCKS, call.name, desc));
                    n++;
                }
            }
            Asm.check(n == 5, "5 Bukkit block data calls, found " + n);
            return n;
        }
    }

    /** MCEditSchematicFormat: AddBlocks2, BlocksB3 and Data2 read and written through PluginSchematics. */
    static final class WeSchematicPatch implements ClassPatch {
        public int apply(ClassNode cn) {
            return load(Asm.require(cn, "load", "(Ljava/io/File;)Lcom/sk89q/worldedit/CuboidClipboard;"))
                    + save(Asm.require(cn, "save", "(Lcom/sk89q/worldedit/CuboidClipboard;Ljava/io/File;)V"));
        }

        /** getBlockForId(blocks[index], blockData[index]) becomes PluginSchematics.block(this, id, data, index, schematic). */
        private static int load(MethodNode m) {
            int schematic = -1;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null && schematic < 0; in = in.getNext()) {
                if (in instanceof LdcInsnNode && "AddBlocks".equals(((LdcInsnNode) in).cst) && Asm.prev(in).getOpcode() == ALOAD) {
                    schematic = Asm.var(Asm.prev(in));
                }
            }
            Asm.check(schematic >= 0, "schematic compound in load");
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof MethodInsnNode && ((MethodInsnNode) in).name.equals("getBlockForId")) {
                    // Stack: this, blocks[index], blockData[index]; index is loaded right before BALOAD.
                    AbstractInsnNode load = Asm.prev(Asm.prev(in));
                    Asm.check(load != null && load.getOpcode() == ILOAD, "index of getBlockForId");
                    InsnList extra = new InsnList();
                    extra.add(new VarInsnNode(ILOAD, Asm.var(load)));
                    extra.add(new VarInsnNode(ALOAD, schematic));
                    m.instructions.insertBefore(in, extra);
                    m.instructions.insertBefore(in, IdPatches.call(SCHEMATICS, "block", "(Ljava/lang/Object;IIILjava/util/Map;)Ljava/lang/Object;"));
                    m.instructions.set(in, new TypeInsnNode(CHECKCAST, WE_BASE_BLOCK));
                    return 1;
                }
            }
            throw new IllegalStateException("pattern not found: getBlockForId in load");
        }

        /** Before new CompoundTag("Schematic", schematic): PluginSchematics.save(schematic, clipboard). */
        private static int save(MethodNode m) {
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof LdcInsnNode && "Schematic".equals(((LdcInsnNode) in).cst)) {
                    AbstractInsnNode map = Asm.next(in);
                    AbstractInsnNode created = Asm.prev(Asm.prev(in));
                    Asm.check(map != null && map.getOpcode() == ALOAD && created != null && created.getOpcode() == NEW, "new CompoundTag(\"Schematic\", schematic)");
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, Asm.var(map)));
                    hook.add(new VarInsnNode(ALOAD, 1));
                    hook.add(IdPatches.call(SCHEMATICS, "save", "(Ljava/util/Map;Ljava/lang/Object;)V"));
                    m.instructions.insertBefore(created, hook);
                    return 1;
                }
            }
            throw new IllegalStateException("pattern not found: \"Schematic\" compound in save");
        }
    }

    private PluginIdPatches() {
    }
}
