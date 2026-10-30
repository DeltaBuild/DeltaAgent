package fr.deltabuild.deltaagent.runtime;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;

/**
 * Biome IDs on 16 bits, as NGClient keeps them: the chunk's Biomes array holds bits 0-7, a second
 * array of 256 bytes (Biomes16) holds bits 8-15 once a biome above 255 needs it. A column still to
 * compute reads 255 without that array, 65535 with it.
 *
 * <p>Called by the patched Chunk, AnvilChunkLoader, ChunkProviderServer and CraftWorld.
 */
public final class ChunkBiomes {

    public static final String BIOMES16 = "Biomes16";
    public static final int FORMAT_BIOME16 = 5;
    private static final int COLUMNS = 256;

    /** null until the first chunk is generated: whether a biome above 255 is registered. */
    private static volatile Boolean wide;

    /** Chunk.getBiomeGenForWorldCoords. */
    public static BiomeGenBase biome(Chunk chunk, int x, int z, WorldChunkManager manager) {
        int index = z << 4 | x;
        byte[] low = chunk.func_76605_m();
        byte[] high = chunk.deltaBiomeHigh;
        int id = low[index] & 0xFF;
        if (high != null) {
            id |= (high[index] & 0xFF) << 8;
        }
        if (id == (high == null ? 0xFF : 0xFFFF)) {
            id = manager.func_76935_a((chunk.field_76635_g << 4) + x, (chunk.field_76647_h << 4) + z).field_76756_M;
            set(chunk, index, id);
        }
        BiomeGenBase[] list = BiomeGenBase.field_76773_a;
        return id < list.length && list[id] != null ? list[id] : BiomeGenBase.field_76772_c;
    }

    /** Biome ID of a column, as saved. */
    public static int id(Chunk chunk, int x, int z) {
        int index = z << 4 | x;
        byte[] high = chunk.deltaBiomeHigh;
        return chunk.func_76605_m()[index] & 0xFF | (high == null ? 0 : (high[index] & 0xFF) << 8);
    }

    /** Sets the biome of a column (index z << 4 | x), creating Biomes16 when it is above 255. */
    public static void set(Chunk chunk, int index, int id) {
        byte[] low = chunk.func_76605_m();
        low[index] = (byte) id;
        byte[] high = chunk.deltaBiomeHigh;
        if ((id & 0xFF00) != 0) {
            if (high == null) {
                high = new byte[COLUMNS];
                for (int i = 0; i < COLUMNS; i++) {
                    if (i != index && low[i] == (byte) 0xFF) {
                        high[i] = (byte) 0xFF; // still to compute
                    }
                }
                chunk.deltaBiomeHigh = high;
            }
            high[index] = (byte) (id >> 8);
        } else if (high != null) {
            high[index] = 0;
        }
    }

    /** CraftWorld.setBiome, after it wrote the low byte. */
    public static void setBukkit(Chunk chunk, int x, int z, BiomeGenBase biome) {
        if (chunk != null && biome != null) {
            set(chunk, (z & 15) << 4 | x & 15, biome.field_76756_M);
        }
    }

    /**
     * A chunk just made by a generator, which wrote only the low byte of each biome: the bits 8-15
     * come from the world's biome source wherever its biome has the same low byte.
     */
    public static void generated(Chunk chunk) {
        if (chunk == null || !wideBiomes()) {
            return;
        }
        World world = chunk.field_76637_e;
        BiomeGenBase[] biomes = world.func_72959_q().func_76933_b(null, chunk.field_76635_g * 16, chunk.field_76647_h * 16, 16, 16);
        byte[] low = chunk.func_76605_m();
        for (int i = 0; i < COLUMNS && i < biomes.length; i++) {
            BiomeGenBase biome = biomes[i];
            if (biome != null && biome.field_76756_M > 0xFF && low[i] == (byte) biome.field_76756_M) {
                set(chunk, i, biome.field_76756_M);
            }
        }
    }

    private static boolean wideBiomes() {
        Boolean known = wide;
        if (known == null) {
            BiomeGenBase[] list = BiomeGenBase.field_76773_a;
            boolean found = false;
            for (int i = 256; i < list.length && !found; i++) {
                found = list[i] != null;
            }
            wide = known = Boolean.valueOf(found);
        }
        return known.booleanValue();
    }

    // Save

    /** AnvilChunkLoader, after the Biomes array is read. */
    public static void read(Chunk chunk, NBTTagCompound level) {
        NBTBase tag = level.func_74781_a(BIOMES16);
        if (!(tag instanceof NBTTagByteArray)) {
            chunk.deltaBiomeHigh = null;
            return;
        }
        byte[] high = ((NBTTagByteArray) tag).field_74754_a;
        if (high.length == COLUMNS) {
            chunk.deltaBiomeHigh = useful(high);
            SectionIds.arm(FORMAT_BIOME16);
        } else {
            chunk.deltaBiomeHigh = null;
            SectionIds.log("Biomes16 of " + high.length + " bytes ignored in chunk " + chunk.field_76635_g + "," + chunk.field_76647_h + ".");
        }
    }

    /** AnvilChunkLoader, after the Biomes array is written. */
    public static void write(NBTTagCompound level, Chunk chunk) {
        byte[] high = useful(chunk.deltaBiomeHigh);
        if (high != null) {
            level.func_74773_a(BIOMES16, high);
            SectionIds.arm(FORMAT_BIOME16);
        }
    }

    /** Biomes16 when it says something: an array of only 0 (biomes below 256) or only 255 (to compute) is no array. */
    public static byte[] useful(byte[] high) {
        if (high == null || high.length == 0) {
            return null;
        }
        byte first = high[0];
        if (first != 0 && first != (byte) 0xFF) {
            return high;
        }
        for (int i = 1; i < high.length; i++) {
            if (high[i] != first) {
                return high;
            }
        }
        return null;
    }

    private ChunkBiomes() {
    }
}
