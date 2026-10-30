import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Builds libs/mcpc-srg.jar: the MCPC+ server jar with MCP class names and SRG member names, as FML
 * sees the classes at runtime. Only needed to regenerate libs/mcpc-stubs.jar and to run the benches.
 *
 * <p>1. Extract the mappings from the server jar:
 * <pre>python -c "import lzma,zipfile,os;os.makedirs('build',exist_ok=True);open('build/srg.txt','wb').write(lzma.decompress(zipfile.ZipFile('libs/minecraft_server.jar').read('deobfuscation_data-1.6.4.lzma')))"</pre>
 * 2. Run {@code gradlew mcpcSrg}.
 *
 * <p>Arguments: srg.txt, minecraft_server.jar, output jar.
 */
public class Deobf {

    private static final Map<String, String> classes = new HashMap<>();
    private static final Map<String, String> fields = new HashMap<>();
    private static final Map<String, String> methods = new HashMap<>();
    private static final Map<String, String[]> parents = new HashMap<>();

    public static void main(String[] args) throws Exception {
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            String[] parts = line.split(" ");
            if (line.startsWith("CL:")) {
                classes.put(parts[1], parts[2]);
            } else if (line.startsWith("FD:")) {
                fields.put(parts[1], parts[2].substring(parts[2].lastIndexOf('/') + 1));
            } else if (line.startsWith("MD:")) {
                methods.put(parts[1] + " " + parts[2], parts[3].substring(parts[3].lastIndexOf('/') + 1));
            }
        }
        Map<String, byte[]> server = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(args[1])) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                String name = entry.getName().substring(0, entry.getName().length() - 6);
                // Obfuscated Minecraft classes have no package; libraries are left out.
                boolean wanted = !name.contains("/") || name.startsWith("net/minecraft/") || name.startsWith("net/minecraftforge/")
                        || name.startsWith("cpw/") || name.startsWith("org/bukkit/") || name.startsWith("org/spigotmc/")
                        || name.startsWith("za/");
                if (!wanted) {
                    continue;
                }
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                server.put(name, bytes);
                ClassReader reader = new ClassReader(bytes);
                String[] interfaces = reader.getInterfaces();
                String[] all = new String[interfaces.length + 1];
                all[0] = reader.getSuperName();
                System.arraycopy(interfaces, 0, all, 1, interfaces.length);
                parents.put(name, all);
            }
        }
        Remapper remapper = new Remapper() {
            @Override
            public String map(String name) {
                return classes.getOrDefault(name, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String desc) {
                String mapped = find(owner, name, null);
                return mapped == null ? name : mapped;
            }

            @Override
            public String mapMethodName(String owner, String name, String desc) {
                if (name.startsWith("<")) {
                    return name;
                }
                String mapped = find(owner, name, desc);
                return mapped == null ? name : mapped;
            }

            /** Members can be declared by a parent class or an interface. */
            private String find(String owner, String name, String desc) {
                Deque<String> queue = new ArrayDeque<>();
                queue.add(owner);
                Set<String> seen = new HashSet<>();
                while (!queue.isEmpty()) {
                    String type = queue.poll();
                    if (type == null || !seen.add(type)) {
                        continue;
                    }
                    String mapped = desc == null ? fields.get(type + "/" + name) : methods.get(type + "/" + name + " " + desc);
                    if (mapped != null) {
                        return mapped;
                    }
                    String[] above = parents.get(type);
                    if (above != null) {
                        for (String parent : above) {
                            queue.add(parent);
                        }
                    }
                }
                return null;
            }
        };
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[2]))) {
            for (Map.Entry<String, byte[]> entry : server.entrySet()) {
                try {
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(entry.getValue()).accept(new ClassRemapper(writer, remapper), 0);
                    out.putNextEntry(new ZipEntry(remapper.map(entry.getKey()) + ".class"));
                    out.write(writer.toByteArray());
                    out.closeEntry();
                } catch (Throwable t) {
                    System.err.println(entry.getKey() + " " + t);
                }
            }
        }
    }
}
