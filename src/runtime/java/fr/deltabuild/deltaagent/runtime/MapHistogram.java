package fr.deltabuild.deltaagent.runtime;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;

/**
 * ItemMap counts, for each map pixel, the blocks under it in an array indexed by block ID, then
 * draws the color of the most frequent one. The array keeps 4096 slots; a block above 4095 is
 * counted in the slot of a vanilla block of the same map color.
 */
public final class MapHistogram {

    public static final int SIZE = 4096;

    private static volatile int[] byColor;

    public static int size() {
        return SIZE;
    }

    /** The slot counting this block ID. */
    public static int slot(int id) {
        if (id >= 0 && id < SIZE) {
            return id;
        }
        Block[] blocks = Block.field_71973_m;
        Block block = id > 0 && id < blocks.length ? blocks[id] : null;
        if (block == null || block.field_72018_cp == null || block.field_72018_cp.field_76234_F == null) {
            return 0;
        }
        int[] table = byColor;
        if (table == null) {
            table = colorTable(blocks);
            byColor = table;
        }
        int color = block.field_72018_cp.field_76234_F.field_76290_q;
        return color >= 0 && color < table.length ? table[color] : 0;
    }

    /** For each map color, the first block below 4096 that shows it. */
    private static int[] colorTable(Block[] blocks) {
        int[] table = new int[MapColor.field_76281_a.length];
        for (int id = 1; id < SIZE && id < blocks.length; id++) {
            Block block = blocks[id];
            if (block == null || block.field_72018_cp == null || block.field_72018_cp.field_76234_F == null) {
                continue;
            }
            int color = block.field_72018_cp.field_76234_F.field_76290_q;
            if (color >= 0 && color < table.length && table[color] == 0) {
                table[color] = id;
            }
        }
        return table;
    }

    private MapHistogram() {
    }
}
