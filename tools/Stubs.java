import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Builds libs/mcpc-stubs.jar: signatures only (no code) of the MCPC+ classes and members used by
 * the replacement classes of src/replace/java. The project then compiles without the server jar,
 * on GitHub Actions too. Run by {@code gradlew mcpcStubs}, again whenever a replacement class uses
 * a new MCPC+ class or member.
 *
 * <p>Arguments: output jar, folder of the compiled replacement classes, MCPC+ jars (SRG names first).
 */
public class Stubs {

    private static final Pattern TYPE = Pattern.compile("L([^;<>:]+)[;<]");

    private static final Map<String, ClassNode> classes = new HashMap<>();
    private static final Set<String> own = new HashSet<>();
    private static final Set<String> needed = new TreeSet<>();
    private static final Map<String, Set<String>> members = new HashMap<>();

    public static void main(String[] args) throws Exception {
        for (int i = 2; i < args.length; i++) {
            load(Paths.get(args[i]));
        }
        List<Path> replacements;
        try (Stream<Path> files = Files.walk(Paths.get(args[1]))) {
            replacements = files.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        List<ClassNode> sources = new ArrayList<>();
        for (Path file : replacements) {
            ClassNode node = read(Files.readAllBytes(file), 0);
            own.add(node.name);
            sources.add(node);
        }
        for (ClassNode node : sources) {
            collect(node);
        }

        // Parents, outer classes and the types of the members kept, until nothing new shows up.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new ArrayList<>(needed)) {
                ClassNode node = classes.get(name);
                int before = needed.size();
                addTypes(node.superName);
                for (String itf : node.interfaces) {
                    addTypes(itf);
                }
                addTypes(node.signature);
                for (InnerClassNode inner : node.innerClasses) {
                    if (inner.name.equals(name) && inner.outerName != null) {
                        addTypes(inner.outerName);
                    }
                }
                for (FieldNode field : node.fields) {
                    if (kept(name, field.name, field.desc)) {
                        addTypes(field.desc);
                        addTypes(field.signature);
                    }
                }
                for (MethodNode method : node.methods) {
                    if (kept(name, method.name, method.desc)) {
                        addTypes(method.desc);
                        addTypes(method.signature);
                        for (String exception : method.exceptions) {
                            addTypes(exception);
                        }
                    }
                }
                changed |= needed.size() != before;
            }
        }

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Paths.get(args[0])))) {
            for (String name : needed) {
                JarEntry entry = new JarEntry(name + ".class");
                entry.setTime(0L); // same bytes on every run
                out.putNextEntry(entry);
                out.write(stub(classes.get(name)));
                out.closeEntry();
            }
        }
        System.out.println("Stubs: " + needed.size() + " classes for " + own.size() + " replacement classes -> " + args[0]);
    }

    /** Every type and member a replacement class refers to. */
    private static void collect(ClassNode node) {
        addTypes(node.superName);
        for (String itf : node.interfaces) {
            addTypes(itf);
        }
        for (FieldNode field : node.fields) {
            addTypes(field.desc);
            addTypes(field.signature);
        }
        for (MethodNode method : node.methods) {
            addTypes(method.desc);
            addTypes(method.signature);
            for (String exception : method.exceptions) {
                addTypes(exception);
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                addTypes(block.type);
            }
            if (method.localVariables != null) {
                for (LocalVariableNode local : method.localVariables) {
                    addTypes(local.desc);
                    addTypes(local.signature);
                }
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof TypeInsnNode) {
                    addTypes(((TypeInsnNode) insn).desc);
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) insn;
                    addMember(field.owner, field.name, field.desc);
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    addMember(call.owner, call.name, call.desc);
                } else if (insn instanceof MultiANewArrayInsnNode) {
                    addTypes(((MultiANewArrayInsnNode) insn).desc);
                } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) {
                    addTypes(((Type) ((LdcInsnNode) insn).cst).getDescriptor());
                } else if (insn instanceof FrameNode) {
                    addFrame(((FrameNode) insn).local);
                    addFrame(((FrameNode) insn).stack);
                }
            }
        }
    }

    private static void load(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String file = entry.getName();
                if (!file.endsWith(".class")) {
                    continue;
                }
                String name = file.substring(0, file.length() - 6);
                // The first jar wins: SRG names before the obfuscated server jar.
                if (!classes.containsKey(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        classes.put(name, read(in.readAllBytes(), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
                    }
                }
            }
        }
    }

    private static ClassNode read(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    private static void addTypes(String text) {
        if (text == null) {
            return;
        }
        if (!text.contains(";") && !text.contains("(")) {
            addClass(text.startsWith("[") ? null : text);
            return;
        }
        Matcher matcher = TYPE.matcher(text);
        while (matcher.find()) {
            addClass(matcher.group(1));
        }
    }

    private static void addFrame(List<Object> values) {
        if (values != null) {
            for (Object value : values) {
                if (value instanceof String) {
                    String type = (String) value;
                    addTypes(type.startsWith("[") ? type : "L" + type + ";");
                }
            }
        }
    }

    private static void addClass(String name) {
        if (name != null && !own.contains(name) && classes.containsKey(name)) {
            needed.add(name);
        }
    }

    private static void addMember(String owner, String name, String desc) {
        if (owner.startsWith("[")) {
            return;
        }
        addClass(owner);
        addTypes(desc);
        String declaring = declaring(owner, name, desc);
        if (declaring != null && !own.contains(declaring)) {
            addClass(declaring);
            members.computeIfAbsent(declaring, k -> new HashSet<>()).add(name + desc);
        }
    }

    /** The class that really declares the member: maybe a parent class or an interface. */
    private static String declaring(String owner, String name, String desc) {
        ClassNode node = classes.get(owner);
        if (node == null) {
            return null;
        }
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return owner;
            }
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return owner;
            }
        }
        String found = node.superName != null ? declaring(node.superName, name, desc) : null;
        for (int i = 0; found == null && i < node.interfaces.size(); i++) {
            found = declaring(node.interfaces.get(i), name, desc);
        }
        return found;
    }

    private static boolean kept(String owner, String name, String desc) {
        Set<String> kept = members.get(owner);
        return kept != null && kept.contains(name + desc);
    }

    /** The class with only the members kept; methods throw if they ever run. */
    private static byte[] stub(ClassNode source) {
        ClassNode node = new ClassNode();
        node.version = Math.min(source.version, Opcodes.V1_8);
        node.access = source.access;
        node.name = source.name;
        node.signature = source.signature;
        node.superName = source.superName;
        node.interfaces = new ArrayList<>(source.interfaces);
        for (InnerClassNode inner : source.innerClasses) {
            if (needed.contains(inner.name) && (inner.outerName == null || needed.contains(inner.outerName))) {
                node.innerClasses.add(inner);
            }
        }
        for (FieldNode field : source.fields) {
            if (kept(source.name, field.name, field.desc)) {
                node.fields.add(new FieldNode(field.access, field.name, field.desc, field.signature, field.value));
            }
        }
        for (MethodNode method : source.methods) {
            if (!kept(source.name, method.name, method.desc)) {
                continue;
            }
            MethodNode stub = new MethodNode(method.access, method.name, method.desc, method.signature,
                    method.exceptions.toArray(new String[0]));
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                stub.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                stub.instructions.add(new InsnNode(Opcodes.ATHROW));
                stub.maxStack = 1;
                stub.maxLocals = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
            }
            node.methods.add(stub);
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
