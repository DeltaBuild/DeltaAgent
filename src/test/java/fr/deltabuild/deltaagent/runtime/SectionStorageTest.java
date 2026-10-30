package fr.deltabuild.deltaagent.runtime;

import static fr.deltabuild.deltaagent.Reflect.call;
import static fr.deltabuild.deltaagent.Reflect.create;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.McpcClasses;
import fr.deltabuild.deltaagent.core.GameTransformer;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.height.HeightModule;
import fr.deltabuild.deltaagent.ids.IdsModule;
import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The 24-bit section of the agent with the real NibbleArray and NBT classes of MCPC+: the four ID
 * planes, their save tags, palette sections, the NG blocks of Packet56 and the Bukkit snapshots.
 */
@EnabledIf("fr.deltabuild.deltaagent.McpcClasses#present")
class SectionStorageTest {

    private static final String EBS = "net.minecraft.world.chunk.storage.ExtendedBlockStorage";
    /** IDs on every plane boundary, up to the 24-bit maximum. */
    private static final int[] IDS = {0, 1, 255, 256, 4095, 4096, 40000, 65535, 65536, 70000, 80021, 131071, 0xFFFFFF};
    /** Metadata on every plane boundary, up to 16 bits. */
    private static final int[] METAS = {0, 1, 15, 16, 255, 256, 4095, 40000, 65535};

    private Class<?> section;
    private Class<?> nibble;
    private Class<?> nbt;
    private Class<?> list;
    private Class<?> sectionIds;
    private Class<?> chunkPackets;
    private Class<?> snapshots;
    private Class<?> chunkClass;
    private Class<?> chunkBiomes;

    @BeforeEach
    void setUp() throws Exception {
        // The chunk as the server gets it: height replacement, then the ids patches (Biomes16).
        PatchRegistry registry = new PatchRegistry();
        HeightModule.registerTargets(registry);
        IdsModule.start(registry);
        String chunkName = "net.minecraft.world.chunk.Chunk";
        Map<String, byte[]> replaced = new HashMap<String, byte[]>();
        replaced.put(EBS, McpcClasses.replacement("ids/ExtendedBlockStorage"));
        replaced.put(chunkName, new GameTransformer(registry).transform(chunkName, chunkName, McpcClasses.read(chunkName.replace('.', '/'))));
        ClassLoader loader = McpcClasses.launchLike(replaced);
        section = loader.loadClass(EBS);
        nibble = loader.loadClass("net.minecraft.world.chunk.NibbleArray");
        nbt = loader.loadClass("net.minecraft.nbt.NBTTagCompound");
        list = loader.loadClass("net.minecraft.nbt.NBTTagList");
        sectionIds = loader.loadClass("fr.deltabuild.deltaagent.runtime.SectionIds");
        chunkPackets = loader.loadClass("fr.deltabuild.deltaagent.runtime.ChunkPackets");
        snapshots = loader.loadClass("fr.deltabuild.deltaagent.runtime.Snapshots");
        chunkClass = loader.loadClass(chunkName);
        chunkBiomes = loader.loadClass("fr.deltabuild.deltaagent.runtime.ChunkBiomes");
    }

    @Test
    void idsUseFourPlanes() throws Exception {
        Object storage = filled();
        for (int i = 0; i < IDS.length; i++) {
            assertEquals(IDS[i], id(storage, i), "ID " + IDS[i]);
        }
        assertTrue((Boolean) call(section, "deltaHasWidePlanes", storage));
        assertFalse((Boolean) call(section, "deltaIsWide", storage, x(1), y(1), z(1)));
        assertTrue((Boolean) call(section, "deltaIsWide", storage, x(3), y(3), z(3)));
        assertFalse((Boolean) call(section, "deltaHasWidePlanes", newSection()));
    }

    @Test
    void savedPlanesComeBack() throws Exception {
        Object storage = filled();
        Object tag = save(storage);
        assertEquals(2048, ((byte[]) call(nbt, "func_74770_j", tag, "Add2")).length);
        assertEquals(4096, ((byte[]) call(nbt, "func_74770_j", tag, "BlocksB3")).length);
        Object loaded = load(tag);
        for (int i = 0; i < IDS.length; i++) {
            assertEquals(IDS[i], id(loaded, i), "ID " + IDS[i]);
        }
        assertEquals(3, call(sectionIds, "format"));
    }

