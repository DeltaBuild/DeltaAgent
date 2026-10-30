package fr.deltabuild.deltaagent.runtime;

import fr.deltabuild.deltaagent.core.Safety;
import fr.deltabuild.deltaagent.ids.IdsModule;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagShort;

/**
 * The ID formats a world declares in level.dat (Data compound), as NationsGlory servers write them.
 * A server refuses a world whose format it cannot read: loading it would lose items or blocks at
 * the next save.
 *
 * <p>Called by the patched WorldInfo when level.dat is read and written.
 */
public final class WorldFormats {

    public static final String ITEMS = "NGItemIdFormat";
    public static final String BLOCKS = "NGBlockIdFormat";

    /** Once a world declares a format, every save keeps it, unless asked to forget it. */
    private static volatile int declaredItems;

    public static void read(NBTTagCompound data) {
        int blocks = number(data.func_74781_a(BLOCKS));
        int items = number(data.func_74781_a(ITEMS));
        if (blocks > IdsModule.blockFormat()) {
            Safety.stop("ids", "This world declares " + BLOCKS + "=" + blocks + " in level.dat: its chunks hold block IDs"
                    + " above 4095, which this version cannot read yet. Loading it would turn those blocks into others"
                    + " or air, and the next save would keep it. Use the server that wrote this world.");
            return;
        }
        if (items > IdsModule.itemFormat()) {
            String fix = IdsModule.itemFormat() == 0 && items == 1
                    ? "Remove -D" + IdsModule.OPTION + "=false." : "Use the server that wrote this world.";
            Safety.stop("ids", "This world declares " + ITEMS + "=" + items + " in level.dat: it holds items saved with"
                    + " idExt. Without that support they would read back as ID 0 and vanish at the next save. " + fix);
            return;
        }
        if (items > declaredItems && !IdsModule.forgetWorldFormat()) {
            declaredItems = items;
        }
    }

    public static void write(NBTTagCompound data) {
        int items = Math.max(declaredItems, ItemIds.extendedWritten() ? 1 : 0);
        if (items > 0) {
            data.func_74768_a(ITEMS, items);
        }
    }

    private static int number(NBTBase tag) {
        if (tag instanceof NBTTagInt) {
            return ((NBTTagInt) tag).field_74748_a;
        }
        if (tag instanceof NBTTagShort) {
            return ((NBTTagShort) tag).field_74752_a;
        }
        if (tag instanceof NBTTagByte) {
            return ((NBTTagByte) tag).field_74756_a;
        }
        return 0;
    }

    private WorldFormats() {
    }
}
