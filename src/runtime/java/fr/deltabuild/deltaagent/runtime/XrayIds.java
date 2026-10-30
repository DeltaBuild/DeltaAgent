package fr.deltabuild.deltaagent.runtime;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Spigot's anti-xray reads only the low byte of each block ID in the chunk data it sends. A block
 * above 255 whose low byte matches a hidden ore is left alone, or the client would see another block.
 */
public final class XrayIds {

    /** AntiXray.obfuscate: whether this position may be hidden, given the low byte matched. */
    public static boolean narrow(boolean matched, World world, int chunkX, int chunkZ, int section, int x, int y, int z) {
        if (!matched || !world.func_72863_F().func_73149_a(chunkX, chunkZ)) {
            return matched;
        }
        Chunk chunk = world.func_72964_e(chunkX, chunkZ);
        ExtendedBlockStorage[] sections = chunk.func_76587_i();
        ExtendedBlockStorage storage = section >= 0 && section < sections.length ? sections[section] : null;
        return storage == null || !storage.deltaIsWide(x, y, z);
    }

    private XrayIds() {
    }
}
