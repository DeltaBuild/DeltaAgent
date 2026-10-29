package fr.deltabuild.deltaagent.height;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeightGuardTest {

    private static final long NO_LIMIT = Long.MAX_VALUE;

    @TempDir
    Path dir;

    @Test
    void findsAChunkSavedByDeltaHeight() throws IOException {
        File world = dir.resolve("hub").toFile();
        region(new File(world, "region/r.0.0.mca"), 2, chunk("xPos", "DeltaHeight"));
        HeightGuard.Result result = HeightGuard.scan(world, NO_LIMIT);
        assertEquals("r.0.0.mca", result.markedRegion.getName());
    }

    @Test
    void findsAChunkSavedByNgHeightInADimension() throws IOException {
        File world = dir.resolve("hub").toFile();
        region(new File(world, "region/r.0.0.mca"), 2, chunk("xPos", "zPos"));
        region(new File(world, "DIM-31/region/r.-1.0.mca"), 1, chunk("xPos", "NGHeight"));
        HeightGuard.Result result = HeightGuard.scan(world, NO_LIMIT);
        assertEquals("r.-1.0.mca", result.markedRegion.getName());
    }

    @Test
    void acceptsAVanillaWorld() throws IOException {
        File world = dir.resolve("hub").toFile();
        region(new File(world, "region/r.0.0.mca"), 2, chunk("xPos", "zPos", "Sections"));
        HeightGuard.Result result = HeightGuard.scan(world, NO_LIMIT);
        assertNull(result.markedRegion);
        assertEquals(1, result.chunks);
    }

    @Test
    void ignoresTheWordInsideAnotherName() {
        assertFalse(HeightGuard.hasMarker(nbtName("DeltaHeightOld")));
        assertTrue(HeightGuard.hasMarker(nbtName("DeltaHeight")));
    }

    @Test
    void stopsWhenOutOfTime() throws IOException {
        File world = dir.resolve("hub").toFile();
        region(new File(world, "region/r.0.0.mca"), 2, chunk("xPos", "DeltaHeight"));
        HeightGuard.Result result = HeightGuard.scan(world, 0);
        assertTrue(result.outOfTime);
        assertNull(result.markedRegion);
    }

    @Test
    void checksOtherWorldFoldersAfterTheMainOne() throws IOException {
        File root = dir.toFile();
        new File(root, "hub").mkdirs();
        new File(root, "plugins/Essentials").mkdirs();
        new File(root, "test").mkdirs();
        Files.write(new File(root, "test/level.dat").toPath(), new byte[] {0});
        assertEquals(Arrays.asList(new File(root, "hub"), new File(root, "test")), HeightGuard.worlds(root, "hub"));
    }

    @Test
    void readsTheLevelName() throws IOException {
        File properties = dir.resolve("server.properties").toFile();
        Files.write(properties.toPath(), "motd=x\nlevel-name=hub\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("hub", HeightGuard.levelName(properties));
        assertEquals("world", HeightGuard.levelName(dir.resolve("missing.properties").toFile()));
    }

    /** NBT-like bytes holding the given tag names, enough for the marker search. */
    private static byte[] chunk(String... tagNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        for (String name : tagNames) {
            out.writeByte(1);
            out.writeUTF(name);
            out.writeByte(1);
        }
        out.writeByte(0);
        return bytes.toByteArray();
    }

    private static byte[] nbtName(String name) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new DataOutputStream(bytes).writeUTF(name);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** An Anvil region file with one chunk in slot 0, compressed with gzip (1) or zlib (2). */
    private static void region(File file, int compression, byte[] nbt) throws IOException {
        file.getParentFile().mkdirs();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream out = compression == 1 ? new GZIPOutputStream(compressed) : new DeflaterOutputStream(compressed)) {
            out.write(nbt);
        }
        byte[] data = compressed.toByteArray();
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(8192 + 4096);
            out.seek(0);
            out.writeInt(2 << 8 | 1); // chunk at sector 2, 1 sector long
            out.seek(8192);
            out.writeInt(data.length + 1);
            out.writeByte(compression);
            out.write(data);
        }
    }
}
