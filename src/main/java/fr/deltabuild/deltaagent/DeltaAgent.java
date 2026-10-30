package fr.deltabuild.deltaagent;

import fr.deltabuild.deltaagent.core.GameTransformerInstaller;
import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.OtherAgents;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.Safety;
import fr.deltabuild.deltaagent.height.HeightGuard;
import fr.deltabuild.deltaagent.height.HeightModule;
import fr.deltabuild.deltaagent.ids.IdsModule;
import fr.deltabuild.deltaagent.java17.Java17;
import java.lang.instrument.Instrumentation;

/**
 * Entry point. Step 2: Java 17 support, the world from Y -64 to 319, and block and item registries
 * up to 2^24 IDs with items above 31999 saved and sent.
 */
public final class DeltaAgent {

    public static final String VERSION = version();

    public static void premain(String args, Instrumentation inst) {
        boolean height = HeightModule.enabled();
        boolean ids = IdsModule.enabled();
        Log.info("core", "DeltaAgent " + VERSION + " on Java " + System.getProperty("java.version")
                + " (modules: java17" + (height ? ", height" : "") + (ids ? ", ids" : "") + ").");
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

        PatchRegistry registry = new PatchRegistry();
        if (height) {
            HeightModule.start(inst, registry);
        } else {
            HeightGuard.check(heightAgent == null ? null : heightAgent.displayName);
        }
        if (ids) {
            IdsModule.start(registry);
        } else {
            IdsModule.guardOnly(registry);
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
