package fr.deltabuild.deltaagent.runtime;

import net.minecraft.world.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_6_R3.CraftWorld;

/**
 * Block metadata beyond the byte of the Bukkit API, for the patched WorldEdit. Values that fit
 * in a byte keep going through Bukkit; larger ones go to the world directly, with the same update
 * flags as CraftBlock.
 */
public final class PluginBlocks {

    /** Block.setTypeIdAndData(type, (byte) data, physics) without the byte. */
    public static boolean setTypeIdAndData(Block block, int type, int data, boolean physics) {
        if (data >= 0 && data <= 0xFF) {
            return block.setTypeIdAndData(type, (byte) data, physics);
        }
        World world = handle(block);
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (physics) {
            return world.func_72832_d(x, y, z, type, data, 3);
        }
        boolean changed = world.func_72832_d(x, y, z, type, data, 2);
        if (changed) {
            world.func_72845_h(x, y, z);
        }
        return changed;
    }

    /** Block.setData((byte) data) without the byte. */
    public static void setData(Block block, int data) {
        setData(block, data, true);
    }

    /** Block.setData((byte) data, physics) without the byte. */
    public static void setData(Block block, int data, boolean physics) {
        if (data >= 0 && data <= 0xFF) {
            block.setData((byte) data, physics);
            return;
        }
        handle(block).func_72921_c(block.getX(), block.getY(), block.getZ(), data, physics ? 3 : 2);
    }

    /** Block.getData() on 16 bits instead of a signed byte. */
    public static int getData(Block block) {
        return handle(block).func_72805_g(block.getX(), block.getY(), block.getZ());
    }

    private static World handle(Block block) {
        return ((CraftWorld) block.getWorld()).getHandle();
    }

    private PluginBlocks() {
    }
}
