package fr.deltabuild.deltaagent.runtime;

import fr.deltabuild.deltaagent.core.Log;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetServerHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet29DestroyEntity;
import net.minecraft.network.packet.Packet51MapChunk;
import net.minecraft.network.packet.Packet51MapChunkData;
import net.minecraft.network.packet.Packet52MultiBlockChange;
import net.minecraft.network.packet.Packet53BlockChange;
import net.minecraft.network.packet.Packet56MapChunks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Block IDs above 4095 and metadata above 15 on the wire, the NGClient way. Only Packet56 carries
 * the upper planes, in five blocks after the vanilla data of each chunk. Any other packet that
 * cannot carry its block is turned into one that can, just before it is sent:
 * <ul>
 * <li>Packet53 (ID on 16 bits, meta on 8): Packet56 of the chunk beyond;</li>
 * <li>Packet52 (ID on 12 bits, meta on 4): one Packet53 per block, or Packet56 beyond Packet53;</li>
 * <li>Packet51 of sections with upper ID or metadata planes: Packet56, since NGClient clears the
 * ID planes and keeps stale metadata planes; the same for a whole chunk with Biomes16.</li>
 * </ul>
 * A Packet56 rebuilds the chunk on the client, so its tile entities and entities are sent again
 * right after. While PlayerInstance flushes a chunk, it is sent whole at most once per player.
 */
public final class ChunkPackets {

    static final int WATERLOG = 0x4E47574C; // NGWL
    static final int IDS = 0x4E474944; // NGID, Add2
    static final int META = 0x4E474D44; // NGMD, meta bits 4-7 then 8-15
    static final int BIOME = 0x4E474249; // NGBI, biome bits 8-15
    static final int B3 = 0x4E474233; // NGB3, BlocksB3
    static final int VERSION = 1;
    /** Decompressed bytes NGClient makes room for, per chunk of a batch. */
    static final int CLIENT_LIMIT = 393216;
    /** Header bytes of the five blocks when they carry nothing. */
    static final int EMPTY_BLOCKS = 9 + 9 + 9 + 6 + 9;
    private static final int LOGGED = 10;

    private static final Map<NetServerHandler, State> states =
            Collections.synchronizedMap(new WeakHashMap<NetServerHandler, State>());
    private static volatile int pending;
    private static final AtomicInteger oversized = new AtomicInteger();
    private static volatile Field trackedEntities;
    // PlayerInstance flush in progress (server thread), and its number.
    private static boolean flushing;
    private static int flush;

    /** Per player: chunks sent whole during the current flush, and packets to send after the current one. */
    private static final class State {
        final Map<Long, Integer> resent = new HashMap<Long, Integer>();
        final List<Object> after = new ArrayList<Object>();
    }

    /** A chunk whose entities the player must see spawn again. */
    private static final class Respawn {
        final Chunk chunk;

        Respawn(Chunk chunk) {
            this.chunk = chunk;
        }
    }

    // Packet56

    /** Packet56 constructor: the NG blocks go after the vanilla data of the chunk. */
    public static void extend(Packet51MapChunkData data, Chunk chunk) {
        data.field_74582_a = withNgBlocks(data.field_74582_a, data.field_74580_b, chunk.func_76587_i(), chunk);
    }

