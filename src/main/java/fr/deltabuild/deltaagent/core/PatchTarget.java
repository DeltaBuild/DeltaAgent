package fr.deltabuild.deltaagent.core;

/**
 * A game class the agent changes, either through a {@link ClassPatch} or by replacing it whole.
 *
 * <p>A critical target is one the world or the protocol depends on: if it cannot be patched, the
 * server stops instead of running half patched.
 */
public final class PatchTarget {

    final String module;
    final String className;
    final boolean critical;
    final ClassPatch patch;
    final byte[] replacement;

    private PatchTarget(String module, String className, boolean critical, ClassPatch patch, byte[] replacement) {
        this.module = module;
        this.className = className;
        this.critical = critical;
        this.patch = patch;
        this.replacement = replacement;
    }

    /** @param className dotted name after deobfuscation, e.g. net.minecraft.world.World */
    public static PatchTarget patch(String module, String className, boolean critical, ClassPatch patch) {
        return new PatchTarget(module, className, critical, patch, null);
    }

    public static PatchTarget replace(String module, String className, byte[] replacement) {
        return new PatchTarget(module, className, true, null, replacement);
    }

    public String className() {
        return className;
    }

    public boolean critical() {
        return critical;
    }
}
