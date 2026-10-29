package fr.deltabuild.deltaagent.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The game classes every module wants to change. Filled during premain, read by
 * {@link GameTransformer} once the server runs.
 */
public final class PatchRegistry {

    private final Map<String, PatchTarget> targets = new ConcurrentHashMap<String, PatchTarget>();

    public void add(PatchTarget target) {
        PatchTarget previous = targets.putIfAbsent(target.className, target);
        if (previous != null) {
            throw new IllegalStateException(target.className + " is patched by both " + previous.module
                    + " and " + target.module + "; one patch per class.");
        }
    }

    public PatchTarget get(String className) {
        return targets.get(className);
    }

    public int size() {
        return targets.size();
    }

    public Collection<String> criticalClassNames() {
        List<String> names = new ArrayList<String>();
        for (PatchTarget target : targets.values()) {
            if (target.critical) {
                names.add(target.className);
            }
        }
        return names;
    }
}