    static byte[] withNgBlocks(byte[] vanilla, int mask, ExtendedBlockStorage[] sections, Chunk chunk) {
        int add2Mask = 0;
        int metaMask = 0;
        int b3Mask = 0;
        for (int i = 0; i < sections.length && i < 24; i++) {
            ExtendedBlockStorage section = sections[i];
            if (section == null || (mask & 1 << i) == 0) {
                continue;
            }
            if (section.getBlockA2Array() != null) {
                add2Mask |= 1 << i;
            }
            if (hasMeta(section)) {
                metaMask |= 1 << i;
            }
            if (section.getBlockB3Array() != null) {
                b3Mask |= 1 << i;
            }
        }
        byte[] biomes = chunk == null ? null : ChunkBiomes.useful(chunk.deltaBiomeHigh);
        int size = vanilla.length + EMPTY_BLOCKS + Integer.bitCount(add2Mask) * 2048 + Integer.bitCount(metaMask) * 6144
                + (biomes == null ? 0 : 256) + Integer.bitCount(b3Mask) * 4096;
        byte[] out = Arrays.copyOf(vanilla, size);
        int p = header(out, vanilla.length, WATERLOG, 0);
        p = header(out, p, IDS, add2Mask);
        for (int i = 0; i < 24; i++) {
            if ((add2Mask & 1 << i) != 0) {
                p = sections[i].getBlockA2Array().copyToByteArray(out, p);
            }
        }
        p = header(out, p, META, metaMask);
        for (int i = 0; i < 24; i++) {
            if ((metaMask & 1 << i) != 0) {
                NibbleArray high = sections[i].getMetaHighArray();
                if (high != null) {
                    high.copyToByteArray(out, p);
                }
                byte[] data2 = sections[i].getMeta2Array();
                if (data2 != null) {
                    System.arraycopy(data2, 0, out, p + 2048, 4096);
                }
                p += 6144; // a missing plane stays at zero
            }
        }
        p = putInt(out, p, BIOME);
        out[p++] = VERSION;
        out[p++] = (byte) (biomes == null ? 0 : 1);
        if (biomes != null) {
            System.arraycopy(biomes, 0, out, p, 256);
            p += 256;
        }
        p = header(out, p, B3, b3Mask);
        for (int i = 0; i < 24; i++) {
            if ((b3Mask & 1 << i) != 0) {
                System.arraycopy(sections[i].getBlockB3Array(), 0, out, p, 4096);
                p += 4096;
            }
        }
        if (p != size) {
            throw new IllegalStateException("NG blocks of " + (p - vanilla.length) + " bytes, " + (size - vanilla.length) + " expected");
        }
        if (size > CLIENT_LIMIT && oversized.incrementAndGet() <= LOGGED) {
            Log.warn("ids", "Chunk " + (chunk == null ? "?" : chunk.field_76635_g + "," + chunk.field_76647_h) + " is " + size
                    + " bytes once decompressed, over the " + CLIENT_LIMIT + " NGClient allows per chunk: it may refuse it.");
        }
        return out;
    }

    /** Whether a metadata plane of the section holds anything. */
    static boolean hasMeta(ExtendedBlockStorage section) {
        NibbleArray high = section.getMetaHighArray();
        if (high != null && !(high.isTrivialArray() && high.getTrivialArrayValue() == 0) && !zero(high.getValueArray())) {
            return true;
        }
        byte[] data2 = section.getMeta2Array();
        return data2 != null && !zero(data2);
    }

    private static boolean zero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static int header(byte[] out, int p, int magic, int mask) {
        p = putInt(out, p, magic);
        out[p++] = VERSION;
        return putInt(out, p, mask);
    }

    private static int putInt(byte[] out, int p, int value) {
        out[p] = (byte) (value >>> 24);
        out[p + 1] = (byte) (value >>> 16);
        out[p + 2] = (byte) (value >>> 8);
        out[p + 3] = (byte) value;
        return p + 4;
    }

    // Sending

    /** PlayerInstance.flushPendingChanges, start. */
    public static void flushStart() {
        flushing = true;
        flush++;
    }

    /** PlayerInstance.flushPendingChanges, end. */
    public static void flushEnd() {
        flushing = false;
    }

