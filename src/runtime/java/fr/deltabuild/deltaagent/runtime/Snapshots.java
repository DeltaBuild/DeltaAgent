package fr.deltabuild.deltaagent.runtime;

import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Bukkit chunk snapshots keep a short per block: bits 0-15 of the ID. CraftChunk fills bits 0-11;
 * bits 12-15 are added here, and bits 16-23 go to a plane attached to the snapshot.
 */
public final class Snapshots {

    /** CraftChunk.getChunkSnapshot: Add2 into the short IDs of every section. */
    public static void widen(short[][] ids, ExtendedBlockStorage[] sections) {
        for (int i = 0; i < sections.length && i < ids.length; i++) {
            ExtendedBlockStorage section = sections[i];
            NibbleArray add2 = section == null ? null : section.getBlockA2Array();
            if (add2 == null || ids[i] == null || ids[i].length != 4096) {
                continue;
            }
            byte[] bytes = add2.getValueArray();
            short[] section16 = ids[i];
            for (int half = 0; half < 2048; half++) {
                int value = bytes[half] & 0xFF;
                if (value == 0) {
                    continue;
                }
                section16[half << 1] = (short) (section16[half << 1] | (value & 0xF) << 12);
                section16[(half << 1) + 1] = (short) (section16[(half << 1) + 1] | (value >> 4) << 12);
            }
        }
    }

    /** CraftChunk.getChunkSnapshot: copies of the BlocksB3 planes, null when none is used. */
    public static byte[][] b3(ExtendedBlockStorage[] sections) {
        byte[][] planes = null;
        for (int i = 0; i < sections.length; i++) {
            byte[] b3 = sections[i] == null ? null : sections[i].getBlockB3Array();
            if (b3 != null) {
                if (planes == null) {
                    planes = new byte[sections.length][];
                }
                planes[i] = b3.clone();
            }
        }
        return planes;
    }

    /** CraftChunkSnapshot.getBlockTypeId: the short read as unsigned, plus bits 16-23. */
    public static int typeId(int value, byte[][] b3, int x, int y, int z) {
        int id = value & 0xFFFF;
        if (b3 != null) {
            int section = b3.length > 16 ? (y >> 4) + 4 : y >> 4;
            byte[] plane = section >= 0 && section < b3.length ? b3[section] : null;
            if (plane != null) {
                id |= (plane[(y & 15) << 8 | z << 4 | x] & 0xFF) << 16;
            }
        }
        return id;
    }

    private Snapshots() {
    }
}
