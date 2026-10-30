package fr.deltabuild.deltaagent;

import fr.deltabuild.deltaagent.core.GameTransformerInstaller;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.OtherAgents;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.Safety;
import fr.deltabuild.deltaagent.height.HeightGuard;
import fr.deltabuild.deltaagent.height.HeightModule;
import fr.deltabuild.deltaagent.ids.legacy.LegacyIdTransformer;
import fr.deltabuild.deltaagent.java17.Java17;
import java.lang.instrument.Instrumentation;

/**
 * Entry point. Step 1: Java 17 support, the world from Y -64 to 319, and the placeholder IDs of
 * mcpc-java17 until the ids module replaces them.
 */
public final class DeltaAgent {

    public static final String VERSION = version();

    public static void premain(String args, Instrumentation inst) {
        boolean height = HeightModule.enabled();
        Log.info("core", "DeltaAgent " + VERSION + " on Java " + System.getProperty("java.version")
                + " (modules: java17" + (height ? ", height" : "") + ", ids placeholders).");
        OtherAgents others = OtherAgents.onCommandLine();
        if (others.has(OtherAgents.Known.MCPC_JAVA17)) {
            Safety.stop("core", "mcpc-java17-agent.jar is loaded too. DeltaAgent replaces it: remove its -javaagent option.");
        }
        OtherAgents.Known heightAgent = others.heightAgent();
        if (height && heightAgent != null) {
            Safety.stop("core", heightAgent.displayName + " is loaded too. DeltaAgent now handles the world height:"
                    + " remove its -javaagent option.");
        }

        // JVM transformers run in the order they are added.
        Java17.start(inst);
        inst.addTransformer(new LegacyIdTransformer());

        PatchRegistry registry = new PatchRegistry();
        if (height) {
            HeightModule.start(inst, registry);
        } else {
            HeightGuard.check(heightAgent == null ? null : heightAgent.displayName);
        }
        inst.addTransformer(new GameTransformerInstaller(registry));
    }

    private static String version() {
        String version = DeltaAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    private DeltaAgent() {
    }
}