    /** NetServerHandler.sendPacketToPlayer, first thing: the packet to send instead, null to drop it. */
    public static Packet onSend(Packet packet, NetServerHandler handler) {
        try {
            if (packet instanceof Packet53BlockChange) {
                Packet53BlockChange change = (Packet53BlockChange) packet;
                if (change.field_73421_d > 0xFFFF || change.field_73422_e > 0xFF) {
                    return wholeChunk(handler, change.field_73425_a >> 4, change.field_73424_c >> 4, packet);
                }
            } else if (packet instanceof Packet52MultiBlockChange) {
                return multiBlock(handler, (Packet52MultiBlockChange) packet);
            } else if (packet instanceof Packet51MapChunk) {
                Packet51MapChunk map = (Packet51MapChunk) packet;
                if (map.field_73600_c != 0) {
                    Chunk chunk = loadedChunk(world(handler), map.field_73601_a, map.field_73599_b);
                    if (chunk != null && (hasWidePlanes(chunk, map.field_73600_c)
                            || map.field_73598_e && ChunkBiomes.useful(chunk.deltaBiomeHigh) != null)) {
                        return wholeChunk(handler, map.field_73601_a, map.field_73599_b, packet);
                    }
                }
            }
        } catch (Throwable t) {
            if (oversized.incrementAndGet() <= LOGGED) {
                Log.warn("ids", "Could not check a " + packet.getClass().getSimpleName() + " for wide block IDs: " + t);
            }
        }
        return packet;
    }

    /** NetServerHandler.sendPacketToPlayer, last thing: what a replacement left to send. */
    public static void afterSend(NetServerHandler handler) {
        if (pending == 0) {
            return;
        }
        List<Object> work;
        synchronized (states) {
            State state = states.get(handler);
            if (state == null || state.after.isEmpty()) {
                return;
            }
            work = new ArrayList<Object>(state.after);
            pending -= work.size();
            state.after.clear();
        }
        for (Object item : work) {
            if (item instanceof Packet) {
                handler.func_72567_b((Packet) item);
            } else {
                respawnEntities(handler, ((Respawn) item).chunk);
            }
        }
    }

    private static Packet multiBlock(NetServerHandler handler, Packet52MultiBlockChange packet) {
        byte[] records = packet.field_73451_c;
        if (records == null) {
            return packet;
        }
        World world = world(handler);
        Chunk chunk = loadedChunk(world, packet.field_73452_a, packet.field_73450_b);
        if (chunk == null) {
            return packet;
        }
        int count = Math.min(packet.field_73448_d, records.length / 4);
        boolean wide = false;
        for (int i = 0; i < count; i++) {
            int position = (records[i * 4] & 0xFF) << 8 | records[i * 4 + 1] & 0xFF;
            int x = position >> 12 & 15;
            int y = position & 255;
            int z = position >> 8 & 15;
            int id = chunk.func_76610_a(x, y, z);
            int meta = chunk.func_76628_c(x, y, z);
            if (id > 0xFFFF || meta > 0xFF) {
                return wholeChunk(handler, packet.field_73452_a, packet.field_73450_b, packet);
            }
            wide |= id > 0xFFF || meta > 15;
        }
        if (!wide) {
            return packet;
        }
        // One Packet53 per block, which NGClient reads with a 16-bit ID and an 8-bit meta.
        Packet first = null;
        List<Object> rest = new ArrayList<Object>();
        for (int i = 0; i < count; i++) {
            int position = (records[i * 4] & 0xFF) << 8 | records[i * 4 + 1] & 0xFF;
            Packet53BlockChange change = new Packet53BlockChange(packet.field_73452_a * 16 + (position >> 12 & 15),
                    position & 255, packet.field_73450_b * 16 + (position >> 8 & 15), world);
            if (first == null) {
                first = change;
            } else {
                rest.add(change);
            }
        }
        queue(handler, rest);
        return first;
    }

