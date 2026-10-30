package fr.deltabuild.deltaagent.runtime;

import cpw.mods.fml.common.registry.GameRegistry;
import fr.deltabuild.deltaagent.core.Log;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * The planes of a saved section beyond vanilla's Blocks, Add and Data: block IDs in Add2 (bits
 * 12-15) and BlocksB3 (bits 16-23), metadata in DataAdd (bits 4-7) and Data2 (bits 8-15), and the
 * Palette sections NGClient can read.
 *
 * <p>Called by the patched AnvilChunkLoader, before the section counts its blocks.
 */
public final class SectionIds {

    public static final String ADD2 = "Add2";
    public static final String B3 = "BlocksB3";
    /** Metadata bits 4-7: NG's sources name no tag for it, this name is DeltaAgent's. */
    public static final String DATA_ADD = "DataAdd";
    public static final String DATA2 = "Data2";
    public static final String PALETTE = "Palette";
    public static final String PALETTE_DATA = "PaletteData";
    public static final String PALETTE_BITS = "PaletteBits";

    /** NGBlockIdFormat values, as NGClient numbers them. */
    public static final int FORMAT_ADD2 = 1;
    public static final int FORMAT_PALETTE = 2;
    public static final int FORMAT_B3 = 3;
    public static final int FORMAT_META16 = 4;

    private static final int POSITIONS = 4096;
    private static final int LOGGED = 32;

    private static volatile int format;
    private static final AtomicInteger logged = new AtomicInteger();

    /** A section just read: palette, or Add2 and BlocksB3 on top of Blocks and Add; then the metadata planes. */
    public static void read(ExtendedBlockStorage section, NBTTagCompound tag) {
        readMeta(section, tag);
        NBTBase palette = tag.func_74781_a(PALETTE);
        if (palette instanceof NBTTagList) {
            readPalette(section, tag, (NBTTagList) palette);
            return;
        }
        if (tag.func_74781_a(ADD2) instanceof NBTTagByteArray) {
            byte[] add2 = tag.func_74770_j(ADD2);
            if (add2.length == POSITIONS / 2) {
                section.setBlockA2Array(new NibbleArray(add2, 4));
                arm(FORMAT_ADD2);
            } else {
                log("Add2 plane of " + add2.length + " bytes ignored in section y=" + section.func_76662_d() + ".");
            }
        }
        if (tag.func_74781_a(B3) instanceof NBTTagByteArray) {
            byte[] b3 = tag.func_74770_j(B3);
            if (b3.length == POSITIONS) {
                section.setBlockB3Array(b3);
                arm(FORMAT_B3);
            } else {
                log("BlocksB3 plane of " + b3.length + " bytes ignored in section y=" + section.func_76662_d() + ".");
            }
        }
    }

    private static void readMeta(ExtendedBlockStorage section, NBTTagCompound tag) {
        if (tag.func_74781_a(DATA_ADD) instanceof NBTTagByteArray) {
            byte[] high = tag.func_74770_j(DATA_ADD);
            if (high.length == POSITIONS / 2) {
                section.setMetaHighArray(new NibbleArray(high, 4));
                arm(FORMAT_META16);
            } else {
                log("DataAdd plane of " + high.length + " bytes ignored in section y=" + section.func_76662_d() + ".");
            }
        }
        if (tag.func_74781_a(DATA2) instanceof NBTTagByteArray) {
            byte[] data2 = tag.func_74770_j(DATA2);
            if (data2.length == POSITIONS) {
                section.setMeta2Array(data2);
                arm(FORMAT_META16);
            } else {
                log("Data2 plane of " + data2.length + " bytes ignored in section y=" + section.func_76662_d() + ".");
            }
        }
    }

    /** A section about to be saved: Add2, BlocksB3, DataAdd and Data2 when they hold anything. */
    public static void write(NBTTagCompound tag, ExtendedBlockStorage section) {
        if (writeNibbles(tag, DATA_ADD, section.getMetaHighArray())) {
            arm(FORMAT_META16);
        }
        byte[] data2 = section.getMeta2Array();
        if (data2 != null && !allZero(data2)) {
            tag.func_74773_a(DATA2, data2);
            arm(FORMAT_META16);
        }
        if (writeNibbles(tag, ADD2, section.getBlockA2Array())) {
            arm(FORMAT_ADD2);
        }
        byte[] b3 = section.getBlockB3Array();
        if (b3 != null && !allZero(b3)) {
            tag.func_74773_a(B3, b3);
            arm(FORMAT_B3);
        }
    }

    /** Writes a nibble plane that holds anything; false if there is nothing to write. */
    private static boolean writeNibbles(NBTTagCompound tag, String name, NibbleArray plane) {
        if (plane == null || plane.isTrivialArray() && plane.getTrivialArrayValue() == 0) {
            return false;
        }
        byte[] bytes = plane.getValueArray();
        if (allZero(bytes)) {
            return false;
        }
        // A trivial array hands out a shared table: the tag gets its own copy.
        tag.func_74773_a(name, plane.isTrivialArray() ? bytes.clone() : bytes);
        return true;
    }

    /** Highest NGBlockIdFormat met since the start, 0 if none. */
    public static int format() {
        return format;
    }

    static void arm(int value) {
        if (value > format) {
            synchronized (SectionIds.class) {
                if (value > format) {
                    format = value;
                }
            }
        }
    }