    @Test
    void emptyPlanesAreNotSaved() throws Exception {
        Object storage = newSection();
        call(section, "createBlockA2Array", storage);
        call(section, "createBlockB3Array", storage);
        setId(storage, 0, 0, 0, 4095);
        Object tag = save(storage);
        assertFalse((Boolean) call(nbt, "func_74764_b", tag, "Add2"));
        assertFalse((Boolean) call(nbt, "func_74764_b", tag, "BlocksB3"));
        assertEquals(0, call(sectionIds, "format"));
        Object loaded = load(tag);
        assertNull(call(section, "getBlockA2Array", loaded));
        assertNull(call(section, "getBlockB3Array", loaded));
        assertEquals(4095, call(section, "func_76656_a", loaded, 0, 0, 0));
    }

    @Test
    void wrongPlaneSizesAreIgnored() throws Exception {
        Object tag = vanillaTag(newSection());
        call(nbt, "func_74773_a", tag, "Add2", new byte[100]);
        call(nbt, "func_74773_a", tag, "BlocksB3", new byte[2048]);
        Object loaded = load(tag);
        assertNull(call(section, "getBlockA2Array", loaded));
        assertNull(call(section, "getBlockB3Array", loaded));
    }

    @Test
    void paletteSectionsAreDecoded() throws Exception {
        int[] four = {0, 5000, 70000, 0xFFFFFF};
        byte[] data = new byte[2048];
        for (int p = 0; p < 4096; p++) {
            data[p >> 1] |= (byte) ((p & 3) << ((p & 1) * 4));
        }
        Object loaded = load(paletteTag(four, 4, data));
        for (int p = 0; p < 4096; p++) {
            assertEquals(four[p & 3], call(section, "func_76656_a", loaded, p & 15, p >> 8, p >> 4 & 15), "position " + p);
        }
        assertEquals(2, call(sectionIds, "format"));

        int[] many = new int[300];
        for (int i = 0; i < many.length; i++) {
            many[i] = i * 50000 % 0x1000000;
        }
        byte[] wide = new byte[8192];
        for (int p = 0; p < 4096; p++) {
            int index = p % many.length;
            wide[p * 2] = (byte) (index >> 8);
            wide[p * 2 + 1] = (byte) index;
        }
        loaded = load(paletteTag(many, 16, wide));
        for (int p = 0; p < 4096; p++) {
            assertEquals(many[p % many.length], call(section, "func_76656_a", loaded, p & 15, p >> 8, p >> 4 & 15), "position " + p);
        }
    }

    @Test
    void unreadablePalettesBecomeAir() throws Exception {
        Object loaded = load(paletteTag(new int[] {0, 5000}, 3, new byte[1536]));
        for (int p = 0; p < 4096; p++) {
            assertEquals(0, call(section, "func_76656_a", loaded, p & 15, p >> 8, p >> 4 & 15));
        }
        assertNull(call(section, "getBlockA2Array", loaded));
        assertNull(call(section, "getBlockB3Array", loaded));
    }

    @Test
    void metadataUsesThreePlanes() throws Exception {
        Object storage = newSection();
        for (int i = 0; i < METAS.length; i++) {
            call(section, "func_76654_b", storage, x(i), y(i), z(i), METAS[i]);
        }
        for (int i = 0; i < METAS.length; i++) {
            assertEquals(METAS[i], call(section, "func_76665_b", storage, x(i), y(i), z(i)), "meta " + METAS[i]);
        }
        assertTrue((Boolean) call(section, "deltaHasWideMeta", storage));
        call(section, "func_76654_b", storage, x(8), y(8), z(8), 3); // 65535 becomes 3: upper planes cleared there
        assertEquals(3, call(section, "func_76665_b", storage, x(8), y(8), z(8)));
        assertFalse((Boolean) call(section, "deltaHasWideMeta", newSection()));
        for (int bad : new int[] {65536, -1}) {
            Exception e = assertThrows(IllegalStateException.class, () -> call(section, "func_76654_b", storage, 1, 2, 3, bad));
            assertTrue(e.getMessage().contains("outside 0-65535"), e.getMessage());
        }
    }