    private static Packet wholeChunk(NetServerHandler handler, int chunkX, int chunkZ, Packet original) {
        Chunk chunk = loadedChunk(world(handler), chunkX, chunkZ);
        if (chunk == null) {
            return original;
        }
        if (flushing && Thread.currentThread() == MinecraftServer.func_71276_C().primaryThread) {
            Long key = Long.valueOf((long) chunkX << 32 | chunkZ & 0xFFFFFFFFL);
            synchronized (states) {
                Map<Long, Integer> resent = state(handler).resent;
                if (resent.size() > 256) {
                    resent.clear(); // older flushes
                }
                Integer previous = resent.put(key, Integer.valueOf(flush));
                if (previous != null && previous.intValue() == flush) {
                    return null; // already sent whole by this flush
                }
            }
        }
        List<Object> after = new ArrayList<Object>();
        for (Object value : chunk.field_76648_i.values()) {
            Packet description = ((TileEntity) value).func_70319_e();
            if (description != null) {
                after.add(description);
            }
        }
        after.add(new Respawn(chunk));
        queue(handler, after);
        return new Packet56MapChunks(Collections.singletonList(chunk));
    }

    /**
     * The client made a new chunk: entities standing in it are no longer in its lists, so it cannot
     * aim at them. Destroy and spawn them again for this player.
     */
    private static void respawnEntities(NetServerHandler handler, Chunk chunk) {
        EntityPlayerMP player = handler.field_72574_e;
        if (Thread.currentThread() != MinecraftServer.func_71276_C().primaryThread || !(player.field_70170_p instanceof WorldServer)) {
            return;
        }
        EntityTracker tracker = ((WorldServer) player.field_70170_p).func_73039_n();
        Set<?> entries = trackedEntries(tracker);
        if (entries == null) {
            return;
        }
        List<Integer> ids = new ArrayList<Integer>();
        for (Object value : entries) {
            EntityTrackerEntry entry = (EntityTrackerEntry) value;
            Entity entity = entry.field_73132_a;
            if (entity == player || entity.field_70176_ah != chunk.field_76635_g || entity.field_70164_aj != chunk.field_76647_h) {
                continue;
            }
            if (entry.field_73134_o.remove(player)) {
                ids.add(Integer.valueOf(entity.field_70157_k));
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] array = new int[ids.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = ids.get(i).intValue();
        }
        handler.func_72567_b(new Packet29DestroyEntity(array));
        tracker.func_85172_a(player, chunk);
    }

    private static Set<?> trackedEntries(EntityTracker tracker) {
        try {
            Field field = trackedEntities;
            if (field == null) {
                field = EntityTracker.class.getDeclaredField("field_72793_b");
                field.setAccessible(true);
                trackedEntities = field;
            }
            return (Set<?>) field.get(tracker);
        } catch (Exception e) {
            if (oversized.incrementAndGet() <= LOGGED) {
                Log.warn("ids", "Cannot list the tracked entities: " + e);
            }
            return null;
        }
    }

    private static void queue(NetServerHandler handler, List<Object> packets) {
        if (packets.isEmpty()) {
            return;
        }
        synchronized (states) {
            state(handler).after.addAll(packets);
            pending += packets.size();
        }
    }

    private static State state(NetServerHandler handler) {
        State state = states.get(handler);
        if (state == null) {
            state = new State();
            states.put(handler, state);
        }
        return state;
    }

    private static World world(NetServerHandler handler) {
        return handler.field_72574_e.field_70170_p;
    }

    private static Chunk loadedChunk(World world, int chunkX, int chunkZ) {
        if (world == null || !world.func_72863_F().func_73149_a(chunkX, chunkZ)) {
            return null;
        }
        return world.func_72964_e(chunkX, chunkZ);
    }

    /** Whether one of the sections of the mask has upper ID or metadata planes. */
    static boolean hasWidePlanes(Chunk chunk, int mask) {
        ExtendedBlockStorage[] sections = chunk.func_76587_i();
        for (int i = 0; i < sections.length && i < 24; i++) {
            if ((mask & 1 << i) != 0 && sections[i] != null && (sections[i].deltaHasWidePlanes() || sections[i].deltaHasWideMeta())) {
                return true;
            }
        }
        return false;
    }

    private ChunkPackets() {
    }
}