    // Palette sections, read as NGClient reads them; the bits 16-23 it drops go to BlocksB3

    private static void readPalette(ExtendedBlockStorage section, NBTTagCompound tag, NBTTagList palette) {
        int entries = palette.func_74745_c();
        if (entries <= 0) {
            failPalette(section, "empty palette");
            return;
        }
        int[] ids = new int[entries];
        for (int i = 0; i < entries; i++) {
            ids[i] = resolve(palette.func_74743_b(i), i);
        }
        NBTBase declared = tag.func_74781_a(PALETTE_BITS);
        int bits = declared instanceof NBTTagByte ? ((NBTTagByte) declared).field_74756_a & 0xFF : bitsFor(entries);
        if (!(bits == 0 || bits == 1 || bits == 2 || bits == 4 || bits == 8 || bits == 16)
                || (bits >= 16 ? entries > 65536 : entries > 1 << bits)) {
            failPalette(section, "bad index width " + bits + " for " + entries + " entries");
            return;
        }
        byte[] data = null;
        if (bits > 0) {
            NBTBase raw = tag.func_74781_a(PALETTE_DATA);
            data = raw instanceof NBTTagByteArray ? ((NBTTagByteArray) raw).field_74754_a : null;
            int expected = POSITIONS * bits / 8;
            if (data == null || data.length != expected) {
                failPalette(section, "PaletteData of " + (data == null ? -1 : data.length) + " bytes, " + expected + " expected");
                return;
            }
        }
        byte[] lsb = new byte[POSITIONS];
        byte[] add = null;
        byte[] add2 = null;
        byte[] b3 = null;
        boolean outside = false;
        for (int position = 0; position < POSITIONS; position++) {
            int index = bits == 0 ? 0 : index(data, position, bits);
            if (index >= ids.length) {
                outside = true;
                continue;
            }
            int id = ids[index];
            if (id == 0) {
                continue;
            }
            lsb[position] = (byte) id;
            if (id > 0xFF) {
                add = nibble(add, position, id >> 8);
            }
            if (id > 0xFFF) {
                add2 = nibble(add2, position, id >> 12);
            }
            if (id > 0xFFFF) {
                if (b3 == null) {
                    b3 = new byte[POSITIONS];
                }
                b3[position] = (byte) (id >> 16);
            }
        }
        if (outside) {
            log("Palette index out of range in section y=" + section.func_76662_d() + ": those blocks become air.");
        }
        section.func_76664_a(lsb);
        section.deltaSetUpperPlanes(add == null ? null : new NibbleArray(add, 4), add2 == null ? null : new NibbleArray(add2, 4), b3);
        arm(FORMAT_PALETTE);
    }

    /** modid:name through the block registry first, then the pinned Id. */
    private static int resolve(NBTBase raw, int rank) {
        if (!(raw instanceof NBTTagCompound)) {
            log("Palette entry " + rank + " is not a compound: air.");
            return 0;
        }
        NBTTagCompound entry = (NBTTagCompound) raw;
        NBTBase name = entry.func_74781_a("Name");
        NBTBase pinned = entry.func_74781_a("Id");
        int id = -1;
        if (name instanceof NBTTagString) {
            String text = ((NBTTagString) name).field_74751_a;
            int colon = text == null ? -1 : text.indexOf(':');
            if (colon > 0 && colon < text.length() - 1) {
                try {
                    Block block = GameRegistry.findBlock(text.substring(0, colon), text.substring(colon + 1));
                    id = block == null ? -1 : block.field_71990_ca;
                } catch (Throwable t) {
                    id = -1;
                }
            }
        }
        if (id < 0 && pinned instanceof NBTTagInt) {
            id = ((NBTTagInt) pinned).field_74748_a;
        }
        if (id >= 0 && id <= 0xFFFFFF) {
            return id;
        }
        log("Palette entry " + rank + " not resolved (" + name + ", " + pinned + "): air.");
        return 0;
    }

    private static int bitsFor(int entries) {
        if (entries <= 1) {
            return 0;
        }
        return entries <= 16 ? 4 : entries <= 256 ? 8 : 16;
    }

    private static int index(byte[] data, int position, int bits) {
        if (bits == 16) {
            int offset = position << 1;
            return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
        }
        int perByte = 8 / bits;
        int value = data[position / perByte] & 0xFF;
        return value >> (position % perByte * bits) & (1 << bits) - 1;
    }

    private static byte[] nibble(byte[] plane, int position, int value) {
        byte[] bytes = plane == null ? new byte[POSITIONS / 2] : plane;
        int offset = position >> 1;
        bytes[offset] = (position & 1) == 0
                ? (byte) (bytes[offset] & 0xF0 | value & 0xF)
                : (byte) (bytes[offset] & 0x0F | (value & 0xF) << 4);
        return bytes;
    }

    private static void failPalette(ExtendedBlockStorage section, String reason) {
        log("Unreadable palette section y=" + section.func_76662_d() + " (" + reason + "): the section becomes air.");
        section.func_76664_a(new byte[POSITIONS]);
        section.deltaSetUpperPlanes(null, null, null);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static void log(String message) {
        int count = logged.incrementAndGet();
        if (count <= LOGGED) {
            Log.warn("ids", message + (count == LOGGED ? " Further section problems are not logged." : ""));
        }
    }

    private SectionIds() {
    }
}
