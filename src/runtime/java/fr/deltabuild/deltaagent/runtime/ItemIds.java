package fr.deltabuild.deltaagent.runtime;

import fr.deltabuild.deltaagent.core.Log;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagShort;

/**
 * Item IDs above 31999, written the NationsGlory way: the vanilla short "id" holds 0 and an
 * "idExt" int holds the real ID, in saved NBT and in the tag of network item stacks.
 *
 * <p>Called by the patched ItemStack, Packet and Packet20NamedEntitySpawn.
 */
public final class ItemIds {

    public static final String EXT = "idExt";
    /** Highest item ID still written as a plain short. */
    public static final int VANILLA_MAX = 31999;
    private static final int LOGGED = 5;

    private static volatile boolean extendedWritten;
    private static final AtomicInteger recovered = new AtomicInteger();

    /** ItemStack.writeToNBT: {@code tag.setShort(key, (short) id)}. */
    public static void writeId(NBTTagCompound tag, String key, int id) {
        if (id > VANILLA_MAX) {
            tag.func_74777_a(key, (short) 0);
            tag.func_74768_a(EXT, id);
            if (!extendedWritten) {
                extendedWritten = true;
                Log.info("ids", "Item " + id + " saved with idExt: level.dat files now get NGItemIdFormat=1.");
            }
        } else {
            tag.func_74777_a(key, (short) id);
            tag.func_82580_o(EXT); // a reused tag must not keep the ID of an earlier item
        }
    }

    /** ItemStack.readFromNBT: {@code tag.getShort(key)}, with idExt first. */
    public static int readId(NBTTagCompound tag, String key) {
        NBTBase ext = tag.func_74781_a(EXT);
        if (ext instanceof NBTTagInt) {
            return ((NBTTagInt) ext).field_74748_a;
        }
        NBTBase id = tag.func_74781_a(key);
        if (id instanceof NBTTagShort) {
            short value = ((NBTTagShort) id).field_74752_a;
            return value < 0 ? recover(value) : value;
        }
        if (id instanceof NBTTagInt) {
            return ((NBTTagInt) id).field_74748_a;
        }
        return 0;
    }

    /**
     * Before DeltaAgent, items 32768-65535 were saved as a plain short and came back negative. Their
     * unsigned value is the real ID when an item has it.
     */
    private static int recover(short value) {
        int id = value & 0xFFFF;
        Item[] items = Item.field_77698_e;
        if (id >= items.length || items[id] == null) {
            return value;
        }
        int count = recovered.incrementAndGet();
        if (count <= LOGGED) {
            Log.info("ids", "Item ID " + value + " read back as " + id + " (saved as a short before DeltaAgent)."
                    + (count == LOGGED ? " Further ones are not logged." : ""));
        }
        return id;
    }

    /** Packet.writeItemStack and Packet20: the short sent for an item ID. */
    public static int netId(int id) {
        return id > VANILLA_MAX ? 0 : id;
    }

    /** Packet.writeItemStack: the tag sent with the stack, with idExt for a large ID. */
    public static NBTTagCompound netTag(NBTTagCompound tag, ItemStack stack) {
        int id = stack.field_77993_c;
        if (id <= VANILLA_MAX) {
            return tag;
        }
        NBTTagCompound sent = tag == null ? new NBTTagCompound() : (NBTTagCompound) tag.func_74737_b();
        sent.func_74768_a(EXT, id);
        return sent;
    }

    /** Packet.readItemStack: takes the ID out of idExt, and drops the tag if nothing else is left. */
    public static void readNetExt(ItemStack stack) {
        NBTTagCompound tag = stack.field_77990_d;
        if (stack.field_77993_c != 0 || tag == null) {
            return;
        }
        NBTBase ext = tag.func_74781_a(EXT);
        if (ext instanceof NBTTagInt) {
            stack.field_77993_c = ((NBTTagInt) ext).field_74748_a;
            tag.func_82580_o(EXT);
            if (tag.func_82582_d()) {
                stack.field_77990_d = null;
            }
        }
    }

    static boolean extendedWritten() {
        return extendedWritten;
    }

    private ItemIds() {
    }
}
