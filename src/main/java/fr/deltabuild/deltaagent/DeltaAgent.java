package fr.deltabuild.deltaagent;

import fr.deltabuild.deltaagent.core.GameTransformerInstaller;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.OtherAgents;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.Safety;
import fr.deltabuild.deltaagent.height.HeightGuard;
import fr.deltabuild.deltaagent.ids.legacy.LegacyIdTransformer;
import fr.deltabuild.deltaagent.java17.Java17;
import java.lang.instrument.Instrumentation;

/**
 * Entry point. Step 0: Java 17 support and the placeholder IDs of mcpc-java17, plus the game
 * transformer the next modules will fill. The world height is still left to NGHeight or DeltaHeight.
 */
public final class DeltaAgent {

    public static final String VERSION = version();

    public static void premain(String args, Instrumentation inst) {
        Log.info("core", "DeltaAgent " + VERSION + " on Java " + System.getProperty("java.version")
                + " (modules: java17, ids placeholders).");
        OtherAgents others = OtherAgents.onCommandLine();
        if (others.has(OtherAgents.Known.MCPC_JAVA17)) {
            Safety.stop("core", "mcpc-java17-agent.jar is loaded too. DeltaAgent replaces it: remove its -javaagent option.");
        }

        // JVM transformers run in the order they are added.
        Java17.start(inst);
        inst.addTransformer(new LegacyIdTransformer());

        OtherAgents.Known height = others.heightAgent();
        HeightGuard.check(height == null ? null : height.displayName);

        PatchRegistry registry = new PatchRegistry();
        inst.addTransformer(new GameTransformerInstaller(registry));
    }

    private static String version() {
        String version = DeltaAgent.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }

    private DeltaAgent() {
    }
}