    @Test
    void savedMetadataComesBack() throws Exception {
        Object storage = newSection();
        for (int i = 0; i < METAS.length; i++) {
            call(section, "func_76654_b", storage, x(i), y(i), z(i), METAS[i]);
        }
        Object tag = save(storage);
        assertEquals(2048, ((byte[]) call(nbt, "func_74770_j", tag, "DataAdd")).length);
        assertEquals(4096, ((byte[]) call(nbt, "func_74770_j", tag, "Data2")).length);
        Object loaded = load(tag);
        for (int i = 0; i < METAS.length; i++) {
            assertEquals(METAS[i], call(section, "func_76665_b", loaded, x(i), y(i), z(i)), "meta " + METAS[i]);
        }
        assertEquals(4, call(sectionIds, "format"));

        Object small = newSection();
        call(section, "func_76654_b", small, 0, 0, 0, 200); // then back to a vanilla value
        call(section, "func_76654_b", small, 0, 0, 0, 7);
        Object plain = save(small);
        assertFalse((Boolean) call(nbt, "func_74764_b", plain, "DataAdd"));
        assertFalse((Boolean) call(nbt, "func_74764_b", plain, "Data2"));
    }

    @Test
    void biomesUseSixteenBits() throws Exception {
        Object chunk = chunk(1, 2);
        byte[] low = (byte[]) chunkClass.getField("field_76651_r").get(chunk);
        Arrays.fill(low, (byte) 0xFF); // every column still to compute
        call(chunkBiomes, "set", chunk, 5, 300);
        call(chunkBiomes, "set", chunk, 7, 12);
        call(chunkBiomes, "set", chunk, 8, 65534);
        assertEquals(300, call(chunkBiomes, "id", chunk, 5, 0));
        assertEquals(12, call(chunkBiomes, "id", chunk, 7, 0));
        assertEquals(65534, call(chunkBiomes, "id", chunk, 8, 0));
        assertEquals(65535, call(chunkBiomes, "id", chunk, 6, 0), "a column to compute stays to compute");

        Object level = create(nbt);
        call(chunkBiomes, "write", level, chunk);
        assertEquals(256, ((byte[]) call(nbt, "func_74770_j", level, "Biomes16")).length);
        Object loaded = chunk(1, 2);
        chunkClass.getField("field_76651_r").set(loaded, low.clone());
        call(chunkBiomes, "read", loaded, level);
        assertEquals(300, call(chunkBiomes, "id", loaded, 5, 0));
        assertEquals(5, call(sectionIds, "format"));

        Object plain = chunk(0, 0);
        call(chunkBiomes, "set", plain, 3, 300);
        call(chunkBiomes, "set", plain, 3, 4); // back below 256: Biomes16 holds only zeros
        Object none = create(nbt);
        call(chunkBiomes, "write", none, plain);
        assertFalse((Boolean) call(nbt, "func_74764_b", none, "Biomes16"));
        call(chunkBiomes, "read", loaded, none);
        assertNull(chunkClass.getField("deltaBiomeHigh").get(loaded));
    }

    @Test
    void packet56CarriesBiomes16() throws Exception {
        Object chunk = chunk(0, 0);
        call(chunkBiomes, "set", chunk, 9, 300);
        Object[] sections = (Object[]) Array.newInstance(section, 24);
        byte[] vanilla = new byte[256];
        byte[] data = (byte[]) call(chunkPackets, "withNgBlocks", vanilla, 0, sections, chunk);
        NgBlocks blocks = NgBlocks.parse(data, new int[] {vanilla.length});
        assertNotNull(blocks);
        assertEquals(1, blocks.biome[0]);
        assertArrayEquals((byte[]) chunkClass.getField("deltaBiomeHigh").get(chunk), blocks.plane(0, NgBlocks.BIOME, 0));
        assertEquals(vanilla.length + 42 + 256, data.length);
    }

