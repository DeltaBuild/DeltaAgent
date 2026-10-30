package net.minecraft.world.chunk.storage;

import net.minecraft.block.Block;
import net.minecraft.world.chunk.NibbleArray;

/**
 * Chunk section of MCPC+ 1.6.4 (build 164-251) with 24-bit block IDs, as NGClient stores them:
 * Blocks (bits 0-7), Add (8-11), Add2 (12-15) and BlocksB3 (16-23). Add2 and BlocksB3 exist only
 * once a block needs them. Everything else is MCPC+'s section, Spigot's trivial nibble arrays
 * included. Shipped by the ids module (DeltaAgent).
 */
public class ExtendedBlockStorage {

    private int field_76684_a;
    private int field_76682_b;
    private int field_76683_c;
    private byte[] field_76680_d;
    private NibbleArray field_76681_e;
    private NibbleArray field_76678_f;
    private NibbleArray field_76679_g;
    private NibbleArray field_76685_h;
    // Bits 12-15 and 16-23 of the block IDs, null while unused. Names of NGClient.
    private NibbleArray blockA2Array;
    private byte[] blockB3Array;

    public ExtendedBlockStorage(int par1, boolean par2) {
        this.field_76684_a = par1;
        this.field_76680_d = new byte[4096];
        this.field_76678_f = new NibbleArray(this.field_76680_d.length, 4);
        this.field_76679_g = new NibbleArray(this.field_76680_d.length, 4);
        if (par2) {
            this.field_76685_h = new NibbleArray(this.field_76680_d.length, 4);
        }
    }

    // CraftBukkit chunk generators
    public ExtendedBlockStorage(int y, boolean flag, byte[] blkIds, byte[] extBlkIds) {
        this.field_76684_a = y;
        this.field_76680_d = blkIds;
        if (extBlkIds != null) {
            this.field_76681_e = new NibbleArray(extBlkIds, 4);
        }
        this.field_76678_f = new NibbleArray(this.field_76680_d.length, 4);
        this.field_76679_g = new NibbleArray(this.field_76680_d.length, 4);
        if (flag) {
            this.field_76685_h = new NibbleArray(this.field_76680_d.length, 4);
        }
        this.func_76672_e();
    }

    /** Block ID at section coordinates. */
    public int func_76656_a(int par1, int par2, int par3) {
        int index = par2 << 8 | par3 << 4 | par1;
        int id = this.field_76680_d[index] & 0xFF;
        if (this.field_76681_e != null) {
            id |= this.field_76681_e.func_76582_a(par1, par2, par3) << 8;
        }
        if (this.blockA2Array != null) {
            id |= this.blockA2Array.func_76582_a(par1, par2, par3) << 12;
        }
        if (this.blockB3Array != null) {
            id |= (this.blockB3Array[index] & 0xFF) << 16;
        }
        return id;
    }

    /** Sets the block ID at section coordinates; an ID outside the block registry is refused. */
    public void func_76655_a(int par1, int par2, int par3, int par4) {
        Block[] blocks = Block.field_71973_m;
        if (par4 < 0 || par4 >= blocks.length) {
            throw new IllegalStateException("[DeltaAgent/ids] Block ID " + par4 + " is outside the block registry (0-"
                    + (blocks.length - 1) + ") at section position x=" + par1 + " y=" + (this.field_76684_a + par2)
                    + " z=" + par3 + ".");
        }
        int old = this.func_76656_a(par1, par2, par3);
        Block before = old < blocks.length ? blocks[old] : null;
        Block after = blocks[par4];
        if (old == 0 && par4 != 0) {
            ++this.field_76682_b;
            if (after != null && after.func_71881_r()) {
                ++this.field_76683_c;
            }
        } else if (old != 0 && par4 == 0) {
            --this.field_76682_b;
            if (before != null && before.func_71881_r()) {
                --this.field_76683_c;
            }
        } else if (before != null && before.func_71881_r() && (after == null || !after.func_71881_r())) {
            --this.field_76683_c;
        } else if ((before == null || !before.func_71881_r()) && after != null && after.func_71881_r()) {
            ++this.field_76683_c;
        }
        int index = par2 << 8 | par3 << 4 | par1;
        this.field_76680_d[index] = (byte) (par4 & 0xFF);
        if (par4 > 0xFF) {
            if (this.field_76681_e == null) {
                this.field_76681_e = new NibbleArray(this.field_76680_d.length, 4);
            }
            this.field_76681_e.func_76581_a(par1, par2, par3, (par4 & 0xF00) >> 8);
        } else if (this.field_76681_e != null) {
            this.field_76681_e.func_76581_a(par1, par2, par3, 0);
        }
        if (par4 > 0xFFF) {
            if (this.blockA2Array == null) {
                this.blockA2Array = new NibbleArray(this.field_76680_d.length, 4);
            }
            this.blockA2Array.func_76581_a(par1, par2, par3, (par4 & 0xF000) >> 12);
        } else if (this.blockA2Array != null) {
            this.blockA2Array.func_76581_a(par1, par2, par3, 0);
        }
        if (par4 > 0xFFFF) {
            if (this.blockB3Array == null) {
                this.blockB3Array = new byte[this.field_76680_d.length];
            }
            this.blockB3Array[index] = (byte) ((par4 & 0xFF0000) >> 16);
        } else if (this.blockB3Array != null) {
            this.blockB3Array[index] = 0;
        }
    }

