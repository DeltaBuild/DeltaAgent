package fr.deltabuild.deltaagent.height;

import fr.deltabuild.deltaagent.core.Bytes;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.Options;
import fr.deltabuild.deltaagent.core.Safety;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Refuses to start a world saved with the extended height (Y -64..319) when nothing on the command
 * line handles that height. MCPC+ alone cannot read those chunks and would generate them again.
 *
 * <p>Looks for the tag that NGHeight and DeltaHeight write into every chunk they save. Region files
 * are read before the server starts, so a hit stops it before a single chunk is loaded.
 */
public final class HeightGuard {

    static final String[] MARKER_TAGS = {"DeltaHeight", "NGHeight"};
    private static final byte[][] MARKERS = nbtNames(MARKER_TAGS);
    private static final int MAX_DEPTH = 4;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    private static final long TIME_BUDGET_MS = 30_000;

    /** What a scan found. */
    static final class Result {
        File markedRegion;
        int regionFiles;
        int chunks;
        boolean outOfTime;
    }

    /** @param heightAgent name of the agent that handles the height, or null if none */
    public static void check(String heightAgent) {
        if (heightAgent != null) {
            Log.info("height", heightAgent + " handles the world height for now.");
            return;
        }
        if (!Options.flag("deltaagent.height.guard", true)) {
            Log.warn("height", "World height check turned off (-Ddeltaagent.height.guard=false).");
            return;
        }
        File root = new File(System.getProperty("user.dir", "."));
        List<File> worlds = worlds(root, levelName(new File(root, "server.properties")));
        if (worlds.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        Result result = new Result();
        for (File world : worlds) {
            scanFolder(world, 0, deadline, result);
            if (result.markedRegion != null) {
                Safety.stop("height", "The world '" + world.getName() + "' was saved with the extended height (Y -64..319), see "
                        + root.toPath().relativize(result.markedRegion.toPath()) + ". Start the server with its height"
                        + " agent (ngheight-agent.jar or DeltaHeight.jar) next to DeltaAgent; without it, MCPC+ would"
                        + " regenerate those chunks.");
                return;
            }
        }
        if (result.outOfTime) {
            Log.warn("height", "World height check stopped after " + (TIME_BUDGET_MS / 1000) + " s ("
                    + result.chunks + " chunks in " + result.regionFiles + " region files), nothing found so far.");
        } else {
            Log.info("height", worlds.size() + " world folder(s) with the vanilla height (" + result.chunks
                    + " chunks checked in " + result.regionFiles + " region files).");
        }
    }

    /**
     * The main world first, then every other folder of the server holding a level.dat: Bukkit and
     * Multiverse worlds can live next to the main one.
     */
    static List<File> worlds(File root, String level) {
        List<File> worlds = new ArrayList<File>();
        File main = new File(root, level);
        if (main.isDirectory()) {
            worlds.add(main);
        }
        File[] folders = root.listFiles(File::isDirectory);
        if (folders != null) {
            Arrays.sort(folders);
            for (File folder : folders) {
                if (!folder.equals(main) && new File(folder, "level.dat").isFile()) {
                    worlds.add(folder);
                }
            }
        }
        return worlds;
    }

    static String levelName(File serverProperties) {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(serverProperties)) {
            properties.load(in);
        } catch (IOException e) {
            // No server.properties yet: the server will create it with the default level.
        }
        String level = properties.getProperty("level-name", "world").trim();
        return level.isEmpty() ? "world" : level;
    }

    /** Looks through every region folder of one world and its dimensions, stops at the first marked chunk. */
    static Result scan(File world, long deadline) {
        Result result = new Result();
        scanFolder(world, 0, deadline, result);
        return result;
    }

    private static void scanFolder(File folder, int depth, long deadline, Result result) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files); // same order, same log, on every start
        for (File file : files) {
            if (result.markedRegion != null || result.outOfTime) {
                return;
            }
            if (file.isDirectory()) {
                if (depth < MAX_DEPTH) {
                    scanFolder(file, depth + 1, deadline, result);
                }
            } else if (file.getName().endsWith(".mca") && "region".equals(folder.getName())) {
                result.regionFiles++;
                try {
                    if (regionHasMarker(file, deadline, result)) {
                        result.markedRegion = file;
                    }
                } catch (IOException e) {
                    Log.warn("height", "Could not read " + file + ": " + e);
                }
            }
        }
    }

    /** Reads the chunks of one Anvil region file until a marker shows up or time runs out. */
    static boolean regionHasMarker(File region, long deadline, Result result) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(region, "r")) {
            long length = file.length();
            if (length < 8192) {
                return false;
            }
            byte[] header = new byte[4096];
            file.readFully(header);
            for (int i = 0; i < 1024; i++) {
                if (System.currentTimeMillis() > deadline) {
                    result.outOfTime = true;
                    return false;
                }
                int offset = (header[i * 4] & 0xFF) << 16 | (header[i * 4 + 1] & 0xFF) << 8 | header[i * 4 + 2] & 0xFF;
                if (offset == 0 || header[i * 4 + 3] == 0) {
                    continue;
                }
                long position = offset * 4096L;
                if (position + 5 > length) {
                    continue;
                }
                file.seek(position);
                int size = file.readInt();
                int compression = file.readUnsignedByte();
                if (size <= 1 || position + 4 + size > length) {
                    continue;
                }
                byte[] data = new byte[size - 1];
                file.readFully(data);
                result.chunks++;
                byte[] nbt = inflate(compression, data);
                if (nbt != null && hasMarker(nbt)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasMarker(byte[] nbt) {
        for (byte[] marker : MARKERS) {
            if (Bytes.contains(nbt, marker)) {
                return true;
            }
        }
        return false;
    }

    /** 1 = gzip, 2 = zlib, as in RegionFile; null for anything unreadable. */
    private static byte[] inflate(int compression, byte[] data) {
        try (InputStream in = compression == 1 ? new GZIPInputStream(new ByteArrayInputStream(data))
                : compression == 2 ? new InflaterInputStream(new ByteArrayInputStream(data)) : null) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_CHUNK_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** An NBT tag name as stored: its length on two bytes, then its characters. */
    private static byte[][] nbtNames(String[] names) {
        byte[][] encoded = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            byte[] text = names[i].getBytes(StandardCharsets.UTF_8);
            encoded[i] = new byte[text.length + 2];
            encoded[i][0] = (byte) (text.length >>> 8);
            encoded[i][1] = (byte) text.length;
            System.arraycopy(text, 0, encoded[i], 2, text.length);
        }
        return encoded;
    }

    private HeightGuard() {
    }
}
