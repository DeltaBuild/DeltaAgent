package fr.deltabuild.deltaagent.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The game classes every module wants to change. Filled during premain, read by
 * {@link GameTransformer} once the server runs.
 *
 * <p>A class can take several patches, from one module or more, applied in the order they were
 * added; at most one module may replace it whole, and its replacement comes first.
 */
public final class PatchRegistry {

    /** Called once the LaunchClassLoader exists, before the first game class is patched. */
    public interface LoaderCheck {
        void check(ClassLoader launchClassLoader, PatchRegistry registry);
    }

    private final Map<String, List<PatchTarget>> targets = new ConcurrentHashMap<String, List<PatchTarget>>();
    private final List<LoaderCheck> loaderChecks = new CopyOnWriteArrayList<LoaderCheck>();

    public synchronized void add(PatchTarget target) {
        List<PatchTarget> list = targets.get(target.className);
        if (list == null) {
            list = new CopyOnWriteArrayList<PatchTarget>();
            targets.put(target.className, list);
        }
        if (target.replacement != null) {
            for (PatchTarget other : list) {
                if (other.replacement != null) {
                    throw new IllegalStateException(target.className + " is replaced by both " + other.module
                            + " and " + target.module + ".");
                }
            }
            list.add(0, target);
        } else {
            list.add(target);
        }
    }

    /** The targets of a class, replacement first, or null when nothing changes it. */
    public List<PatchTarget> get(String className) {
        List<PatchTarget> list = targets.get(className);
        return list == null ? null : Collections.unmodifiableList(list);
    }

    /** Drops every target of a module, e.g. when the server turns out to have its own version of it. */
    public synchronized int removeModule(String module) {
        int removed = 0;
        for (Map.Entry<String, List<PatchTarget>> entry : targets.entrySet()) {
            for (PatchTarget target : entry.getValue()) {
                if (target.module.equals(module)) {
                    entry.getValue().remove(target);
                    removed++;
                }
            }
            if (entry.getValue().isEmpty()) {
                targets.remove(entry.getKey());
            }
        }
        return removed;
    }

    /** Number of classes changed. */
    public int size() {
        return targets.size();
    }

    public Collection<String> criticalClassNames() {
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, List<PatchTarget>> entry : targets.entrySet()) {
            for (PatchTarget target : entry.getValue()) {
                if (target.critical) {
                    names.add(entry.getKey());
                    break;
                }
            }
        }
        return names;
    }

    public void addLoaderCheck(LoaderCheck check) {
        loaderChecks.add(check);
    }

    void runLoaderChecks(ClassLoader launchClassLoader) {
        for (LoaderCheck check : loaderChecks) {
            check.check(launchClassLoader, this);
        }
    }
}
