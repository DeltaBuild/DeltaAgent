package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtherAgentsTest {

    @TempDir
    Path dir;

    @Test
    void findsAgentsByPremainClassWhateverTheirFileName() throws Exception {
        agentJar("j17.jar", "gg.nationsglory.mcpcj17.McpcJava17Agent");
        agentJar("height.jar", "fr.deltabuild.deltaheight.DeltaHeightAgent");
        OtherAgents agents = OtherAgents.fromArguments(Arrays.asList(
                "-Xmx4G", "-javaagent:j17.jar", "-javaagent:height.jar=debug"), dir.toFile());
        assertTrue(agents.has(OtherAgents.Known.MCPC_JAVA17));
        assertEquals(OtherAgents.Known.DELTAHEIGHT, agents.heightAgent());
    }

    @Test
    void findsAgentsByAbsolutePath() throws Exception {
        File delta = agentJar("delta.jar", "fr.deltabuild.deltaheight.DeltaHeightAgent");
        OtherAgents agents = OtherAgents.fromArguments(Arrays.asList(
                "-javaagent:" + delta.getAbsolutePath()), new File("/nowhere"));
        assertEquals(OtherAgents.Known.DELTAHEIGHT, agents.heightAgent());
    }

    @Test
    void ignoresUnknownOrMissingJars() throws Exception {
        agentJar("other.jar", "com.example.Agent");
        OtherAgents agents = OtherAgents.fromArguments(Arrays.asList(
                "-javaagent:other.jar", "-javaagent:missing.jar"), dir.toFile());
        assertFalse(agents.has(OtherAgents.Known.MCPC_JAVA17));
        assertNull(agents.heightAgent());
    }

    private File agentJar(String name, String premainClass) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), premainClass);
        File jar = dir.resolve(name).toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            out.flush();
        }
        return jar;
    }
}