    @Test
    void packet56CarriesTheUpperPlanes() throws Exception {
        Object[] sections = (Object[]) Array.newInstance(section, 24);
        sections[0] = filled();
        sections[5] = newSection();
        setId(sections[5], 2, 3, 4, 40000); // Add2 only
        sections[6] = newSection();
        setId(sections[6], 0, 0, 0, 300); // nothing above 4095
        sections[9] = filled(); // outside the mask
        call(section, "func_76654_b", sections[5], 7, 8, 9, 40000);
        call(section, "func_76654_b", sections[6], 1, 1, 1, 7); // vanilla meta: no NGMD plane
        int mask = 1 | 1 << 5 | 1 << 6;
        byte[] vanilla = new byte[1234];
        Arrays.fill(vanilla, (byte) 7);

        byte[] data = (byte[]) call(chunkPackets, "withNgBlocks", vanilla, mask, sections, null);
        NgBlocks blocks = NgBlocks.parse(data, new int[] {vanilla.length});
        assertNotNull(blocks, "NGClient would not read these blocks");
        assertArrayEquals(vanilla, Arrays.copyOf(data, vanilla.length));
        assertEquals(0, blocks.waterlog[0]);
        assertEquals(1 | 1 << 5, blocks.ids[0]);
        assertEquals(1 << 5, blocks.meta[0]);
        byte[] meta = blocks.plane(0, NgBlocks.META, 0);
        int index = 8 << 8 | 9 << 4 | 7;
        int high = meta[index >> 1] >> (index & 1) * 4 & 15;
        assertEquals(40000 >> 4 & 15, high);
        assertEquals(40000 >> 8 & 255, meta[2048 + index] & 0xFF);
        assertEquals(0, blocks.biome[0]);
        assertEquals(1, blocks.b3[0]);
        Object add2 = call(section, "getBlockA2Array", sections[0]);
        assertArrayEquals((byte[]) call(nibble, "getValueArray", add2), blocks.plane(0, NgBlocks.IDS, 0));
        add2 = call(section, "getBlockA2Array", sections[5]);
        assertArrayEquals((byte[]) call(nibble, "getValueArray", add2), blocks.plane(0, NgBlocks.IDS, 1));
        assertArrayEquals((byte[]) call(section, "getBlockB3Array", sections[0]), blocks.plane(0, NgBlocks.B3, 0));

        // A batch of two chunks, the second without wide IDs: NGClient walks them in a row.
        byte[] plain = (byte[]) call(chunkPackets, "withNgBlocks", vanilla, 1 << 6, sections, null);
        assertEquals(vanilla.length + 42, plain.length);
        byte[] batch = Arrays.copyOf(data, data.length + plain.length);
        System.arraycopy(plain, 0, batch, data.length, plain.length);
        NgBlocks two = NgBlocks.parse(batch, new int[] {vanilla.length, vanilla.length});
        assertNotNull(two);
        assertEquals(0, two.ids[1]);
        assertEquals(0, two.b3[1]);
    }

    @Test
    void snapshotsSeeEveryPlane() throws Exception {
        Object[] sections = (Object[]) Array.newInstance(section, 24);
        sections[4] = filled(); // y 0-15 with the height module
        sections[0] = newSection(); // y -64 to -49
        setId(sections[0], 1, 2, 3, 0xABCDEF);
        short[][] ids = new short[24][];
        for (int i = 0; i < 24; i++) {
            ids[i] = sections[i] == null ? null : vanillaShorts(sections[i]);
        }
        call(snapshots, "widen", ids, sections);
        byte[][] b3 = (byte[][]) call(snapshots, "b3", new Object[] {sections});
        assertNotNull(b3);
        for (int i = 0; i < IDS.length; i++) {
            int value = ids[4][y(i) << 8 | z(i) << 4 | x(i)];
            assertEquals(IDS[i], call(snapshots, "typeId", value, b3, x(i), y(i), z(i)), "ID " + IDS[i]);
        }
        int value = ids[0][2 << 8 | 3 << 4 | 1];
        assertEquals(0xABCDEF, call(snapshots, "typeId", value, b3, 1, -64 + 2, 3));
        assertNull(call(snapshots, "b3", new Object[] {Array.newInstance(section, 24)}));
        assertEquals(40000, call(snapshots, "typeId", (int) (short) 40000, null, 0, 0, 0));
    }

