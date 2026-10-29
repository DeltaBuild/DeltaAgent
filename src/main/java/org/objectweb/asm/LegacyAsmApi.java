package org.objectweb.asm;

/**
 * Support for visitors written against the ASM 4 API, patched into the bundled ASM at build time.
 *
 * <p>An ASM 4 visitor only has {@code visitMethodInsn(opcode, owner, name, descriptor)}: ASM 9
 * cannot pass it whether the owner is an interface, and refuses the call when that flag cannot be
 * guessed from the opcode (invokestatic of a Java 8 static interface method). The patched
 * {@link MethodVisitor} records the flag here before calling the 4 argument method, and restores it
 * when that method forwards the instruction to the next visitor.
 */
public final class LegacyAsmApi {

    private static final ThreadLocal<Boolean> PENDING_INTERFACE = new ThreadLocal<Boolean>();

    public static void pendInterface(boolean isInterface) {
        PENDING_INTERFACE.set(isInterface);
    }

    public static void clearInterface() {
        PENDING_INTERFACE.remove();
    }

    /** The recorded flag if any, else the one guessed from the opcode. */
    public static boolean interfaceFlag(boolean guessed) {
        Boolean pending = PENDING_INTERFACE.get();
        if (pending == null) {
            return guessed;
        }
        PENDING_INTERFACE.remove();
        return pending;
    }

    private LegacyAsmApi() {
    }
}
