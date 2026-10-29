package fr.deltabuild.deltaagent.ids.legacy;

import fr.deltabuild.deltaagent.core.Log;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Raises the block and item ID limits of a stock MCPC+ 1.6.4 server (see {@link IdCapacity}).
 * Ported from mcpc-java17 unchanged; it runs at JVM level, on the final bytes of each class.
 *
 * <p>Classes are matched after FML's deobfuscation (MCP class names, SRG member names), with the
 * obfuscated names as a fallback.
 */
final class IdPatches {

    private static final String CAPACITY = "fr/deltabuild/deltaagent/ids/legacy/IdCapacity";

    private static final String BLOCK = "net/minecraft/block/Block";
    private static final String ITEM = "net/minecraft/item/Item";
    private static final String CHUNK = "net/minecraft/world/chunk/Chunk";
    private static final String SECTION = "net/minecraft/world/chunk/storage/ExtendedBlockStorage";
    private static final String BUKKIT_MATERIAL = "org/bukkit/Material";
    private static final String FORGE_CONFIGURATION = "net/minecraftforge/common/Configuration";

    /** Returns the patched class, or null when the class is not a target or is left untouched. */
    static byte[] patch(String className, byte[] bytes) {
        String target = target(className);
        if (target == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(node, ClassReader.SKIP_FRAMES);
        if (node.version > Opcodes.V1_6) {
            // Frames would have to be recomputed; Minecraft 1.6.4 classes are Java 6.
            Log.info("ids", className + " is not Java 6 bytecode, ID limits left unchanged.");
            return null;
        }
        int changes;
        switch (target) {
            case BLOCK:
                changes = resizeArrays(node, 4096, "blocks") + checkConstructorId(node, "checkBlock", 0);
                break;
            case ITEM:
                changes = resizeArrays(node, 32000, "items") + checkConstructorId(node, "checkItem", 256);
                break;
            case BUKKIT_MATERIAL:
                changes = resizeArrays(node, 32000, "items");
                break;
            case FORGE_CONFIGURATION:
                changes = keepVanillaRanges(node);
                break;
            case CHUNK:
                changes = refuseUnstorableBlock(node, new String[] {"func_76592_a", "a"}, "(IIIII)Z");
                break;
            default:
                changes = replaceUnstorableBlock(node, new String[] {"func_76655_a", "a"}, "(IIII)V");
                break;
        }
        if (changes == 0) {
            Log.info("ids", className + ": nothing to patch for the ID limits.");
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Log.info("ids", "ID limits: patched " + className + " (" + changes + ")");
        return writer.toByteArray();
    }

    private static String target(String className) {
        switch (className) {
            case BLOCK:
            case "aqz":
                return BLOCK;
            case ITEM:
            case "yc":
                return ITEM;
            case CHUNK:
            case "adr":
                return CHUNK;
            case SECTION:
            case "ads":
                return SECTION;
            case BUKKIT_MATERIAL:
                return BUKKIT_MATERIAL;
            case FORGE_CONFIGURATION:
                return FORGE_CONFIGURATION;
            default:
                return null;
        }
    }

    /** In the static initializer, sizes the arrays created with {@code new X[size]} from IdCapacity. */
    private static int resizeArrays(ClassNode node, int size, String capacityMethod) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.SIPUSH && ((IntInsnNode) insn).operand == size) {
                    AbstractInsnNode next = insn.getNext();
                    if (next != null && (next.getOpcode() == Opcodes.NEWARRAY || next.getOpcode() == Opcodes.ANEWARRAY)) {
                        method.instructions.set(insn,
                                new MethodInsnNode(Opcodes.INVOKESTATIC, CAPACITY, capacityMethod, "()I", false));
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Forge's Configuration uses {@code Block.blocksList.length} as the end of the block range and the
     * start of the item range, and {@code Item.itemsList.length} as the end of the item range. It keeps
     * the vanilla ranges (4096, 32000), so the IDs it assigns do not change and stay storable.
     */
    private static int keepVanillaRanges(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                continue; // configMarkers is sized from the raised capacity
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.GETSTATIC || insn.getNext() == null
                        || insn.getNext().getOpcode() != Opcodes.ARRAYLENGTH) {
                    continue;
                }
                String desc = ((org.objectweb.asm.tree.FieldInsnNode) insn).desc;
                int vanilla;
                if (desc.equals("[L" + BLOCK + ";") || desc.equals("[Laqz;")) {
                    vanilla = 4096;
                } else if (desc.equals("[L" + ITEM + ";") || desc.equals("[Lyc;")) {
                    vanilla = 32000;
                } else {
                    continue;
                }
                method.instructions.remove(insn.getNext());
                method.instructions.set(insn, new IntInsnNode(Opcodes.SIPUSH, vanilla));
                count++;
            }
        }
        return count;
    }

    /** At the start of the constructors taking the ID as first argument, checks it against the capacity. */
    private static int checkConstructorId(ClassNode node, String check, int offset) {
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") || !method.desc.startsWith("(I")) {
                continue;
            }
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ILOAD, 1));
            if (offset != 0) {
                call.add(new IntInsnNode(Opcodes.SIPUSH, offset));
                call.add(new InsnNode(Opcodes.IADD));
            }
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CAPACITY, check, "(I)V", false));
            method.instructions.insert(call);
            count++;
        }
        return count;
    }

    /** Chunk.setBlockIDWithMetadata: returns false (nothing changed) for blocks a chunk cannot store. */
    private static int refuseUnstorableBlock(ClassNode node, String[] names, String desc) {
        MethodNode method = find(node, names, desc);
        if (method == null) {
            return 0;
        }
        LabelNode storable = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ILOAD, 4));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CAPACITY, "storable", "(I)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, storable));
        guard.add(new InsnNode(Opcodes.ICONST_0));
        guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(storable);
        method.instructions.insert(guard);
        return 1;
    }

    /** ExtendedBlockStorage.setExtBlockID: writes air instead of a block the section cannot store. */
    private static int replaceUnstorableBlock(ClassNode node, String[] names, String desc) {
        MethodNode method = find(node, names, desc);
        if (method == null) {
            return 0;
        }
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ILOAD, 4));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CAPACITY, "storedId", "(I)I", false));
        guard.add(new VarInsnNode(Opcodes.ISTORE, 4));
        method.instructions.insert(guard);
        return 1;
    }

    private static MethodNode find(ClassNode node, String[] names, String desc) {
        for (String name : names) {
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    return method;
                }
            }
        }
        return null;
    }

    private IdPatches() {
    }
}