    /** A chunk without a world, enough for its biome arrays. */
    private Object chunk(int x, int z) throws Exception {
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object chunk = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, chunkClass);
        chunkClass.getField("field_76651_r").set(chunk, new byte[256]);
        for (String[] position : new String[][] {{"field_76635_g", "" + x}, {"field_76647_h", "" + z}}) {
            Field field = chunkClass.getField(position[0]);
            field.setAccessible(true);
            field.setInt(chunk, Integer.parseInt(position[1]));
        }
        return chunk;
    }

    // Sections

    private Object newSection() throws Exception {
        return create(section, 0, true);
    }

    /** One ID of IDS per position, the rest air. */
    private Object filled() throws Exception {
        Object storage = newSection();
        for (int i = 0; i < IDS.length; i++) {
            setId(storage, x(i), y(i), z(i), IDS[i]);
        }
        return storage;
    }

    private static int x(int i) {
        return i * 3 & 15;
    }

    private static int y(int i) {
        return i;
    }

    private static int z(int i) {
        return i * 7 & 15;
    }

    private int id(Object storage, int i) throws Exception {
        return (Integer) call(section, "func_76656_a", storage, x(i), y(i), z(i));
    }

    /** Writes the four planes by hand, as func_76655_a would without the block registry. */
    private void setId(Object storage, int x, int y, int z, int id) throws Exception {
        byte[] lsb = (byte[]) call(section, "func_76658_g", storage);
        lsb[y << 8 | z << 4 | x] = (byte) id;
        Object add = call(section, "func_76660_i", storage);
        if (add == null && (id >> 8 & 15) != 0) {
            add = call(section, "func_76667_m", storage);
        }
        if (add != null) {
            call(nibble, "func_76581_a", add, x, y, z, id >> 8 & 15);
        }
        Object add2 = call(section, "getBlockA2Array", storage);
        if (add2 == null && (id >> 12 & 15) != 0) {
            add2 = call(section, "createBlockA2Array", storage);
        }
        if (add2 != null) {
            call(nibble, "func_76581_a", add2, x, y, z, id >> 12 & 15);
        }
        byte[] b3 = (byte[]) call(section, "getBlockB3Array", storage);
        if (b3 == null && (id >> 16 & 255) != 0) {
            b3 = (byte[]) call(section, "createBlockB3Array", storage);
        }
        if (b3 != null) {
            b3[y << 8 | z << 4 | x] = (byte) (id >> 16);
        }
    }

    /** The short IDs CraftChunk builds: Blocks and Add only. */
    private short[] vanillaShorts(Object storage) throws Exception {
        byte[] lsb = (byte[]) call(section, "func_76658_g", storage);
        Object add = call(section, "func_76660_i", storage);
        short[] ids = new short[4096];
        for (int p = 0; p < 4096; p++) {
            int high = add == null ? 0 : (Integer) call(nibble, "func_76582_a", add, p & 15, p >> 8, p >> 4 & 15);
            ids[p] = (short) (lsb[p] & 0xFF | high << 8);
        }
        return ids;
    }

    // Save tags, as AnvilChunkLoader writes and reads them around the patched calls

    private Object vanillaTag(Object storage) throws Exception {
        Object tag = create(nbt);
        call(nbt, "func_74773_a", tag, "Blocks", call(section, "func_76658_g", storage));
        call(nbt, "func_74773_a", tag, "Data", call(nibble, "getValueArray", call(section, "func_76669_j", storage)));
        Object add = call(section, "func_76660_i", storage);
        if (add != null) {
            call(nbt, "func_74773_a", tag, "Add", call(nibble, "getValueArray", add));
        }
        return tag;
    }

    private Object save(Object storage) throws Exception {
        Object tag = vanillaTag(storage);
        call(sectionIds, "write", tag, storage);
        return tag;
    }

    private Object load(Object tag) throws Exception {
        Object storage = newSection();
        if ((Boolean) call(nbt, "func_74764_b", tag, "Blocks")) {
            call(section, "func_76664_a", storage, call(nbt, "func_74770_j", tag, "Blocks"));
        }
        if ((Boolean) call(nbt, "func_74764_b", tag, "Data")) {
            call(section, "func_76668_b", storage, create(nibble, call(nbt, "func_74770_j", tag, "Data"), 4));
        }
        if ((Boolean) call(nbt, "func_74764_b", tag, "Add")) {
            call(section, "func_76673_a", storage, create(nibble, call(nbt, "func_74770_j", tag, "Add"), 4));
        }
        call(sectionIds, "read", storage, tag);
        return storage;
    }

    private Object paletteTag(int[] ids, int bits, byte[] data) throws Exception {
        Object palette = create(list);
        for (int id : ids) {
            Object entry = create(nbt);
            call(nbt, "func_74778_a", entry, "Name", "unknown");
            call(nbt, "func_74768_a", entry, "Id", id);
            call(list, "func_74742_a", palette, entry);
        }
        Object tag = create(nbt);
        call(nbt, "func_74782_a", tag, "Palette", palette);
        call(nbt, "func_74774_a", tag, "PaletteBits", (byte) bits);
        call(nbt, "func_74773_a", tag, "PaletteData", data);
        return tag;
    }

    /**
     * The NG blocks of a Packet56 batch, read with NGClient's rules: after the vanilla data of each
     * chunk, NGWL, NGID, NGMD, NGBI and NGB3 in that order, version 1, masks on 24 bits, and nothing
     * left at the end.
     */
    static final class NgBlocks {

        /** Block numbers, in their order after the vanilla data. */
        static final int IDS = 1;
        static final int META = 2;
        static final int BIOME = 3;
        static final int B3 = 4;

        private static final int[] MAGICS = {0x4E47574C, 0x4E474944, 0x4E474D44, 0x4E474249, 0x4E474233};
        private static final int[] PER_SECTION = {512, 2048, 6144, 0, 4096};

        final int[] waterlog;
        final int[] ids;
        final int[] meta;
        final int[] biome;
        final int[] b3;
        private final byte[] data;
        /** Per chunk and block, where its section payload starts. */
        private final int[][] starts;

        private NgBlocks(int chunks, byte[] data) {
            waterlog = new int[chunks];
            ids = new int[chunks];
            meta = new int[chunks];
            biome = new int[chunks];
            b3 = new int[chunks];
            this.data = data;
            starts = new int[chunks][5];
        }

        static NgBlocks parse(byte[] data, int[] vanillaSizes) {
            NgBlocks blocks = new NgBlocks(vanillaSizes.length, data);
            int[][] masks = {blocks.waterlog, blocks.ids, blocks.meta, blocks.biome, blocks.b3};
            int p = 0;
            for (int chunk = 0; chunk < vanillaSizes.length; chunk++) {
                p += vanillaSizes[chunk];
                for (int block = 0; block < 5; block++) {
                    if (block == 3) {
                        if (p + 6 > data.length || readInt(data, p) != MAGICS[3] || data[p + 4] != 1) {
                            return null;
                        }
                        int flag = data[p + 5];
                        if (flag != 0 && flag != 1) {
                            return null;
                        }
                        masks[3][chunk] = flag;
                        blocks.starts[chunk][3] = p + 6;
                        p += 6 + flag * 256;
                        continue;
                    }
                    if (p + 9 > data.length || readInt(data, p) != MAGICS[block] || data[p + 4] != 1) {
                        return null;
                    }
                    int mask = readInt(data, p + 5);
                    if ((mask & 0xFF000000) != 0) {
                        return null;
                    }
                    masks[block][chunk] = mask;
                    blocks.starts[chunk][block] = p + 9;
                    p += 9 + Integer.bitCount(mask) * PER_SECTION[block];
                    if (p > data.length) {
                        return null;
                    }
                }
            }
            return p == data.length ? blocks : null;
        }

        /** Payload of the nth section present in a block (the chunk's biome plane for NGBI). */
        byte[] plane(int chunk, int block, int nth) {
            if (block == BIOME) {
                return Arrays.copyOfRange(data, starts[chunk][BIOME], starts[chunk][BIOME] + 256);
            }
            int start = starts[chunk][block] + nth * PER_SECTION[block];
            return Arrays.copyOfRange(data, start, start + PER_SECTION[block]);
        }

        private static int readInt(byte[] data, int p) {
            return (data[p] & 0xFF) << 24 | (data[p + 1] & 0xFF) << 16 | (data[p + 2] & 0xFF) << 8 | data[p + 3] & 0xFF;
        }
    }
}