    public int func_76665_b(int par1, int par2, int par3) {
        return this.field_76678_f.func_76582_a(par1, par2, par3);
    }

    public void func_76654_b(int par1, int par2, int par3, int par4) {
        this.field_76678_f.func_76581_a(par1, par2, par3, par4);
    }

    public boolean func_76663_a() {
        return this.field_76682_b == 0;
    }

    public boolean func_76675_b() {
        return this.field_76683_c > 0;
    }

    public int func_76662_d() {
        return this.field_76684_a;
    }

    public void func_76657_c(int par1, int par2, int par3, int par4) {
        this.field_76685_h.func_76581_a(par1, par2, par3, par4);
    }

    public int func_76670_c(int par1, int par2, int par3) {
        return this.field_76685_h.func_76582_a(par1, par2, par3);
    }

    public void func_76677_d(int par1, int par2, int par3, int par4) {
        this.field_76679_g.func_76581_a(par1, par2, par3, par4);
    }

    public int func_76674_d(int par1, int par2, int par3) {
        return this.field_76679_g.func_76582_a(par1, par2, par3);
    }

    /**
     * Recounts the blocks after a load. A block that is not registered (removed mod, or past the
     * registry) becomes air, as in vanilla; unused upper planes are dropped.
     */
    public void func_76672_e() {
        byte[] lsb = this.field_76680_d;
        Block[] blocks = Block.field_71973_m;
        int nonEmpty = 0;
        int ticking = 0;
        if (this.field_76681_e == null && this.blockA2Array == null && this.blockB3Array == null) {
            for (int off = 0; off < lsb.length; ++off) {
                int id = lsb[off] & 0xFF;
                if (id <= 0) {
                    continue;
                }
                Block block = blocks[id];
                if (block == null) {
                    lsb[off] = 0;
                    continue;
                }
                ++nonEmpty;
                if (block.func_71881_r()) {
                    ++ticking;
                }
            }
        } else {
            byte[] add = writable(this.field_76681_e);
            byte[] add2 = writable(this.blockA2Array);
            byte[] b3 = this.blockB3Array;
            for (int off = 0; off < lsb.length; ++off) {
                int shift = (off & 1) << 2;
                int half = off >> 1;
                int id = lsb[off] & 0xFF;
                if (add != null) {
                    id |= (add[half] >> shift & 0xF) << 8;
                }
                if (add2 != null) {
                    id |= (add2[half] >> shift & 0xF) << 12;
                }
                if (b3 != null) {
                    id |= (b3[off] & 0xFF) << 16;
                }
                if (id <= 0) {
                    continue;
                }
                Block block = id < blocks.length ? blocks[id] : null;
                if (block == null) {
                    int keep = shift == 0 ? 0xF0 : 0x0F;
                    lsb[off] = 0;
                    if (add != null) {
                        add[half] = (byte) (add[half] & keep);
                    }
                    if (add2 != null) {
                        add2[half] = (byte) (add2[half] & keep);
                    }
                    if (b3 != null) {
                        b3[off] = 0;
                    }
                    continue;
                }
                ++nonEmpty;
                if (block.func_71881_r()) {
                    ++ticking;
                }
            }
            this.field_76681_e = trimmed(this.field_76681_e);
            this.blockA2Array = trimmed(this.blockA2Array);
            if (b3 != null && allZero(b3)) {
                this.blockB3Array = null;
            }
        }
        this.field_76682_b = nonEmpty;
        this.field_76683_c = ticking;
    }

    private static byte[] writable(NibbleArray array) {
        if (array == null) {
            return null;
        }
        array.forceToNonTrivialArray();
        return array.getValueArray();
    }

    /** Back to Spigot's trivial form, or null when the plane holds only zeros. */
    private static NibbleArray trimmed(NibbleArray array) {
        if (array == null) {
            return null;
        }
        array.detectAndProcessTrivialArray();
        return array.isTrivialArray() && array.getTrivialArrayValue() == 0 ? null : array;
    }

