package fr.deltabuild.deltaagent.core;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The other java agents on the command line that DeltaAgent knows about, found through the
 * Premain-Class of their jar rather than the file name, which server owners often change.
 */
public final class OtherAgents {

    public enum Known {
        MCPC_JAVA17("gg.nationsglory.mcpcj17.McpcJava17Agent", "mcpc-java17"),
        NGHEIGHT("fr.skhr.deltabuild.ngheight.NGHeightAgent", "NGHeight"),
        DELTAHEIGHT("fr.deltabuild.deltaheight.DeltaHeightAgent", "DeltaHeight");

        final String premainClass;
        public final String displayName;

        Known(String premainClass, String displayName) {
            this.premainClass = premainClass;
            this.displayName = displayName;
        }

        static Known byPremainClass(String premainClass) {
            for (Known known : values()) {
                if (known.premainClass.equals(premainClass)) {
                    return known;
                }
            }
            return null;
        }
    }

    private final Set<Known> found;

    private OtherAgents(Set<Known> found) {
        this.found = found;
    }

    public static OtherAgents onCommandLine() {
        List<String> arguments;
        try {
            arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            Log.warn("core", "Could not read the JVM arguments, other agents not checked: " + t);
            return new OtherAgents(EnumSet.noneOf(Known.class));
        }
        return fromArguments(arguments, new File(System.getProperty("user.dir", ".")));
    }

    static OtherAgents fromArguments(List<String> arguments, File workingDir) {
        Set<Known> found = EnumSet.noneOf(Known.class);
        for (String argument : arguments) {
            if (!argument.startsWith("-javaagent:")) {
                continue;
            }
            String path = argument.substring("-javaagent:".length());
            int options = path.indexOf('=');
            if (options >= 0) {
                path = path.substring(0, options);
            }
            File jar = new File(path);
            if (!jar.isAbsolute()) {
                jar = new File(workingDir, path);
            }
            Known known = Known.byPremainClass(premainClass(jar));
            if (known != null) {
                found.add(known);
            }
        }
        return new OtherAgents(found);
    }

    private static String premainClass(File jar) {
        try (JarFile file = new JarFile(jar)) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(new Attributes.Name("Premain-Class"));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean has(Known agent) {
        return found.contains(agent);
    }

    /** The height agent already on the command line, or null. */
    public Known heightAgent() {
        if (found.contains(Known.DELTAHEIGHT)) {
            return Known.DELTAHEIGHT;
        }
        return found.contains(Known.NGHEIGHT) ? Known.NGHEIGHT : null;
    }
}