    private static boolean allZero(byte[] array) {
        for (byte b : array) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    public void old_recalcBlockCounts() {
        this.field_76682_b = 0;
        this.field_76683_c = 0;
        Block[] blocks = Block.field_71973_m;
        for (int i = 0; i < 16; ++i) {
            for (int j = 0; j < 16; ++j) {
                for (int k = 0; k < 16; ++k) {
                    int id = this.func_76656_a(i, j, k);
                    if (id <= 0) {
                        continue;
                    }
                    Block block = id < blocks.length ? blocks[id] : null;
                    if (block == null) {
                        this.field_76680_d[j << 8 | k << 4 | i] = 0;
                        if (this.field_76681_e != null) {
                            this.field_76681_e.func_76581_a(i, j, k, 0);
                        }
                        if (this.blockA2Array != null) {
                            this.blockA2Array.func_76581_a(i, j, k, 0);
                        }
                        if (this.blockB3Array != null) {
                            this.blockB3Array[j << 8 | k << 4 | i] = 0;
                        }
                        continue;
                    }
                    ++this.field_76682_b;
                    if (block.func_71881_r()) {
                        ++this.field_76683_c;
                    }
                }
            }
        }
    }

    public byte[] func_76658_g() {
        return this.field_76680_d;
    }

    public void func_76676_h() {
        this.field_76681_e = null;
    }

    public NibbleArray func_76660_i() {
        return this.field_76681_e;
    }

    public NibbleArray func_76669_j() {
        return this.field_76678_f;
    }

    public NibbleArray func_76661_k() {
        return this.field_76679_g;
    }

    public NibbleArray func_76671_l() {
        return this.field_76685_h;
    }

    public void func_76664_a(byte[] par1ArrayOfByte) {
        this.field_76680_d = this.validateByteArray(par1ArrayOfByte);
    }

    public void func_76673_a(NibbleArray par1NibbleArray) {
        if (par1NibbleArray.isTrivialArray() && par1NibbleArray.getTrivialArrayValue() == 0) {
            return;
        }
        this.field_76681_e = this.validateNibbleArray(par1NibbleArray);
    }

    public void func_76668_b(NibbleArray par1NibbleArray) {
        this.field_76678_f = this.validateNibbleArray(par1NibbleArray);
    }

    public void func_76659_c(NibbleArray par1NibbleArray) {
        this.field_76679_g = this.validateNibbleArray(par1NibbleArray);
    }

    public void func_76666_d(NibbleArray par1NibbleArray) {
        this.field_76685_h = this.validateNibbleArray(par1NibbleArray);
    }

    private NibbleArray validateNibbleArray(NibbleArray nibbleArray) {
        if (nibbleArray != null && nibbleArray.getByteLength() < 2048) {
            nibbleArray.resizeArray(2048);
        }
        return nibbleArray;
    }

    private byte[] validateByteArray(byte[] byteArray) {
        if (byteArray != null && byteArray.length < 4096) {
            byte[] newArray = new byte[4096];
            System.arraycopy(byteArray, 0, newArray, 0, byteArray.length);
            byteArray = newArray;
        }
        return byteArray;
    }

    public NibbleArray func_76667_m() {
        this.field_76681_e = new NibbleArray(this.field_76680_d.length, 4);
        return this.field_76681_e;
    }

    // Bits 12-15 (Add2), NGClient's API

    public NibbleArray getBlockA2Array() {
        return this.blockA2Array;
    }

    public void setBlockA2Array(NibbleArray par1NibbleArray) {
        this.blockA2Array = this.validateNibbleArray(par1NibbleArray);
    }

    public NibbleArray createBlockA2Array() {
        this.blockA2Array = new NibbleArray(this.field_76680_d.length, 4);
        return this.blockA2Array;
    }

    public void clearA2Array() {
        this.blockA2Array = null;
    }

    // Bits 16-23 (BlocksB3), NGClient's API

    public byte[] getBlockB3Array() {
        return this.blockB3Array;
    }

    public void setBlockB3Array(byte[] par1Array) {
        if (par1Array != null && par1Array.length != this.field_76680_d.length) {
            throw new IllegalArgumentException("[DeltaAgent/ids] BlocksB3 plane of " + par1Array.length + " bytes, expected "
                    + this.field_76680_d.length + ".");
        }
        this.blockB3Array = par1Array;
    }

    public byte[] createBlockB3Array() {
        this.blockB3Array = new byte[this.field_76680_d.length];
        return this.blockB3Array;
    }

    public void clearB3Array() {
        this.blockB3Array = null;
    }

    // DeltaAgent

    /** Sets the three upper planes at once (palette sections); null clears a plane. */
    public void deltaSetUpperPlanes(NibbleArray add, NibbleArray add2, byte[] b3) {
        this.field_76681_e = add;
        this.blockA2Array = add2;
        this.setBlockB3Array(b3);
    }

    /** Whether Add2 or BlocksB3 is in use: the section holds IDs above 4095. */
    public boolean deltaHasWidePlanes() {
        return this.blockA2Array != null || this.blockB3Array != null;
    }

    /** Whether the block here has an ID above 255, so Blocks alone does not tell it. */
    public boolean deltaIsWide(int x, int y, int z) {
        return this.field_76681_e != null && this.field_76681_e.func_76582_a(x, y, z) != 0
                || this.blockA2Array != null && this.blockA2Array.func_76582_a(x, y, z) != 0
                || this.blockB3Array != null && this.blockB3Array[y << 8 | z << 4 | x] != 0;
    }
}
